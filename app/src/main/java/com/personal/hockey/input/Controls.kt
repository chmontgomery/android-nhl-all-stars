package com.personal.hockey.input

import com.personal.hockey.sim.SkaterInput
import kotlin.math.sqrt

/** Turns raw touch pointers into one skater's [SkaterInput]. No allocation per event. */
class Controls(layout: ControlsLayout) {
    var layout: ControlsLayout = layout
        set(value) {
            field = value
            cancelAll()
        }

    val input = SkaterInput()

    /** Set by the game every frame; changing it releases all held buttons. */
    var hasPuck = false
        set(value) {
            if (field != value) {
                field = value
                releaseButtons()
            }
        }

    var stickActive = false; private set
    var stickBaseX = layout.stickRestX; private set
    var stickBaseY = layout.stickRestY; private set
    var stickKnobX = layout.stickRestX; private set
    var stickKnobY = layout.stickRestY; private set

    private var stickPointer = NONE
    private val buttonPointer = LongArray(Btn.entries.size) { NONE }

    fun isDown(btn: Btn): Boolean = buttonPointer[btn.ordinal] != NONE

    fun down(pointerId: Long, x: Float, y: Float) {
        if (owns(pointerId)) return
        val spot = buttonAt(x, y)
        if (spot != null) {
            if (!isDown(spot.btn)) {
                buttonPointer[spot.btn.ordinal] = pointerId
                syncButtons()
            }
            return
        }
        if (x < layout.stickZoneRight && !stickActive) {
            stickActive = true
            stickPointer = pointerId
            val r = layout.stickRadius
            stickBaseX = x.coerceIn(r, maxOf(r, layout.width - r))
            stickBaseY = y.coerceIn(r, maxOf(r, layout.height - r))
            updateStick(x, y, follow = false)
        }
    }

    fun move(pointerId: Long, x: Float, y: Float) {
        if (stickActive && pointerId == stickPointer) {
            updateStick(x, y)
            return
        }
        for (i in buttonPointer.indices) {
            if (buttonPointer[i] != pointerId) continue
            val spot = spotFor(Btn.entries[i])
            val limit = layout.touchRadius * SLIDE_OFF
            if (spot == null || dist2(x - spot.cx, y - spot.cy) > limit * limit) {
                buttonPointer[i] = NONE
                syncButtons()
            }
        }
    }

    private fun spotFor(btn: Btn): ButtonSpot? {
        val spots = layout.buttons(hasPuck)
        for (i in spots.indices) if (spots[i].btn == btn) return spots[i]
        return null
    }

    fun up(pointerId: Long) {
        if (stickActive && pointerId == stickPointer) releaseStick()
        var changed = false
        for (i in buttonPointer.indices) {
            if (buttonPointer[i] == pointerId) {
                buttonPointer[i] = NONE
                changed = true
            }
        }
        if (changed) syncButtons()
    }

    /** Pointer cancel, pause, backgrounding. */
    fun cancelAll() {
        releaseStick()
        releaseButtons()
    }

    private fun owns(pointerId: Long): Boolean =
        (stickActive && stickPointer == pointerId) || buttonPointer.any { it == pointerId }

    private fun buttonAt(x: Float, y: Float): ButtonSpot? {
        val tr = layout.touchRadius
        var best: ButtonSpot? = null
        var bestD = tr * tr
        val spots = layout.buttons(hasPuck)
        for (i in spots.indices) {
            val spot = spots[i]
            val d = dist2(x - spot.cx, y - spot.cy)
            if (d <= bestD) {
                best = spot
                bestD = d
            }
        }
        return best
    }

    private fun updateStick(x: Float, y: Float, follow: Boolean = true) {
        val r = layout.stickRadius
        var dx = x - stickBaseX
        var dy = y - stickBaseY
        val len = sqrt(dist2(dx, dy))
        if (len > r && follow) {
            val k = (len - r) / len
            stickBaseX += dx * k
            stickBaseY += dy * k
            dx = x - stickBaseX
            dy = y - stickBaseY
        }
        stickKnobX = stickBaseX + dx
        stickKnobY = stickBaseY + dy
        val mx = dx / r
        val my = dy / r
        // On touch-down the base may be clamped away from the finger; the vector is capped below.
        val mLen = sqrt(mx * mx + my * my)
        if (mLen < DEAD_ZONE) {
            input.moveX = 0f; input.moveY = 0f
        } else {
            val s = if (mLen > 1f) 1f / mLen else 1f
            input.moveX = mx * s
            input.moveY = -my * s   // screen y grows downward; rink +y is up-screen
        }
    }

    private fun releaseStick() {
        stickActive = false
        stickPointer = NONE
        stickBaseX = layout.stickRestX; stickBaseY = layout.stickRestY
        stickKnobX = stickBaseX; stickKnobY = stickBaseY
        input.moveX = 0f; input.moveY = 0f
    }

    private fun releaseButtons() {
        buttonPointer.fill(NONE)
        syncButtons()
    }

    private fun syncButtons() {
        input.shoot = isDown(Btn.SHOOT)
        input.pass = isDown(Btn.PASS)
        input.deke = isDown(Btn.DEKE)
        input.poke = isDown(Btn.POKE)
        input.body = isDown(Btn.BODY)
    }

    private fun dist2(dx: Float, dy: Float) = dx * dx + dy * dy

    private companion object {
        const val NONE = Long.MIN_VALUE
        const val DEAD_ZONE = 0.12f
        const val SLIDE_OFF = 1.5f
    }
}
