package com.personal.hockey.render

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.hockey.input.Btn
import com.personal.hockey.input.ControlsLayout
import com.personal.hockey.settings.Level
import com.personal.hockey.sim.Act
import com.personal.hockey.sim.HockeySim
import com.personal.hockey.sim.Phase
import com.personal.hockey.sim.Position
import com.personal.hockey.sim.Rink
import com.personal.hockey.sim.Skater
import com.personal.hockey.teams.Crest
import com.personal.hockey.teams.Kit
import com.personal.hockey.teams.MatchSetup
import com.personal.hockey.teams.Player
import com.personal.hockey.teams.Team
import com.personal.hockey.teams.Teams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasRendererTest {
    private val w = 800
    private val h = 360
    private val p = FloatArray(3)

    private fun team(code: String) = Teams.byId(code)!!

    private fun render(r: CanvasRenderer, sim: HockeySim, width: Int = w, height: Int = h, timeS: Float = 1f): ImageBitmap {
        val bmp = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(width.toFloat(), height.toFloat())) {
            r.draw(this, sim, timeS)
        }
        return bmp
    }

    /** Live play, skaters spread in a row at x -15..15 on alternating sides of centre, all standing still facing +x. */
    private fun spreadSim(): HockeySim {
        val sim = HockeySim(Random(1))
        sim.startPlayForTest()
        for (s in sim.skaters) {
            s.x = -15f + 3.3f * s.id; s.y = if (s.id % 2 == 0) -3f else 4f
            s.vx = 0f; s.vy = 0f; s.facing = 0f
        }
        sim.puck.x = 0f; sim.puck.y = 9f; sim.puck.vx = 0f; sim.puck.vy = 0f
        return sim
    }

    /** Points the renderer's camera at x = 0 for a w × h frame; the puck stays at x = 0 so it does not pan. */
    private fun renderer(setup: MatchSetup) = CanvasRenderer(setup).also {
        it.camera.setViewport(w.toFloat(), h.toFloat()); it.camera.snapTo(0f)
    }

    /**
     * Pixels of the skater's torso region (jersey), as projected by the renderer's camera; with
     * [wide], the whole upper body with the sleeves, since the large jersey number covers most of
     * the torso itself.
     */
    private fun torsoPixels(r: CanvasRenderer, pixels: PixelMap, s: Skater, wide: Boolean = false): List<Color> {
        r.camera.project(s.x - if (wide) 0.35f else 0.15f, s.y, if (wide) 0.95f else 1.0f, p)
        val x0 = p[0].toInt(); val y1 = p[1].toInt()
        r.camera.project(s.x + if (wide) 0.6f else 0.4f, s.y, if (wide) 1.55f else 1.4f, p)
        val x1 = p[0].toInt(); val y0 = p[1].toInt()
        val out = ArrayList<Color>()
        for (x in x0..x1) for (y in y0..y1) if (x in 0 until pixels.width && y in 0 until pixels.height) out += pixels[x, y]
        return out
    }

    /** Pixels within [radiusM] metres (projected) of the skater's feet. */
    private fun feetPixels(r: CanvasRenderer, pixels: PixelMap, s: Skater, radiusM: Float): List<Color> {
        r.camera.project(s.x, s.y, 0f, p)
        val cx = p[0]; val cy = p[1]; val rad = radiusM * p[2]
        val out = ArrayList<Color>()
        for (x in (cx - rad).toInt()..(cx + rad).toInt()) for (y in (cy - rad).toInt()..(cy + rad).toInt()) {
            val dx = x - cx; val dy = y - cy
            if (dx * dx + dy * dy <= rad * rad && x in 0 until pixels.width && y in 0 until pixels.height) out += pixels[x, y]
        }
        return out
    }

    private fun near(c: Color, argb: Long, tol: Int = 12): Boolean {
        val t = Color(argb)
        return abs(c.red - t.red) * 255 <= tol && abs(c.green - t.green) * 255 <= tol && abs(c.blue - t.blue) * 255 <= tol
    }

    /**
     * White kit, lit or in shade: light and exactly neutral. Shading keeps a white kit grey; the ice
     * (F4F8FB) is tinted blue, so it does not count.
     */
    private fun shadedWhite(c: Color): Boolean {
        val hi = maxOf(c.red, c.green, c.blue); val lo = minOf(c.red, c.green, c.blue)
        return lo >= 0.7f && (hi - lo) * 255 <= 2.5f
    }
    private fun greenDominant(c: Color) = c.green > 0.55f && c.green > c.red + 0.25f && c.green > c.blue + 0.25f

    @Test fun draws_a_frame_in_every_phase_without_crashing() {
        val r = renderer(MatchSetup.of(team("MIN"), team("COL"), Level.EASY))
        val sim = spreadSim()
        for (phase in Phase.entries) {
            sim.phase = phase
            render(r, sim)
        }
        sim.goalies[1].holding = true
        render(r, sim)
    }

    @Test fun draws_every_act_pose_without_crashing() {
        val r = renderer(MatchSetup.of(team("MIN"), team("COL"), Level.EASY))
        val sim = spreadSim()
        val s = sim.skaters[0]
        for (act in Act.entries) for (facing in floatArrayOf(0f, PI.toFloat())) for (moving in listOf(false, true)) {
            s.act = act; s.facing = facing; s.windup = 0.5f
            s.vx = if (moving) 6f else 0f; s.vy = if (moving) 2f else 0f; s.stride += 0.7f
            render(r, sim)
        }
        sim.giveTo(s)
        sim.goalies[0].saveFlash = 0.1f
        render(r, sim)
    }

    @Test fun home_jersey_colour_appears_near_a_home_skater() {
        val r = renderer(MatchSetup.of(team("MIN"), team("COL"), Level.EASY))
        val sim = spreadSim()
        val s = sim.skaters[4]   // x = -1.8
        val pixels = torsoPixels(r, render(r, sim).toPixelMap(), s)
        assertTrue("no MIN green in ${pixels.size} torso pixels", pixels.any { near(it, 0xFF154734) })
    }

    @Test fun away_wears_white_even_when_both_teams_are_blue() {
        // Kit colours only: without numbers, which cover much of the torso.
        val home = team("EDM").copy(skaters = emptyList())
        val r = renderer(MatchSetup.of(home, team("TOR").copy(skaters = emptyList()), Level.EASY))
        val sim = spreadSim()
        val bmp = render(r, sim).toPixelMap()
        val homePx = torsoPixels(r, bmp, sim.skaters[4])
        val awayPx = torsoPixels(r, bmp, sim.skaters[5])
        assertTrue("away torso has only ${awayPx.count { shadedWhite(it) }} white of ${awayPx.size}", awayPx.count { shadedWhite(it) } > awayPx.size / 4)
        val homeWhite = homePx.count { shadedWhite(it) }
        val homePrimary = homePx.count { near(it, home.primary) }
        assertTrue("home white $homeWhite vs primary $homePrimary", homePrimary > homeWhite)
    }

    @Test fun a_custom_kit_and_roster_are_used() {
        val magenta = 0xFFFF00FF
        val roster = Teams.standardSkaters().map { if (it.position == Position.C) Player(99, Position.C) else it }
        val custom = Team(
            id = "TST", name = "Test Squad", nickname = "Squad", code = "TST",
            primary = magenta, secondary = 0xFF000000, trim = 0xFFFFFFFF, crest = Crest.DROP,
            kits = listOf(Kit("pink", "Pink", magenta, 0xFF000000, 0xFF202020, 0xFF202020, 0xFFFFFFFF)),
            skaters = roster, goalieNumber = 1,
        )
        val setup = MatchSetup(team("MIN"), custom, team("MIN").darkKit, custom.kits[0], Level.EASY)
        val r = renderer(setup)
        val sim = spreadSim()
        val c = sim.skaters[5]
        assertEquals(Position.C, c.pos)
        assertEquals(99, r.numberFor(c))
        val pixels = torsoPixels(r, render(r, sim).toPixelMap(), c, wide = true)
        assertTrue("no magenta at the custom team's centre", pixels.any { near(it, magenta, 30) })   // the smaller torso at 1.6x samples fewer pixels, so shading can miss the exact kit colour
    }

    @Test fun a_roster_missing_a_position_draws_no_number_for_it() {
        val roster = Teams.standardSkaters().filter { it.position != Position.C }
        val short = team("COL").copy(skaters = roster)
        val r = renderer(MatchSetup(team("MIN"), short, team("MIN").darkKit, short.kits[1], Level.EASY))
        assertEquals(CanvasRenderer.NO_NUMBER, r.numberFor(spreadSim().skaters[5]))
        render(r, spreadSim())   // and the frame still draws
    }

    @Test fun a_carried_pucks_streak_is_recorded_on_the_ice() {
        val sim = carrierWithTargetAt(0f)
        val r = renderer(MatchSetup.of(team("MIN"), team("COL"), Level.EASY))
        r.anim.flight.height = 0.8f   // stale flight height while the puck is carried
        sim.puck.vx = 30f
        r.onTick(sim)
        assertTrue(r.effects.streakLength > 0)
        assertEquals(0f, r.effects.newestStreakZ, 0f)
    }

    @Test fun control_ring_is_under_the_controlled_skater() {
        val r = renderer(MatchSetup.of(team("COL"), team("EDM"), Level.EASY))
        val sim = spreadSim()
        sim.controlledId = 4
        val bmp = render(r, sim).toPixelMap()
        assertTrue("no ring under the controlled skater", feetPixels(r, bmp, sim.skaters[4], 1f).any { greenDominant(it) })
        assertTrue("ring under another skater", feetPixels(r, bmp, sim.skaters[6], 1f).none { greenDominant(it) })
        assertTrue("ring under another skater", feetPixels(r, bmp, sim.skaters[2], 1f).none { greenDominant(it) })
    }

    private fun luminance(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

    @Test fun pass_target_marker_stands_out_on_the_ice_even_with_white_trim() {
        val home = team("MIN")
        val whiteTrim = home.darkKit.copy(trim = 0xFFFFFFFF)
        val r = renderer(MatchSetup(home, team("COL"), whiteTrim, team("COL").whiteKit, Level.EASY))
        val sim = spreadSim()
        sim.controlledId = 4
        val target = sim.passTargetFor(sim.skaters[4].also { sim.giveTo(it) })!!
        val withRing = feetPixels(r, render(r, sim).toPixelMap(), target, 0.8f)
        sim.controlledId = 0   // the carrier is not the human's skater: no pass marker
        val without = feetPixels(r, render(r, sim).toPixelMap(), target, 0.8f)
        val iceLum = luminance(Color(0xFFF4F8FB))
        var contrasting = 0
        for (i in withRing.indices) {
            if (withRing[i] != without[i] && abs(luminance(withRing[i]) - iceLum) >= 0.4f) contrasting++
        }
        assertTrue("only $contrasting marker pixels contrast with the ice", contrasting >= 30)
    }

    private fun yellowCount(r: CanvasRenderer, sim: HockeySim, s: Skater): Int =
        feetPixels(r, render(r, sim).toPixelMap(), s, 1f).count { near(it, 0xFFFFD21F, 20) }

    @Test fun yellow_ring_under_another_carrier() {
        val r = renderer(MatchSetup.of(team("MIN"), team("COL"), Level.EASY))
        for (carrierId in intArrayOf(2, 6)) {   // a teammate, then an opponent of the controlled skater 4
            val sim = spreadSim()
            sim.controlledId = 4
            val c = sim.skaters[carrierId]
            sim.giveTo(c)
            assertTrue("no yellow ring under carrier $carrierId", yellowCount(r, sim, c) >= 15)
            sim.puck.carrier = null; sim.puck.x = 0f; sim.puck.y = 9f
            assertTrue("yellow ring under a non-carrier", yellowCount(r, sim, c) < 5)
        }
        val sim = spreadSim()
        sim.controlledId = 4
        sim.giveTo(sim.skaters[4])
        sim.passTargetFor(sim.skaters[4])
        assertTrue("yellow ring under the controlled carrier", yellowCount(r, sim, sim.skaters[4]) < 5)
    }

    @Test fun no_marker_above_the_controlled_player() {
        val r = renderer(MatchSetup.of(team("MIN"), team("COL"), Level.EASY))
        val sim = spreadSim()
        sim.controlledId = 4
        val s = sim.skaters[4]
        for (o in sim.skaters) if (o !== s) { o.x = 40f + o.id; o.y = 8f }   // nobody near: no pass ring over his head
        s.x = -8f; s.y = -6f
        sim.giveTo(s)
        val carrying = render(r, sim).toPixelMap()
        for (z in floatArrayOf(2.0f, 2.2f, 2.4f, 2.6f)) {
            r.camera.project(s.x, s.y, z, p)
            for (dx in -5..5) for (dy in -3..3) {
                val x = p[0].toInt() + dx; val y = p[1].toInt() + dy
                val c = carrying[x, y]   // ice and its red and blue markings only: no white fill, no dark outline
                assertTrue("pixel ($dx, $dy) at $z m above the controlled skater is $c", minOf(c.red, c.green, c.blue) < 0.99f && maxOf(c.red, c.green, c.blue) > 0.25f)
            }
        }
    }

    /** Skater 4 carries and is controlled; its pass target is moved to x = [targetX], y = 0. */
    private fun carrierWithTargetAt(targetX: Float): HockeySim {
        val sim = spreadSim()
        sim.controlledId = 4
        sim.giveTo(sim.skaters[4])
        sim.skaters[4].x = 0f; sim.skaters[4].y = 0f
        // Every teammate goes to the target spot, so the pass target is there whichever one is chosen
        // (at 1.6x the nearer teammates, around x = -8, are still on screen).
        for (m in sim.skaters) if (m.side == sim.skaters[4].side && m !== sim.skaters[4]) { m.x = targetX; m.y = 0f }
        val target = sim.passTargetFor(sim.skaters[4])!!
        target.x = targetX; target.y = 0f
        return sim
    }

    @Test fun no_arrow_when_the_pass_target_is_on_screen() {
        val r = renderer(MatchSetup.of(team("MIN"), team("COL"), Level.EASY))
        val sim = carrierWithTargetAt(3f)
        assertTrue(!r.edgeArrowFor(sim, FloatArray(3)))
    }

    @Test fun an_arrow_points_from_the_edge_toward_an_off_screen_target() {
        val r = renderer(MatchSetup.of(team("MIN"), team("COL"), Level.EASY))
        val sim = carrierWithTargetAt(25f)
        val out = FloatArray(3)
        assertTrue(r.edgeArrowFor(sim, out))
        assertTrue("x ${out[0]} not near the right edge", out[0] > w - 60f && out[0] <= w - 24f)
        assertTrue("angle ${out[2]}", kotlin.math.cos(out[2]) > 0.7f)
    }

    @Test fun the_arrow_is_drawn_in_pass_yellow() {
        val r = renderer(MatchSetup.of(team("MIN"), team("COL"), Level.EASY))
        val sim = carrierWithTargetAt(25f)
        val px = render(r, sim).toPixelMap()
        var yellow = 0
        for (x in w - 60 until w) for (y in 0 until h) if (near(px[x, y], 0xFFFFD21F, 20)) yellow++
        assertTrue("only $yellow yellow pixels near the right edge", yellow >= 30)
        sim.controlledId = 0   // no pass marker, no arrow
        val none = render(r, sim).toPixelMap()
        var y2 = 0
        for (x in w - 60 until w) for (y in 0 until h) if (near(none[x, y], 0xFFFFD21F, 20)) y2++
        assertTrue("yellow without a pass target: $y2", y2 < 5)
    }

    @Test fun the_whole_arrow_with_its_outline_stays_inside_the_viewport() {
        val bw = 1920; val bh = 1080
        val r = CanvasRenderer(MatchSetup.of(team("MIN"), team("COL"), Level.EASY))
        r.camera.setViewport(bw.toFloat(), bh.toFloat()); r.camera.snapTo(0f)
        val pts = FloatArray(6)
        for ((tx, ty) in listOf(25f to 0f, -25f to 0f, 3f to 40f, 3f to -14f, 25f to 40f)) {
            val sim = carrierWithTargetAt(tx)
            val target = sim.passTargetFor(sim.skaters[4])!!
            target.y = ty
            for (m in sim.skaters) if (m.side == target.side && m !== sim.skaters[4]) m.y = ty
            assertTrue("no arrow for ($tx, $ty)", r.edgeArrowCorners(sim, pts))
            for (i in 0 until 3) {
                assertTrue("x ${pts[2 * i]} for ($tx, $ty)", pts[2 * i] in 0f..bw.toFloat())
                assertTrue("y ${pts[2 * i + 1]} for ($tx, $ty)", pts[2 * i + 1] in 0f..bh.toFloat())
            }
        }
    }

    /** Whether the triangle [pts] (3 x, y pairs) overlaps the circle at ([cx], [cy]) of radius [r]. */
    private fun hits(pts: FloatArray, cx: Float, cy: Float, r: Float): Boolean {
        var inside = true; var sign = 0f
        for (i in 0 until 3) {
            val ax = pts[2 * i]; val ay = pts[2 * i + 1]
            val bx = pts[(2 * i + 2) % 6]; val by = pts[(2 * i + 3) % 6]
            val cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            if (sign == 0f) sign = cross else if (cross * sign < 0f) inside = false
            val ex = bx - ax; val ey = by - ay
            val t = (((cx - ax) * ex + (cy - ay) * ey) / (ex * ex + ey * ey)).coerceIn(0f, 1f)
            val dx = ax + t * ex - cx; val dy = ay + t * ey - cy
            if (dx * dx + dy * dy < r * r) return true
        }
        return inside
    }

    /** On the owner's phone (2424 × 1080 px, 420 dpi), with the renderer told where the controls are. */
    private fun phoneRenderer(): Pair<CanvasRenderer, ControlsLayout> {
        val bw = 2424f; val bh = 1080f
        val layout = ControlsLayout(bw, bh, 420f / 160f)
        val r = CanvasRenderer(MatchSetup.of(team("MIN"), team("COL"), Level.EASY))
        r.camera.setViewport(bw, bh)
        r.avoidControls(layout)
        return r to layout
    }

    /**
     * Skater 4 carries at ([cx], [cy]) with the camera settled on him, and is controlled; his
     * teammates are all at ([tx], [ty]) and his stick points there, so his pass target is there.
     */
    private fun carrierAt(r: CanvasRenderer, cx: Float, cy: Float, tx: Float, ty: Float): HockeySim {
        val sim = carrierWithTargetAt(0f)
        val me = sim.skaters[4]
        me.x = cx; me.y = cy
        for (m in sim.skaters) if (m.side == me.side && m !== me) { m.x = tx; m.y = ty }
        val dx = tx - cx; val dy = ty - cy
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        me.input.moveX = dx / len; me.input.moveY = dy / len
        r.camera.snapTo(cx, cy)
        return sim
    }

    @Test fun an_arrow_to_a_target_ahead_keeps_clear_of_the_shoot_button() {
        val (r, layout) = phoneRenderer()
        val pts = FloatArray(6)
        assertTrue(r.edgeArrowCorners(carrierAt(r, 0f, 0f, 25f, 0f), pts))
        val shoot = layout.buttons(hasPuck = true).first { it.btn == Btn.SHOOT }
        assertTrue("arrow ${pts.toList()} over SHOOT at ${shoot.cx}, ${shoot.cy}", !hits(pts, shoot.cx, shoot.cy, shoot.radius))
    }

    @Test fun an_arrow_to_a_target_low_and_behind_keeps_clear_of_the_stick() {
        val (r, layout) = phoneRenderer()
        val pts = FloatArray(6)
        assertTrue(r.edgeArrowCorners(carrierAt(r, 0f, -10f, -12f, -12f), pts))
        assertTrue("arrow ${pts.toList()} over the stick at ${layout.stickRestX}, ${layout.stickRestY}",
            !hits(pts, layout.stickRestX, layout.stickRestY, layout.stickRadius))
    }

    @Test fun an_arrow_in_any_direction_keeps_clear_of_every_control_and_inside_the_viewport() {
        val (r, layout) = phoneRenderer()
        var checked = 0
        val pts = FloatArray(6)
        for (cy in floatArrayOf(-10f, 0f, 10f)) for (dist in floatArrayOf(12f, 20f, 30f)) for (deg in 0 until 360 step 10) {
            val a = Math.toRadians(deg.toDouble())
            val tx = dist * kotlin.math.cos(a).toFloat(); val ty = cy + dist * kotlin.math.sin(a).toFloat()
            if (!r.edgeArrowCorners(carrierAt(r, 0f, cy, tx, ty), pts)) continue   // on screen
            checked++
            val at = "carrier at y $cy, target $dist m at $deg°"
            assertTrue("$at: arrow over the stick", !hits(pts, layout.stickRestX, layout.stickRestY, layout.stickRadius))
            for (b in layout.buttons(hasPuck = true)) assertTrue("$at: arrow over ${b.btn}", !hits(pts, b.cx, b.cy, b.radius))
            for (i in 0 until 3) {
                assertTrue("$at: x ${pts[2 * i]}", pts[2 * i] in 0f..layout.width)
                assertTrue("$at: y ${pts[2 * i + 1]}", pts[2 * i + 1] in 0f..layout.height)
            }
        }
        assertTrue("only $checked arrows checked", checked > 100)
    }

    @Test fun an_arrow_in_any_direction_stays_inside_the_viewport_with_no_controls() {
        val bw = 2424f; val bh = 1080f
        val r = CanvasRenderer(MatchSetup.of(team("MIN"), team("COL"), Level.EASY))
        r.camera.setViewport(bw, bh)   // no avoidControls: nothing to slide clear of
        val pts = FloatArray(6)
        for (cy in floatArrayOf(-10f, 0f, 10f)) for (dist in floatArrayOf(12f, 20f, 30f)) for (deg in 0 until 360 step 5) {
            val a = Math.toRadians(deg.toDouble())
            val tx = dist * kotlin.math.cos(a).toFloat(); val ty = cy + dist * kotlin.math.sin(a).toFloat()
            if (!r.edgeArrowCorners(carrierAt(r, 0f, cy, tx, ty), pts)) continue
            for (i in 0 until 3) {
                assertTrue("y $cy, $dist m at $deg: x ${pts[2 * i]}", pts[2 * i] in 0f..bw)
                assertTrue("y $cy, $dist m at $deg: y ${pts[2 * i + 1]}", pts[2 * i + 1] in 0f..bh)
            }
        }
    }

    @Test fun a_puck_behind_the_near_boards_stays_crisp() {
        val r = renderer(MatchSetup.of(team("MIN"), team("COL"), Level.EASY))
        val sim = spreadSim()
        for (s in sim.skaters) { s.x = 40f + s.id; s.y = 8f }   // nobody near
        sim.puck.carrier = null; sim.puck.x = 0f; sim.puck.y = -(Rink.HALF_W - 0.6f); sim.puck.vx = 0f; sim.puck.vy = 0f
        r.camera.snapTo(0f, sim.puck.y)
        val px = render(r, sim).toPixelMap()
        r.camera.project(sim.puck.x, sim.puck.y, 0f, p)
        // The middle of the puck's top face, a puck thickness above its centre on the ice.
        val top = px[p[0].toInt(), (p[1] - FigurePainter.PUCK_THICK * p[2]).toInt()]
        assertTrue("puck top ${luminance(top)} under the near boards' wash", luminance(top) < 0.2f)
    }

    @Test fun drawing_does_not_move_the_camera() {
        val r = renderer(MatchSetup.of(team("MIN"), team("COL"), Level.EASY))
        val sim = spreadSim()
        sim.puck.x = 20f; sim.puck.vx = 10f; sim.puck.y = 8f
        render(r, sim)
        render(r, sim)
        assertEquals(0f, r.camera.focusX, 0f)
        assertEquals(0f, r.camera.focusY, 0f)
        r.advance(sim, 1f / 60f)
        assertTrue("advance should pan toward the puck: ${r.camera.focusX}", r.camera.focusX > 0f)
        assertTrue("advance should follow the puck in y: ${r.camera.focusY}", r.camera.focusY > 0f)
    }

    @Test fun advancing_the_same_sim_time_in_different_steps_gives_the_same_camera() {
        val a = renderer(MatchSetup.of(team("MIN"), team("COL"), Level.EASY))
        val b = renderer(MatchSetup.of(team("MIN"), team("COL"), Level.EASY))
        val sim = spreadSim()
        sim.puck.x = 20f
        repeat(30) { a.advance(sim, 1f / 60f) }
        // 120 Hz: half the frames run a tick, the rest run none and do not call advance.
        repeat(60) { k -> if (k % 2 == 1) b.advance(sim, 1f / 60f) }
        assertEquals(a.camera.focusX, b.camera.focusX, 1e-5f)
        assertEquals(a.camera.focusY, b.camera.focusY, 1e-5f)
        assertTrue(a.camera.focusX > 1f)
    }

    @Test fun writes_a_preview_frame() {
        val pw = 1600; val ph = 720
        val r = CanvasRenderer(MatchSetup.of(team("MIN"), team("COL"), Level.EASY))
        val sim = HockeySim(Random(1))
        sim.startPlayForTest()
        // x, y, facing (deg), vx, vy, stride, act
        val spots = arrayOf(
            floatArrayOf(15.5f, -1.5f, 15f, 6.5f, 1.7f, 0.3f),   // HOME C, the carrier
            floatArrayOf(18f, 6.5f, -10f, 5f, -0.9f, 1.1f),
            floatArrayOf(20.5f, -7f, 20f, 4f, 1.5f, 1.9f),
            floatArrayOf(9.5f, 4f, 0f, 2f, 0f, 0.6f),
            floatArrayOf(10.5f, -6.5f, 10f, 2.5f, 0.4f, 1.4f),
            floatArrayOf(18.5f, 0.5f, 195f, -4f, -1f, 0.9f),     // AWAY C
            floatArrayOf(14f, 5f, 220f, -3f, -2.5f, 0.2f),
            floatArrayOf(12.5f, -4.5f, 160f, -4f, 1.4f, 1.6f),
            floatArrayOf(22.5f, 3f, 200f, -1f, -0.4f, 0.8f),
            floatArrayOf(22f, -3.5f, 170f, -1.5f, 0.3f, 2.1f),
        )
        for (s in sim.skaters) {
            val v = spots[s.id]
            s.x = v[0]; s.y = v[1]; s.facing = (v[2] * PI / 180.0).toFloat(); s.vx = v[3]; s.vy = v[4]; s.stride = v[5]
        }
        sim.skaters[7].act = Act.POKE
        sim.goalies[1].x = 25f; sim.goalies[1].y = -0.3f
        sim.controlledId = 0
        sim.giveTo(sim.skaters[0])
        r.camera.setViewport(pw.toFloat(), ph.toFloat())
        r.camera.snapTo(sim.puck.x + 0.15f * sim.puck.vx, sim.puck.y + 0.15f * sim.puck.vy)
        val bmp = render(r, sim, pw, ph, timeS = 0.4f)
        val file = File("build/preview/frame.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bmp.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("preview ${file.absolutePath} ${file.length()} bytes", file.exists() && file.length() > 10_000)
    }
}
