package com.personal.hockey.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.personal.hockey.input.Btn
import com.personal.hockey.input.Controls
import com.personal.hockey.input.ControlsLayout

private val SHADE = Color(0xFF0B1A2A)

private fun label(btn: Btn) = when (btn) {
    Btn.SHOOT -> "SHOOT"; Btn.PASS -> "PASS"; Btn.DEKE -> "DEKE"; Btn.POKE -> "POKE"; Btn.BODY -> "BODY"
}

/**
 * The stick and the buttons for [layout]. [frame] is read in the draw block so held state, which is
 * not Compose state, repaints every frame. Touch is handled by the screen, not here.
 */
@Composable
fun ControlsOverlay(controls: Controls, layout: ControlsLayout, hasPuck: Boolean, frame: () -> Int) {
    val spots = layout.buttons(hasPuck)
    val rim = remember(layout.density) { Stroke(2f * layout.density) }
    val restRing = remember(layout.density) { Stroke(3f * layout.density) }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            frame()
            // Dark fills with light rims read on both the near-white ice and the dark boards.
            if (controls.stickActive) {
                drawCircle(SHADE, layout.stickRadius, Offset(controls.stickBaseX, controls.stickBaseY), alpha = 0.3f)
                val knob = Offset(controls.stickKnobX, controls.stickKnobY)
                drawCircle(SHADE, layout.stickRadius * 0.45f, knob, alpha = 0.6f)
                drawCircle(Color.White, layout.stickRadius * 0.45f, knob, alpha = 0.7f, style = rim)
            } else {
                drawCircle(SHADE, layout.stickRadius, Offset(layout.stickRestX, layout.stickRestY), alpha = 0.45f, style = restRing)
            }
            for (i in spots.indices) {
                val s = spots[i]
                val c = Offset(s.cx, s.cy)
                drawCircle(SHADE, s.radius, c, alpha = if (controls.isDown(s.btn)) 0.75f else 0.45f)
                drawCircle(Color.White, s.radius, c, alpha = 0.7f, style = rim)
            }
        }
        val density = LocalDensity.current
        for (s in spots) {
            val diameter = with(density) { (s.radius * 2).toDp() }
            Box(
                Modifier
                    .offset { IntOffset((s.cx - s.radius).toInt(), (s.cy - s.radius).toInt()) }
                    .size(diameter),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(s.btn), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}
