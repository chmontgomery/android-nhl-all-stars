package com.personal.hockey.input

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsTest {
    private fun layout(w: Float = 2424f, h: Float = 1080f, d: Float = 2.625f,
                       l: Float = 0f, t: Float = 0f, r: Float = 0f, b: Float = 0f) =
        ControlsLayout(w, h, d, l, t, r, b)

    private fun controls() = Controls(layout()).also { it.hasPuck = true }
    private fun Controls.spot(btn: Btn) = layout.buttons(hasPuck).first { it.btn == btn }
    private val Controls.stickLen get() = hypot(input.moveX, input.moveY)

    @Test fun stick_appears_where_the_thumb_lands_and_reports_direction() {
        val c = controls()
        c.down(1, 300f, 700f)
        assertTrue(c.stickActive)
        assertEquals(300f, c.stickBaseX, 0.01f)
        c.move(1, 300f + 147f, 700f)
        assertEquals(1f, c.input.moveX, 0.01f)
        assertEquals(0f, c.input.moveY, 0.01f)
    }

    @Test fun up_on_screen_is_positive_rink_y() {
        val c = controls()
        c.down(1, 300f, 700f)
        c.move(1, 300f, 700f - 147f)
        assertTrue(c.input.moveY > 0.9f)
    }

    @Test fun dead_zone_reports_zero() {
        val c = controls()
        c.down(1, 300f, 700f)
        c.move(1, 310f, 705f)
        assertEquals(0f, c.input.moveX, 0f)
        assertEquals(0f, c.input.moveY, 0f)
    }

    @Test fun stick_vector_is_clamped_and_base_follows() {
        val c = controls()
        c.down(1, 300f, 700f)
        c.move(1, 700f, 700f)
        assertEquals(1f, c.stickLen, 0.001f)
        c.move(1, 600f, 700f)
        assertTrue(c.input.moveX > 0f)
        assertTrue(c.stickLen < 1f)
    }

    @Test fun stick_base_stays_off_the_screen_edge() {
        val c = controls()
        c.down(1, 5f, 5f)
        assertEquals(c.layout.stickRadius, c.stickBaseX, 0.01f)
        assertEquals(c.layout.stickRadius, c.stickBaseY, 0.01f)
    }

    @Test fun stick_and_button_work_together() {
        val c = controls()
        c.down(1, 300f, 700f)
        c.move(1, 400f, 700f)
        val s = c.spot(Btn.SHOOT)
        c.down(2, s.cx, s.cy)
        assertTrue(c.input.shoot)
        assertTrue(c.input.moveX != 0f)
    }

    @Test fun releasing_stick_zeros_it_but_keeps_button() {
        val c = controls()
        c.down(1, 300f, 700f)
        c.move(1, 400f, 700f)
        val s = c.spot(Btn.SHOOT)
        c.down(2, s.cx, s.cy)
        c.up(1)
        assertFalse(c.stickActive)
        assertEquals(0f, c.input.moveX, 0f)
        assertEquals(0f, c.input.moveY, 0f)
        assertTrue(c.input.shoot)
        assertTrue(c.isDown(Btn.SHOOT))
    }

    @Test fun button_releases_when_finger_slides_off() {
        val c = controls()
        val s = c.spot(Btn.PASS)
        c.down(1, s.cx, s.cy)
        assertTrue(c.input.pass)
        c.move(1, s.cx + c.layout.touchRadius * 1.4f, s.cy)
        assertTrue(c.input.pass)
        c.move(1, s.cx + c.layout.touchRadius * 1.6f, s.cy)
        assertFalse(c.input.pass)
    }

    @Test fun offence_and_defence_show_different_buttons() {
        val l = layout()
        assertEquals(listOf(Btn.SHOOT, Btn.PASS, Btn.DEKE), l.buttons(true).map { it.btn })
        assertEquals(listOf(Btn.POKE, Btn.BODY), l.buttons(false).map { it.btn })
        val pass = l.buttons(true).first { it.btn == Btn.PASS }
        val poke = l.buttons(false).first { it.btn == Btn.POKE }
        assertEquals(pass.cx, poke.cx, 0f)
        assertEquals(pass.cy, poke.cy, 0f)
    }

    @Test fun swapping_mode_releases_held_buttons() {
        val c = controls()
        val s = c.spot(Btn.SHOOT)
        c.down(1, s.cx, s.cy)
        assertTrue(c.input.shoot)
        c.hasPuck = false
        assertFalse(c.input.shoot)
        assertFalse(c.input.poke)
        // The finger is still down but must not trigger anything until re-pressed.
        c.move(1, s.cx, s.cy)
        assertFalse(c.input.shoot)
        val p = c.spot(Btn.POKE)
        c.down(2, p.cx, p.cy)
        assertTrue(c.input.poke)
    }

    @Test fun cancel_all_clears_everything() {
        val c = controls()
        c.down(1, 300f, 700f)
        c.move(1, 400f, 600f)
        val s = c.spot(Btn.SHOOT)
        c.down(2, s.cx, s.cy)
        c.cancelAll()
        assertFalse(c.stickActive)
        assertAllClear(c)
    }

    @Test fun second_thumb_in_stick_zone_is_ignored() {
        val c = controls()
        c.down(1, 300f, 700f)
        c.move(1, 400f, 700f)
        val x = c.input.moveX
        c.down(2, 800f, 400f)
        c.move(2, 900f, 300f)
        assertEquals(300f, c.stickBaseX, 0.01f)
        assertEquals(x, c.input.moveX, 0f)
        c.up(2)
        assertTrue(c.stickActive)
        assertEquals(x, c.input.moveX, 0f)
    }

    @Test fun ten_pointers_mashing_never_sticks() {
        for (hasPuck in listOf(true, false)) {
            val c = Controls(layout())
            c.hasPuck = hasPuck
            val rnd = Random(42)
            repeat(5000) {
                val id = rnd.nextLong(10)
                val x = rnd.nextFloat() * 2424f
                val y = rnd.nextFloat() * 1080f
                when (rnd.nextInt(3)) {
                    0 -> c.down(id, x, y)
                    1 -> c.move(id, x, y)
                    else -> c.up(id)
                }
            }
            for (id in 0L until 10L) c.up(id)
            assertAllClear(c)
            assertFalse(c.stickActive)
        }
    }

    @Test fun stick_keeps_reporting_through_a_has_puck_swap_while_a_button_is_held() {
        val c = controls()
        c.down(1, 300f, 700f)
        c.move(1, 300f + 147f, 700f)
        val s = c.spot(Btn.SHOOT)
        c.down(2, s.cx, s.cy)
        assertTrue(c.input.shoot)
        c.hasPuck = false
        assertFalse(c.input.shoot)
        assertTrue(c.stickActive)
        assertEquals(1f, c.input.moveX, 0.01f)
        c.move(1, 300f + 147f, 700f - 100f)
        assertTrue(c.input.moveY > 0.3f)
    }

    @Test fun buttons_respect_insets() {
        val r = layout(r = 120f, b = 90f)
        for (hasPuck in listOf(true, false)) {
            for (s in r.buttons(hasPuck)) {
                assertTrue(s.cx + s.radius <= 2424f - 120f)
                assertTrue(s.cy + s.radius <= 1080f - 90f)
            }
        }
        val l = layout(l = 120f)
        assertTrue(l.stickRestX - l.stickRadius >= 120f)
    }

    /** Clear of rounded screen corners: centres measured from the safe edges, in dp. */
    @Test fun buttons_sit_clear_of_the_corners() {
        val d = 2.625f
        val l = layout(d = d, r = 120f, b = 90f)
        val right = 2424f - 120f; val bottom = 1080f - 90f
        fun dpFromRight(x: Float) = (right - x) / d
        fun dpFromBottom(y: Float) = (bottom - y) / d
        val off = l.buttons(true).associateBy { it.btn }
        val def = l.buttons(false).associateBy { it.btn }
        // Slot A (left of the bottom row), slot B (right of the bottom row), slot C (above B).
        for (a in listOf(off.getValue(Btn.PASS), def.getValue(Btn.POKE))) {
            assertEquals(162f, dpFromRight(a.cx), 0.01f); assertEquals(72f, dpFromBottom(a.cy), 0.01f)
        }
        for (b in listOf(off.getValue(Btn.DEKE), def.getValue(Btn.BODY))) {
            assertEquals(68f, dpFromRight(b.cx), 0.01f); assertEquals(72f, dpFromBottom(b.cy), 0.01f)
        }
        val c = off.getValue(Btn.SHOOT)
        assertEquals(68f, dpFromRight(c.cx), 0.01f)
        assertEquals(176f, dpFromBottom(c.cy), 0.01f)
    }

    @Test fun back_gesture_exclusion_covers_the_resting_stick_within_the_system_cap() {
        val d = 2.625f
        val l = layout(d = d, b = 42f)
        val (left, top, right, bottom) = l.gestureExclusion.toList()
        assertEquals(0f, left, 0f)
        assertEquals(1080f, bottom, 0f)
        assertTrue(right >= l.stickRestX + l.stickRadius)
        assertTrue(top <= l.stickRestY - l.stickRadius)
        assertTrue("height ${(bottom - top) / d} dp", (bottom - top) / d <= 200f + 0.01f)
    }

    @Test fun buttons_fit_on_a_short_wide_screen() {
        for ((w, h, d) in listOf(Triple(1600f, 720f, 2f), Triple(2400f, 1080f, 2.625f))) {
            val l = layout(w, h, d)
            val all = l.buttons(true) + l.buttons(false)
            for (s in all) {
                assertTrue(s.cx - s.radius >= 0f && s.cx + s.radius <= w)
                assertTrue(s.cy - s.radius >= 0f && s.cy + s.radius <= h)
            }
            for (group in listOf(l.buttons(true), l.buttons(false))) {
                for (i in group.indices) for (j in i + 1 until group.size) {
                    val a = group[i]; val b = group[j]
                    assertTrue(hypot(a.cx - b.cx, a.cy - b.cy) >= a.radius + b.radius)
                }
            }
            assertTrue(l.stickRestX - l.stickRadius >= 0f && l.stickRestY + l.stickRadius <= h)
        }
    }

    private fun assertAllClear(c: Controls) {
        val i = c.input
        assertFalse(i.shoot || i.pass || i.deke || i.poke || i.body)
        assertTrue(abs(i.moveX) == 0f && abs(i.moveY) == 0f)
        for (b in Btn.entries) assertFalse(c.isDown(b))
    }
}
