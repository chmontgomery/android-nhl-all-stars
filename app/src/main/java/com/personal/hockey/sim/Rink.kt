package com.personal.hockey.sim

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Rink geometry. The boards are a rounded rectangle: straight sides at |x| = HALF_L and
 * |y| = HALF_W joined by quarter-circle corners of radius CORNER_R.
 */
object Rink {
    const val HALF_L = 30f
    const val HALF_W = 13f
    const val CORNER_R = 8.5f
    const val GOAL_X = 26f
    const val GOAL_HALF_W = 1.1f
    const val NET_DEPTH = 1.1f
    const val BLUE_X = 7.6f
    const val CREASE_R = 1.8f
    /** The end-zone faceoff dots, at (±FACEOFF_X, ±FACEOFF_Y). */
    const val FACEOFF_X = 20f
    const val FACEOFF_Y = 6.7f

    /** Centres of the corner arcs, at (±CORNER_CX, ±CORNER_CY). */
    private const val CORNER_CX = HALF_L - CORNER_R
    private const val CORNER_CY = HALF_W - CORNER_R

    /** True if a circle of radius r at (x, y) is fully inside the boards, corners included. */
    fun inside(x: Float, y: Float, r: Float): Boolean {
        val ax = abs(x); val ay = abs(y)
        if (ax > CORNER_CX && ay > CORNER_CY) {
            val dx = ax - CORNER_CX; val dy = ay - CORNER_CY
            val lim = CORNER_R - r
            return dx * dx + dy * dy <= lim * lim
        }
        return ax <= HALF_L - r && ay <= HALF_W - r
    }

    /** Pushes (x, y) back inside for radius r. Writes the result and the inward unit normal
     *  into out = [x, y, nx, ny]; returns false if it was already inside. */
    fun clamp(x: Float, y: Float, r: Float, out: FloatArray): Boolean {
        val ax = abs(x); val ay = abs(y)
        val sx = if (x < 0f) -1f else 1f
        val sy = if (y < 0f) -1f else 1f
        out[0] = x; out[1] = y; out[2] = 0f; out[3] = 0f
        if (ax > CORNER_CX && ay > CORNER_CY) {
            // Corner region: the only boards that can be hit are the arc.
            val dx = ax - CORNER_CX; val dy = ay - CORNER_CY
            val d = sqrt(dx * dx + dy * dy)
            val lim = CORNER_R - r
            if (d <= lim) return false
            val ux = dx / d; val uy = dy / d
            out[0] = sx * (CORNER_CX + ux * lim)
            out[1] = sy * (CORNER_CY + uy * lim)
            out[2] = -sx * ux
            out[3] = -sy * uy
            return true
        }
        // Outside the corner region only one straight board can be hit.
        if (ax > HALF_L - r) {
            out[0] = sx * (HALF_L - r); out[2] = -sx
            return true
        }
        if (ay > HALF_W - r) {
            out[1] = sy * (HALF_W - r); out[3] = -sy
            return true
        }
        return false
    }
}
