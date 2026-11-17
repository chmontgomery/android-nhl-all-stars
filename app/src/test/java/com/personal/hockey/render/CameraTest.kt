package com.personal.hockey.render

import com.personal.hockey.sim.Rink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CameraTest {
    private val w = 2400f
    private val h = 1080f
    private val out = FloatArray(3)
    private val cam = Camera().apply { setViewport(w, h) }

    private fun screenX(x: Float, y: Float, z: Float = 0f): Float { cam.project(x, y, z, out); return out[0] }
    private fun screenY(x: Float, y: Float, z: Float = 0f): Float { cam.project(x, y, z, out); return out[1] }
    private fun scale(x: Float, y: Float, z: Float = 0f): Float { cam.project(x, y, z, out); return out[2] }

    @Test fun focus_point_is_horizontally_centred() {
        cam.snapTo(5f)
        assertEquals(w / 2, screenX(cam.focusX, 0f), 1f)
    }

    @Test fun right_is_right_and_far_is_up() {
        assertTrue(screenX(1f, 0f) > screenX(0f, 0f))
        assertTrue(screenY(0f, 1f) < screenY(0f, 0f))
    }

    @Test fun far_things_are_smaller() {
        assertTrue(scale(0f, Rink.HALF_W) < scale(0f, -Rink.HALF_W))
    }

    @Test fun height_moves_up_the_screen() {
        assertTrue(screenY(0f, 0f, 1f) < screenY(0f, 0f, 0f))
    }

    @Test fun shows_about_22_metres_at_mid_ice() {
        cam.snapTo(0f, 0f)
        val span = screenX(cam.focusX + 11f, 0f) - screenX(cam.focusX - 11f, 0f)
        assertEquals(w, span, 200f)
    }

    @Test fun a_skater_is_about_1_6_times_bigger_than_before() {
        cam.snapTo(0f, 0f)
        val ratio = scale(0f, 0f) / (OLD_SCALE_PER_HEIGHT * h)
        assertTrue("ratio $ratio", ratio in 1.55f..1.65f)
    }

    @Test fun the_puck_is_drawn_at_0_15_m() {
        assertEquals(0.15f, FigurePainter.PUCK_DRAW_R, 0f)
    }

    @Test fun follow_tracks_the_puck_in_y_and_clamps() {
        for (side in floatArrayOf(1f, -1f)) {
            cam.snapTo(0f, 0f)
            var last = cam.focusY
            var furthest = 0f
            repeat(240) {
                cam.follow(0f, side * Rink.HALF_W, DT)
                // Eased: each tick moves under 7% of the way (DT / FOLLOW_TAU), never backwards.
                assertTrue("jump from $last to ${cam.focusY}", abs(cam.focusY - last) <= 0.5f)
                assertTrue("went back from $last to ${cam.focusY}", (cam.focusY - last) * side >= 0f)
                last = cam.focusY
                furthest = maxOf(furthest, abs(cam.focusY))
            }
            val settled = cam.focusY
            assertTrue("follows toward the boards: $settled", abs(settled) > 3f)
            assertTrue("clamped short of the boards: $settled", abs(settled) < Rink.HALF_W)
            repeat(240) {
                cam.follow(0f, side * 100f, DT)
                furthest = maxOf(furthest, abs(cam.focusY))
            }
            assertEquals(settled, cam.focusY, 1e-3f)
            assertTrue("past the clamp: $furthest", furthest <= abs(settled) + 1e-3f)
        }
        // At the far clamp the top of the far glass is still at or above the top edge.
        cam.snapTo(0f, Rink.HALF_W)
        assertTrue("glass top at ${screenY(0f, Rink.HALF_W, GLASS_TOP_Z)}", screenY(0f, Rink.HALF_W, GLASS_TOP_Z) <= 0.5f)
    }

    @Test fun zoom_eases_to_its_goal() {
        cam.snapTo(0f, 0f)
        assertEquals(1f, cam.zoom, 0f)
        val unzoomed = scale(0f, 0f)
        cam.zoomGoal = 1.3f
        cam.follow(0f, 0f, DT)
        assertTrue("one tick zoomed to ${cam.zoom}", cam.zoom in 1.001f..1.05f)
        repeat(71) { cam.follow(0f, 0f, DT) }
        assertEquals(1.3f, cam.zoom, 1.3f * 0.05f)
        // Zoom multiplies the focal length.
        assertEquals(unzoomed * cam.zoom, scale(cam.focusX, cam.focusY), 1e-3f)
    }

    /** Review Focus 3: a puck on the far boards stays below the HUD, on the near boards above the bottom. */
    @Test fun a_puck_along_either_board_stays_between_hud_and_bottom_edge() {
        var x = -28f
        while (x <= 28f) {
            for (y in floatArrayOf(Rink.HALF_W - 0.5f, -Rink.HALF_W + 0.5f)) {
                val c = Camera().apply { setViewport(w, h) }
                repeat(240) { c.follow(x, y, DT) }
                c.project(x, y, 0f, out)
                val at = out[1] / h
                assertTrue("puck at $x,$y drawn at $at of the height", at in 0.18f..0.85f)
            }
            x += 4f
        }
    }

    @Test fun follow_is_smooth_and_clamped() {
        cam.snapTo(0f)
        cam.follow(30f, 0f, DT)
        // One tick moves about DT / FOLLOW_TAU (under 7%) of the way to the end framing.
        assertTrue("one tick moved ${cam.focusX}", cam.focusX in 0.5f..1.6f)
        repeat(180) { cam.follow(30f, 0f, DT) }
        // About 10 m either side is visible at mid-ice; the end framing shows 4 m past the boards.
        assertTrue("focus ${cam.focusX}", cam.focusX <= 30f - 10f + 4f + 0.1f)
        assertTrue("focus ${cam.focusX} should still have moved well right", cam.focusX > 15f)
        repeat(360) { cam.follow(-30f, 0f, DT) }
        assertTrue("focus ${cam.focusX}", cam.focusX >= -(30f - 10f + 4f + 0.1f))
    }

    @Test fun project_does_not_allocate_and_is_finite() {
        var f = -30f
        while (f <= 30f) {
            cam.snapTo(f)
            for (sx in floatArrayOf(-Rink.HALF_L, Rink.HALF_L)) for (sy in floatArrayOf(-Rink.HALF_W, Rink.HALF_W)) {
                cam.project(sx, sy, 0f, out)
                assertTrue(out[0].isFinite() && out[1].isFinite() && out[2].isFinite() && out[2] > 0f)
            }
            f += 2.5f
        }
    }

    @Test fun a_zero_viewport_is_ignored_and_the_clamp_survives() {
        cam.setViewport(0f, 0f)
        repeat(600) { cam.follow(100f, 100f, DT) }
        assertTrue("focus ${cam.focusX}", cam.focusX.isFinite() && cam.focusX <= Rink.HALF_L)
        assertTrue("focus y ${cam.focusY}", cam.focusY.isFinite() && cam.focusY <= Rink.HALF_W)
        cam.project(cam.focusX, 0f, 0f, out)
        assertTrue(out[0].isFinite() && out[1].isFinite() && out[2] > 0f)
    }

    /**
     * Through mid-rink the camera centres on its target, up to showing 1.5 m past the end boards at
     * the focus depth; with the target on or behind a goal line it goes on to show 4 m, so corner play
     * clears the thumb and the buttons (ThumbClearanceTest).
     */
    @Test fun end_framing_centres_mid_rink_and_shows_more_arena_at_the_ends() {
        cam.snapTo(10f)
        assertEquals(10f, cam.focusX, 1e-4f)
        cam.snapTo(20f)   // about 11 m either side is visible at 20:9, so 1.5 m past the boards is about 20.6
        assertEquals(20f, cam.focusX, 1e-4f)
        cam.snapTo(Rink.GOAL_X)
        assertEquals(w, screenX(Rink.HALF_L + 4f, 0f), 2f)
        cam.snapTo(100f)
        assertEquals(w, screenX(Rink.HALF_L + 4f, 0f), 2f)
        cam.snapTo(-100f)
        assertEquals(0f, screenX(-Rink.HALF_L - 4f, 0f), 2f)
    }

    @Test fun end_framing_is_continuous_and_never_goes_backwards() {
        var last = -Float.MAX_VALUE
        var x = -32f
        while (x <= 32f) {
            cam.snapTo(x)
            assertTrue("focus ${cam.focusX} at $x after $last", cam.focusX >= last)
            if (last > -Float.MAX_VALUE) assertTrue("jump at $x", cam.focusX - last <= 0.1f + 1e-4f)
            last = cam.focusX
            x += 0.1f
        }
    }

    private companion object {
        const val DT = 1f / 60f
        /** Today's pixels per metre at mid-ice over the height: 2.35 / 38.0104 (focal / DEPTH_AT_MID_ICE). */
        const val OLD_SCALE_PER_HEIGHT = 0.061825f
        /** RinkPainter's far glass top: BOARD_H + GLASS_H. */
        const val GLASS_TOP_Z = 2.67f
    }
}
