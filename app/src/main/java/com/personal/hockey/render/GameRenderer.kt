package com.personal.hockey.render

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.personal.hockey.sim.HockeySim

interface GameRenderer {
    /** timeS is wall time for animations. Must not allocate. */
    fun draw(scope: DrawScope, sim: HockeySim, timeS: Float)
}
