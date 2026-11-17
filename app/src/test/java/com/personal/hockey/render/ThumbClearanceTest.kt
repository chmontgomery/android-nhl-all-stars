package com.personal.hockey.render

import com.personal.hockey.input.ControlsLayout
import com.personal.hockey.sim.Rink
import com.personal.hockey.sim.Tuning
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On the owner's phone (2424 × 1080 px, 420 dpi) a skater with the puck in a near corner must not
 * sit under the thumb on the resting stick (left end) or under the action buttons (right end),
 * once the camera has followed him there.
 */
class ThumbClearanceTest {
    private val w = 2424f
    private val h = 1080f
    private val density = 420f / 160f
    private val layout = ControlsLayout(w, h, density)
    private val out = FloatArray(3)

    /** Spots in the near-left corner: on and just off the corner boards, below the faceoff dot. */
    private fun nearLeftCorner(): List<Pair<Float, Float>> {
        val cx = -(Rink.HALF_L - Rink.CORNER_R); val cy = -(Rink.HALF_W - Rink.CORNER_R)
        val spots = ArrayList<Pair<Float, Float>>()
        for (deg in 180..270 step 10) for (r in floatArrayOf(5.5f, 6.5f, 7.5f, Rink.CORNER_R - Tuning.SKATER_R)) {
            val a = Math.toRadians(deg.toDouble())
            val x = cx + r * cos(a).toFloat(); val y = cy + r * sin(a).toFloat()
            if (x <= -Rink.GOAL_X + 2f && y <= -6f) spots += x to y
        }
        return spots
    }

    /** The camera after following a still puck at ([x], [y]) for 4 s, as the game does every tick. */
    private fun settledCamera(x: Float, y: Float): Camera {
        val cam = Camera()
        cam.setViewport(w, h)
        repeat(240) { cam.follow(x, y, Tuning.DT) }
        return cam
    }

    /** Screen box of a skater at (x, y): 0.5 m either side of him, ice to the top of the helmet. */
    private fun box(cam: Camera, x: Float, y: Float): FloatArray {
        cam.project(x - 0.5f, y, 0f, out); val l = out[0]; val b = out[1]
        cam.project(x + 0.5f, y, 1.9f, out); val r = out[0]; val t = out[1]
        return floatArrayOf(min(l, r), min(t, b), max(l, r), max(t, b))
    }

    private fun overlaps(box: FloatArray, cx: Float, cy: Float, radius: Float): Boolean {
        val nx = cx.coerceIn(box[0], box[2]); val ny = cy.coerceIn(box[1], box[3])
        val dx = nx - cx; val dy = ny - cy
        return sqrt(dx * dx + dy * dy) < radius
    }

    @Test fun a_skater_in_the_near_left_corner_is_clear_of_the_resting_stick() {
        val spots = nearLeftCorner()
        assertTrue(spots.size >= 10)
        for ((x, y) in spots) {
            val cam = settledCamera(x, y)
            val b = box(cam, x, y)
            assertTrue(
                "skater at $x,$y drawn at ${b.toList()} is under the stick at ${layout.stickRestX},${layout.stickRestY}",
                !overlaps(b, layout.stickRestX, layout.stickRestY, layout.stickRadius),
            )
        }
    }

    @Test fun a_skater_in_the_near_right_corner_is_clear_of_the_buttons() {
        for ((lx, y) in nearLeftCorner()) {
            val x = -lx
            val cam = settledCamera(x, y)
            val b = box(cam, x, y)
            for (hasPuck in listOf(true, false)) for (btn in layout.buttons(hasPuck)) {
                assertTrue(
                    "skater at $x,$y drawn at ${b.toList()} is under ${btn.btn} at ${btn.cx},${btn.cy}",
                    !overlaps(b, btn.cx, btn.cy, btn.radius),
                )
            }
        }
    }
}
