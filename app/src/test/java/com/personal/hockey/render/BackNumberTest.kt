package com.personal.hockey.render

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.hockey.render.anim.AnimState
import com.personal.hockey.settings.Level
import com.personal.hockey.sim.Act
import com.personal.hockey.sim.Goalie
import com.personal.hockey.sim.GoalieSkill
import com.personal.hockey.sim.HockeySim
import com.personal.hockey.sim.Position
import com.personal.hockey.sim.Rink
import com.personal.hockey.sim.Side
import com.personal.hockey.sim.Skater
import com.personal.hockey.teams.MatchSetup
import com.personal.hockey.teams.Teams
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.PI
import kotlin.random.Random

/** The jersey number is printed on the back: it turns with the torso and shows only when the back faces the camera. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackNumberTest {
    private val w = 1600
    private val h = 720
    private val camera = Camera().apply { setViewport(w.toFloat(), h.toFloat()); snapTo(0f) }
    private val figures = FigurePainter()

    private fun skater(facing: Float) = Skater(1, Side.HOME, Position.C).apply {
        x = 0f; y = camera.focusY; this.facing = facing
    }

    private fun alpha(facing: Float): Float {
        figures.poseSkater(camera, skater(facing), AnimState())
        return figures.backNumberAlpha(camera)
    }

    @Test fun the_camera_looks_from_minus_y() {
        // The facings below assume the eye sits toward -y of the skaters it looks at.
        assertTrue(camera.eyeY < camera.focusY)
    }

    @Test fun number_hidden_when_facing_camera() {
        val a = alpha((-PI / 2).toFloat())
        assertTrue("alpha $a", a < 0.01f)
    }

    @Test fun number_full_when_back_to_camera() {
        val a = alpha((PI / 2).toFloat())
        assertTrue("alpha $a", a > 0.99f)
    }

    @Test fun number_fades_side_on() {
        val a = alpha(0f)
        assertTrue("alpha $a", a < 0.3f)
    }

    /**
     * Goalies go through the same rule. Facing out of his net he is side-on and shows nothing; moved
     * far up the ice from the camera, his back turns toward it and the number shows.
     */
    @Test fun goalie_number_follows_the_same_rule() {
        val g = Goalie(Side.HOME, GoalieSkill(0.1f, 3f, 0.5f)).apply { y = camera.focusY }
        assertTrue("HOME goalie faces +x", Side.HOME.dir > 0f)
        g.x = -Rink.GOAL_X
        figures.poseGoalie(camera, g, AnimState())
        val atNet = figures.backNumberAlpha(camera)
        assertTrue("at his net: alpha $atNet", atNet < 0.01f)
        g.x = 25f
        figures.poseGoalie(camera, g, AnimState())
        val upIce = figures.backNumberAlpha(camera)
        assertTrue("up the ice, back toward the eye: alpha $upIce", upIce > 0.9f)
    }

    @Test fun number_reads_left_to_right() {
        val kit = KitColors(Teams.byId("MIN")!!.darkKit)
        val bmp = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
            figures.drawSkater(this, camera, skater((PI / 2).toFloat()), kit, 88, AnimState())
        }
        val n = FigurePainter.NUMBER_H
        val pts = floatArrayOf(0f, n / 2f, n, n / 2f, n / 2f, 0f, n / 2f, n)
        figures.numberMatrix.mapPoints(pts)
        assertTrue("text left ${pts[0]} -> right ${pts[2]}", pts[2] > pts[0] + 5f)
        assertTrue("text top ${pts[5]} above bottom ${pts[7]}", pts[5] < pts[7] - 5f)
    }

    /**
     * Writes build/preview/skater-close-up-turn.png: two rows of standing skaters, home behind away,
     * turning from side-on (left) through back-to-camera to side-on (right), the middle away skater
     * leaning into a check, for a person to see the number turn and tilt with the torso.
     */
    @Test fun writes_a_turntable_preview() {
        val r = CanvasRenderer(MatchSetup.of(Teams.byId("MIN")!!, Teams.byId("COL")!!, Level.EASY))
        r.camera.setViewport(w.toFloat(), h.toFloat())
        r.camera.zoomGoal = 1.9f
        r.camera.snapTo(0f, 3f)
        val sim = HockeySim(Random(1))
        sim.startPlayForTest()
        sim.controlledId = -1
        sim.puck.x = 0f; sim.puck.y = -30f
        for (s in sim.skaters) {
            val k = s.id % 5
            s.x = -4.4f + 2.2f * k; s.y = if (s.id < 5) 2.5f else -0.5f; s.vx = 0f; s.vy = 0f
            s.facing = ((10f + 40f * k) * PI / 180.0).toFloat()
            s.act = if (s.id >= 5 && k == 2) Act.BODY else Act.NONE
        }
        val bmp = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
            r.draw(this, sim, 0f)
        }
        val file = File("build/preview/skater-close-up-turn.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bmp.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("${file.absolutePath} ${file.length()} bytes", file.length() > 10_000)
    }
}
