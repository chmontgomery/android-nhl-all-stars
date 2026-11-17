package com.personal.hockey.render

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.hockey.teams.Crest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RinkPainterTest {
    private val w = 800
    private val h = 360
    private val camera = Camera().apply { setViewport(w.toFloat(), h.toFloat()) }
    private val painter = RinkPainter(Arena.ALL_STARS)

    private fun render(block: DrawScope.() -> Unit): ImageBitmap {
        val bmp = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat()), block)
        return bmp
    }

    @Test fun draws_without_crashing_at_several_camera_positions() {
        for (x in floatArrayOf(-30f, -12f, 0f, 7f, 30f)) {
            camera.snapTo(x)
            render { painter.draw(this, camera); painter.drawNearBoards(this, camera) }
        }
    }

    @Test fun centre_of_ice_is_light_and_stands_are_dark() {
        camera.snapTo(0f)
        val pixels = render { painter.draw(this, camera); painter.drawNearBoards(this, camera) }.toPixelMap()
        val out = FloatArray(3)
        camera.project(camera.focusX + 8f, 5f, 0f, out)
        val ice = pixels[out[0].toInt(), out[1].toInt()]
        // The closer camera shows the stands, through the far glass, only once it follows play there.
        camera.snapTo(0f, 100f)
        val stands = render { painter.draw(this, camera); painter.drawNearBoards(this, camera) }.toPixelMap()[400, 10]
        assertTrue("ice $ice", ice.luminance() > 0.8f)
        assertTrue("stands $stands", stands.luminance() < 0.5f)
    }

    @Test fun every_crest_draws_inside_its_box() {
        for (crest in Crest.entries) {
            val pixels = render {
                Crests.draw(this, crest, 400f, 180f, 100f, Color(0xFF154734), Color(0xFFA6192E))
            }.toPixelMap()
            assertTrue("$crest centre should be painted", pixels[400, 180].alpha > 0.5f)
            assertTrue("$crest should stay in its box", pixels[300, 60].alpha < 0.01f && pixels[520, 300].alpha < 0.01f)
        }
    }

    // The scoreboard at x 100..400, y 0..60; the text is 40 px tall with a 60 px fade.
    private val hud = floatArrayOf(100f, 0f, 400f, 60f, 0f, 0f, 0f, 0f)

    @Test fun board_text_well_clear_of_the_scoreboard_is_opaque() {
        assertEquals(1f, boardTextAlpha(500f, 20f, 700f, 60f, hud, 60f), 0f)
        // Overlapping it horizontally but below it.
        assertEquals(1f, boardTextAlpha(200f, 70f, 300f, 110f, hud, 60f), 0f)
    }

    @Test fun board_text_fades_as_it_nears_the_scoreboard_and_is_gone_once_it_touches() {
        val near = boardTextAlpha(430f, 20f, 630f, 60f, hud, 60f) // 30 px clear
        assertEquals(0.5f, near, 1e-4f)
        assertEquals(0f, boardTextAlpha(400f, 20f, 600f, 60f, hud, 60f), 0f)
        assertEquals(0f, boardTextAlpha(250f, 20f, 450f, 60f, hud, 60f), 0f)
        // From the left side too.
        assertEquals(0.5f, boardTextAlpha(-130f, 20f, 70f, 60f, hud, 60f), 1e-4f)
    }

    @Test fun an_empty_slot_is_ignored() {
        assertEquals(1f, boardTextAlpha(-10f, -10f, 10f, 10f, floatArrayOf(0f, 0f, 0f, 0f), 60f), 0f)
    }

    @Test fun centre_logo_is_painted_on_the_ice_wherever_the_camera_is() {
        val at = FloatArray(3)
        for (x in floatArrayOf(-6f, 0f, 6f)) {
            camera.snapTo(x)
            val pixels = render { painter.draw(this, camera) }.toPixelMap()
            fun lum(rx: Float, ry: Float): Float {
                camera.project(rx, ry, 0f, at)
                return pixels[at[0].toInt(), at[1].toInt()].luminance()
            }
            // A quarter of the way down one stick's shaft, the puck's centre, and bare ice beside them.
            assertTrue("stick at camera $x", lum(-0.9f, 1.3f) < 0.4f)
            assertTrue("puck at camera $x", lum(0f, -2.05f) < 0.4f)
            assertTrue("ice at camera $x", lum(-2.2f, -1.0f) > 0.8f)
        }
    }
}
