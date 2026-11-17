package com.personal.hockey.game

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.hockey.input.Controls
import com.personal.hockey.input.ControlsLayout
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The controls sit over near-white ice: they must stand out against it, not vanish into it. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-land")
class ControlsOverlayTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val ice = Color(0xFFF2F6F8)

    private fun luminanceAt(bmp: Bitmap, x: Float, y: Float): Float {
        val c = bmp.getPixel(x.toInt(), y.toInt())
        return (0.2126f * (c shr 16 and 0xFF) + 0.7152f * (c shr 8 and 0xFF) + 0.0722f * (c and 0xFF)) / 255f
    }

    @Test fun buttons_and_stick_outline_stand_out_on_ice() {
        val layout = ControlsLayout(800f, 360f, 1f)
        val controls = Controls(layout)
        compose.mainClock.autoAdvance = false   // see PauseOverlayTest
        compose.setContent {
            Box(Modifier.fillMaxSize().background(ice)) { ControlsOverlay(controls, layout, hasPuck = true) { 0 } }
        }
        compose.mainClock.advanceTimeByFrame()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        val iceL = luminanceAt(bmp, 400f, 20f)
        for (s in layout.buttons(true)) {
            // Just inside the rim, clear of the label.
            val l = luminanceAt(bmp, s.cx, s.cy + s.radius * 0.8f)
            assertTrue("${s.btn}: $l vs ice $iceL", iceL - l > 0.25f)
        }
        val ring = luminanceAt(bmp, layout.stickRestX + layout.stickRadius, layout.stickRestY)
        assertTrue("stick outline $ring vs ice $iceL", iceL - ring > 0.25f)
    }
}
