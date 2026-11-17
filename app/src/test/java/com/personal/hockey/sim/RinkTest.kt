package com.personal.hockey.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RinkTest {
    @Test fun centre_is_inside() = assertTrue(Rink.inside(0f, 0f, 0.45f))

    @Test fun corner_square_is_outside() = assertFalse(Rink.inside(29.5f, 12.5f, 0.1f))

    @Test fun clamp_pulls_back_from_side_boards() {
        val out = FloatArray(4)
        assertTrue(Rink.clamp(0f, 14f, 0.5f, out))
        assertEquals(12.5f, out[1], 1e-3f)
        assertEquals(-1f, out[3], 1e-3f)
    }

    @Test fun clamp_pulls_back_from_end_boards() {
        val out = FloatArray(4)
        assertTrue(Rink.clamp(-31f, 0f, 0.5f, out))
        assertEquals(-29.5f, out[0], 1e-3f)
        assertEquals(1f, out[2], 1e-3f)
    }

    @Test fun clamp_in_corner_returns_point_inside() {
        val out = FloatArray(4)
        assertTrue(Rink.clamp(29f, 12f, 0.45f, out))
        assertTrue(Rink.inside(out[0], out[1], 0.449f))
        // Inward normal points back toward the corner's centre: -x and -y.
        assertTrue(out[2] < 0f && out[3] < 0f)
        assertEquals(1f, out[2] * out[2] + out[3] * out[3], 1e-3f)
    }

    @Test fun clamp_leaves_inside_point_alone() {
        val out = FloatArray(4)
        assertFalse(Rink.clamp(5f, -3f, 0.45f, out))
        assertEquals(5f, out[0], 0f)
        assertEquals(-3f, out[1], 0f)
    }
}
