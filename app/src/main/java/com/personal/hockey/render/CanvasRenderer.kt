package com.personal.hockey.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.personal.hockey.input.ControlsLayout
import com.personal.hockey.render.anim.AnimDirector
import com.personal.hockey.render.anim.BLEND_S
import com.personal.hockey.render.anim.sweepOffset
import com.personal.hockey.sim.HockeySim
import com.personal.hockey.sim.Phase
import com.personal.hockey.sim.Position
import com.personal.hockey.sim.Rink
import com.personal.hockey.sim.Side
import com.personal.hockey.sim.Skater
import com.personal.hockey.teams.MatchSetup
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The whole frame: rink, a shot in the air's shadow, ground markers, then nets,
 * goalies, skaters and the puck (at its flight height) sorted far to near, then snow and the puck
 * streak, then the near boards (and the puck again over them, when they cover it). Kits and numbers
 * come from [setup]; nothing here names a team.
 */
class CanvasRenderer(setup: MatchSetup, arena: Arena = Arena.ALL_STARS) : GameRenderer {
    val camera = Camera()
    private val rink = RinkPainter(arena)
    internal val figures = FigurePainter()
    private val kits = arrayOf(KitColors(setup.homeKit), KitColors(setup.awayKit))
    /** Index side.index * 5 + position.ordinal; [NO_NUMBER] where the roster lacks that position. */
    private val numbers = IntArray(Side.entries.size * Position.entries.size) { i ->
        val team = if (i < Position.entries.size) setup.home else setup.away
        val pos = Position.entries[i % Position.entries.size]
        team.skaters.firstOrNull { it.position == pos }?.number ?: NO_NUMBER
    }
    private val goalieNumbers = intArrayOf(setup.home.goalieNumber, setup.away.goalieNumber)
    private val order = IntArray(ORDER_SIZE)
    private val keys = FloatArray(ORDER_SIZE)
    private val arrow = FloatArray(3)
    private val proj = FloatArray(3)
    /** World (x, y) the puck is drawn at this frame. */
    private val puckXY = FloatArray(2)
    /**
     * The carried puck's offset (world metres) from the sim's puck, out along the stickhandling sweep,
     * as last drawn; after a release it fades out over [BLEND_S] by [puckOffW] (1 while carried).
     */
    private var puckOffX = 0f; private var puckOffY = 0f; private var puckOffW = 0f
    /** The on-screen controls the edge arrow keeps clear of: x, y and radius in px for each of [thumbCount]. */
    private val thumbs = FloatArray(MAX_THUMBS * 3)
    private var thumbCount = 0
    /** Mesh triangles added for all figures in the last [draw], before culling. */
    internal var figureTriangles = 0
        private set

    /** Snow spray and the puck streak. */
    internal val effects = Effects()

    /** Each figure's animation state, fed by [onTick] and clocked by [advance]; it sprays into [effects]. */
    internal val anim = AnimDirector(effects)

    /**
     * Keeps the off-screen pass arrow clear of [layout]'s resting stick and its buttons with the puck
     * (the arrow only shows while the human's skater carries it). Call when the layout changes.
     */
    fun avoidControls(layout: ControlsLayout) {
        thumbCount = 0
        addThumb(layout.stickRestX, layout.stickRestY, layout.stickRadius)
        val spots = layout.buttons(hasPuck = true)
        for (i in spots.indices) addThumb(spots[i].cx, spots[i].cy, spots[i].radius)
    }

    /** Fades the far-board text away from an on-screen overlay in [slot] ([CLEAR_SCOREBOARD], [CLEAR_PAUSE]). */
    fun keepBoardTextClear(slot: Int, left: Float, top: Float, right: Float, bottom: Float) {
        rink.keepClear(slot, left, top, right, bottom)
    }

    private fun addThumb(x: Float, y: Float, r: Float) {
        if (thumbCount == MAX_THUMBS) return
        thumbs[3 * thumbCount] = x; thumbs[3 * thumbCount + 1] = y; thumbs[3 * thumbCount + 2] = r
        thumbCount++
    }

    /** Hears one sim tick's state and events; called after every sim tick, before the next clears them. */
    fun onTick(sim: HockeySim) {
        anim.onTick(sim)
        val puck = sim.puck
        effects.notePuck(puck.x, puck.y, puckZ(sim), sqrt(puck.vx * puck.vx + puck.vy * puck.vy))
    }

    /** Whether the camera is pushed in on a scorer; it zooms back out when the goal pause ends. */
    private var pushedIn = false

    /**
     * Moves the camera, the animation clocks and the effects by [seconds] of sim time; called once per
     * frame that ran sim ticks. Through the goal pause the camera pushes in on the scorer ([GOAL_ZOOM]);
     * otherwise it follows the puck, leading it a little.
     */
    fun advance(sim: HockeySim, seconds: Float) {
        val sid = sim.lastScorerId
        if (sim.phase.afterGoal && sid >= 0 && sid < sim.skaters.size) {
            val s = sim.skaters[sid]
            camera.zoomGoal = GOAL_ZOOM
            pushedIn = true
            camera.follow(s.x, s.y, seconds)
        } else {
            if (pushedIn) { camera.zoomGoal = 1f; pushedIn = false }
            val puck = sim.puck
            camera.follow(puck.x + LEAD_S * puck.vx, puck.y + LEAD_S * puck.vy, seconds)
        }
        anim.advance(seconds)
        effects.step(seconds)
        val carrier = sim.puck.carrier
        if (carrier != null) {
            carryOffset(carrier)
            puckOffW = 1f
        } else {
            puckOffW = max(0f, puckOffW - seconds / BLEND_S)
        }
    }

    /** Sets [puckOffX], [puckOffY] to [carrier]'s sweep offset: out to his left by [sweepOffset]. */
    private fun carryOffset(carrier: Skater) {
        val b = sweepOffset(anim.skaters[carrier.id], sqrt(carrier.vx * carrier.vx + carrier.vy * carrier.vy))
        puckOffX = -b * sin(carrier.facing); puckOffY = b * cos(carrier.facing)
    }

    /**
     * Fills [out] with the world (x, y) the puck is drawn at: carried, the sim's carry point moved out
     * along the stickhandling sweep onto the blade (as far as the sweep has eased in); loose, the sim's
     * puck, with what was left of that offset at the release fading out, so the puck leaves from where
     * it was drawn.
     */
    internal fun puckDrawXY(sim: HockeySim, out: FloatArray) {
        val puck = sim.puck
        val carrier = puck.carrier
        if (carrier != null) {
            carryOffset(carrier)
            out[0] = puck.x + puckOffX; out[1] = puck.y + puckOffY
        } else {
            out[0] = puck.x + puckOffX * puckOffW; out[1] = puck.y + puckOffY * puckOffW
        }
    }

    /**
     * Height the puck is drawn at this frame: a loose puck's shot flight once it is clearly in the air
     * (over [FigurePainter.PUCK_LIFTED_Z]), else 0, on the ice.
     */
    private fun puckZ(sim: HockeySim): Float {
        val z = anim.flight.height
        return if (z > FigurePainter.PUCK_LIFTED_Z && sim.puck.carrier == null && !sim.puckHeld()) z else 0f
    }

    /** Draws the current state; does not move the camera (see [advance]). */
    override fun draw(scope: DrawScope, sim: HockeySim, timeS: Float) {
        val w = scope.size.width; val h = scope.size.height
        if (w <= 0f || h <= 0f) return
        if (w != camera.width || h != camera.height) camera.setViewport(w, h)
        val puck = sim.puck

        rink.draw(scope, camera)

        // No ground shadows under the figures (they only cluttered the ice); the puck keeps its own.
        val skaters = sim.skaters
        // Carried, the puck rides the sweeping blade; winding up or deking, the sweep eases out and the
        // puck waits at the sim's carry point for the stick to come through.
        puckDrawXY(sim, puckXY)
        val puckZ = puckZ(sim)
        // A shot in the air: its shadow on the ice where it really is, under every figure.
        if (puckZ > 0f) figures.drawPuckShadow(scope, camera, puckXY[0], puckXY[1], puckZ)

        val controlled = if (sim.controlledId in skaters.indices) skaters[sim.controlledId] else null
        val carrier = puck.carrier
        // Fixed colours, not a kit's: a dark outline under a bright ring reads on white ice whatever
        // the teams wear. The pass target's ring (while you carry) and the carrier's (when it is not
        // you) never show together.
        if (controlled != null && carrier === controlled) {
            val target = sim.passTargetFor(carrier)
            if (target != null) drawYellowRing(scope, target.x, target.y)
        } else if (carrier != null) {
            drawYellowRing(scope, carrier.x, carrier.y)
        }
        if (controlled != null) {
            figures.drawGroundRing(scope, camera, controlled.x, controlled.y, CONTROL_RING_R, CONTROL_RING_BAND, CONTROL_GREEN, 1f, CONTROL_FILL_ALPHA)
        }

        figureTriangles = 0
        val n = drawOrder(sim, order)
        for (k in 0 until n) {
            val code = order[k]
            when {
                code < GOALIE_MARK -> {
                    val s = skaters[code]
                    figures.drawSkater(scope, camera, s, kits[s.side.index], numberFor(s), anim.skaters[s.id])
                    figureTriangles += figures.lastTriangles
                }
                code < PUCK_MARK -> {
                    val g = sim.goalies[code - GOALIE_MARK]
                    figures.drawGoalie(scope, camera, g, kits[g.side.index], goalieNumbers[g.side.index], anim.goalies[g.side.index])
                    figureTriangles += figures.lastTriangles
                }
                code == PUCK_MARK -> figures.drawPuckAt(scope, camera, puckXY[0], puckXY[1], puckZ)
                else -> {
                    // A side's net is on the goal line it defends, extending toward its end boards.
                    val front = code >= NET_FRONT_MARK
                    val defender = Side.entries[code - if (front) NET_FRONT_MARK else NET_MARK]
                    val dir = defender.dir
                    if (front) figures.drawNetFront(scope, camera, -dir * Rink.GOAL_X, -dir)
                    else figures.drawNetBack(scope, camera, -dir * Rink.GOAL_X, -dir, sim.phase.afterGoal && sim.lastGoalSide == defender.other, timeS)
                }
            }
        }

        effects.draw(scope, camera)
        rink.drawNearBoards(scope, camera)
        // The near boards' wash would grey out a puck behind them: it goes on again over them, and
        // its shadow with it when it is in the air.
        if (!sim.puckHeld() && rink.nearBoardsCover(camera, puckXY[0], puckXY[1], PUCK_COVER_R)) {
            if (puckZ > 0f) figures.drawPuckShadow(scope, camera, puckXY[0], puckXY[1], puckZ)
            figures.drawPuckAt(scope, camera, puckXY[0], puckXY[1], puckZ)
        }
        if (edgeArrowFor(sim, arrow)) figures.drawEdgeArrow(scope, arrow[0], arrow[1], arrow[2], markerScale(sim))
    }

    private fun drawYellowRing(scope: DrawScope, x: Float, y: Float) {
        figures.drawGroundRing(scope, camera, x, y, PASS_RING_R, PASS_RING_OUTLINE, PASS_OUTLINE, 1f, 0f)
        figures.drawGroundRing(scope, camera, x, y, PASS_RING_R, PASS_RING_BAND, PASS_YELLOW, 1f, 0f)
    }

    /**
     * When the controlled carrier's pass target projects outside the viewport inset by [EDGE_INSET] px,
     * fills [out] with [screen x, screen y, angle in radians] of the tip of an arrow on the inset edge
     * pointing from the screen centre toward it, and returns true. The tip goes where that line meets
     * the edge, slid along the edge clear of the on-screen controls ([avoidControls]).
     */
    internal fun edgeArrowFor(sim: HockeySim, out: FloatArray): Boolean {
        val skaters = sim.skaters
        if (sim.controlledId !in skaters.indices) return false
        val carrier = sim.puck.carrier
        if (carrier == null || carrier !== skaters[sim.controlledId]) return false
        val target = sim.passTargetFor(carrier) ?: return false
        camera.project(target.x, target.y, 0f, proj)
        val left = EDGE_INSET; val right = camera.width - EDGE_INSET
        val top = EDGE_INSET; val bottom = camera.height - EDGE_INSET
        val px = proj[0]; val py = proj[1]
        if (px in left..right && py in top..bottom) return false
        val cx = camera.width / 2f; val cy = camera.height / 2f
        val dx = px - cx; val dy = py - cy
        // Shrink the centre-to-target vector until it just reaches the inset rectangle.
        var kx = Float.MAX_VALUE; var ky = Float.MAX_VALUE
        if (dx > 0f) kx = (right - cx) / dx else if (dx < 0f) kx = (left - cx) / dx
        if (dy > 0f) ky = (bottom - cy) / dy else if (dy < 0f) ky = (top - cy) / dy
        val k = min(1f, min(kx, ky))
        var tx = cx + dx * k; var ty = cy + dy * k
        // The whole arrow has to stay in the viewport: its corners reach past the inset by pad.
        val reach = figures.edgeArrowReach(markerScale(sim))
        val clear = reach + THUMB_GAP
        val pad = max(0f, clear - THUMB_GAP - EDGE_INSET)
        if (thumbCount > 0) {
            if (kx <= ky) ty = slideClear(ty, tx, top + pad, bottom - pad, alongY = true, clear)
            else tx = slideClear(tx, ty, left + pad, right - pad, alongY = false, clear)
        }
        // Slid or not (clear already, no controls, or no clear spot), an edge-bound tip stays in range.
        if (kx <= ky) ty = ty.coerceIn(top + pad, bottom - pad) else tx = tx.coerceIn(left + pad, right - pad)
        out[0] = tx; out[1] = ty
        out[2] = atan2(dy, dx)   // still along the centre-to-target line, so the body trails back inside
        return true
    }

    /**
     * Where along an edge (at [fixed] across it, running [lo]..[hi]; along y when [alongY]) the nearest
     * spot to [at] is that is [clear] px or more beyond every control circle: [at] itself when it is
     * clear, else the nearest clear end of a stretch a control blocks. [at] when no spot is clear.
     */
    private fun slideClear(at: Float, fixed: Float, lo: Float, hi: Float, alongY: Boolean, clear: Float): Float {
        if (clearAt(at, fixed, alongY, clear)) return at
        var best = at; var bestD = Float.MAX_VALUE
        for (i in 0 until thumbCount) {
            val c = if (alongY) thumbs[3 * i + 1] else thumbs[3 * i]
            val across = fixed - if (alongY) thumbs[3 * i] else thumbs[3 * i + 1]
            val r = thumbs[3 * i + 2] + clear
            if (abs(across) >= r) continue
            val half = sqrt(r * r - across * across) + SLIDE_EPS
            for (side in 0..1) {
                val end = if (side == 0) c - half else c + half
                if (end < lo || end > hi || !clearAt(end, fixed, alongY, clear)) continue
                val d = abs(end - at)
                if (d < bestD) { best = end; bestD = d }
            }
        }
        return best
    }

    /** Whether the edge spot [at] (along) by [fixed] (across) is at least [clear] px beyond every control circle. */
    private fun clearAt(at: Float, fixed: Float, alongY: Boolean, clear: Float): Boolean {
        val x = if (alongY) fixed else at
        val y = if (alongY) at else fixed
        for (i in 0 until thumbCount) {
            val ddx = x - thumbs[3 * i]; val ddy = y - thumbs[3 * i + 1]
            val r = thumbs[3 * i + 2] + clear
            if (ddx * ddx + ddy * ddy < r * r) return false
        }
        return true
    }

    /** Px per metre at head height over the controlled skater, so the edge arrow matches its on-screen size. */
    private fun markerScale(sim: HockeySim): Float {
        val c = sim.skaters[sim.controlledId]
        camera.project(c.x, c.y, FigurePainter.MARKER_TIP_Z, proj)
        return proj[2]
    }

    /** The outline-grown corners (3 x, y pairs) of the arrow for this frame; false when there is none. Exposed for tests. */
    internal fun edgeArrowCorners(sim: HockeySim, out: FloatArray): Boolean {
        if (!edgeArrowFor(sim, arrow)) return false
        figures.edgeArrowCorners(arrow[0], arrow[1], arrow[2], markerScale(sim), true, out)
        return true
    }

    internal fun numberFor(s: Skater): Int = numbers[s.side.index * Position.entries.size + s.pos.ordinal]

    /**
     * Skater ids and goalie markers in far-to-near order for this frame; exposed for tests.
     * Codes: skater id (0..9), [GOALIE_MARK] + side, [PUCK_MARK] (left out while a goalie holds it),
     * [NET_MARK] + side for the back of a net and [NET_FRONT_MARK] + side for its near side. The back
     * sorts at the far side wall (y = GOAL_HALF_W) and the front at the near one, both winning ties,
     * so a puck inside the net is drawn between them; each goalie comes after its net's back. A
     * carried puck comes last, so no stick, skater or net ever hides it; a loose one sorts by y.
     */
    internal fun drawOrder(sim: HockeySim, out: IntArray): Int {
        var n = 0
        for (side in 0 until 2) {
            n = insert(out, n, NET_MARK + side, Rink.GOAL_HALF_W)
            n = insert(out, n, NET_FRONT_MARK + side, -Rink.GOAL_HALF_W)
        }
        // A goalie stands in front of its net, so it never sorts before its back, even at y > 0.
        for (i in sim.goalies.indices) {
            val g = sim.goalies[i]
            n = insert(out, n, GOALIE_MARK + g.side.index, min(g.y, Rink.GOAL_HALF_W - GOALIE_NUDGE))
        }
        val skaters = sim.skaters
        for (i in skaters.indices) n = insert(out, n, skaters[i].id, skaters[i].y)
        if (!sim.puckHeld()) {
            val puck = sim.puck
            val carrier = puck.carrier
            // Carried, it is drawn over every figure and net so nobody's stick or body hides it.
            val key = if (carrier != null) Float.NEGATIVE_INFINITY else puck.y
            n = insert(out, n, PUCK_MARK, key)
        }
        return n
    }

    /** Stable insertion into a list kept in descending key order (larger y = farther = first). */
    private fun insert(out: IntArray, n: Int, code: Int, key: Float): Int {
        var i = n
        while (i > 0 && keys[i - 1] < key) {
            keys[i] = keys[i - 1]; out[i] = out[i - 1]; i--
        }
        keys[i] = key; out[i] = code
        return n + 1
    }

    internal companion object {
        const val GOALIE_MARK = 10
        const val PUCK_MARK = 12
        const val NET_MARK = 13
        const val NET_FRONT_MARK = 15
        const val ORDER_SIZE = 17

        const val EDGE_INSET = 24f
        /** World radius of the drawn puck with its rims, for whether the near boards cover it. */
        const val PUCK_COVER_R = FigurePainter.PUCK_DRAW_R + 2f * FigurePainter.PUCK_RIM
        /** Room for the resting stick and the buttons with the puck. */
        const val MAX_THUMBS = 8
        /** Px the edge arrow keeps beyond a control circle, past its own reach from its tip. */
        const val THUMB_GAP = 16f
        /** Px past the end of a blocked stretch, so rounding never leaves the arrow just inside it. */
        const val SLIDE_EPS = 1f
        const val LEAD_S = 0.15f
        /** Zoom the camera pushes in to on the scorer through the goal pause. */
        const val GOAL_ZOOM = 1.3f
        const val GOALIE_NUDGE = 0.01f
        /** Not a hockey number, so FigurePainter draws none. */
        const val NO_NUMBER = -1
        const val CONTROL_RING_R = 0.7f
        const val CONTROL_RING_BAND = 0.16f
        const val CONTROL_FILL_ALPHA = 0.3f
        const val PASS_RING_R = 0.6f
        const val PASS_RING_BAND = 0.08f
        const val PASS_RING_OUTLINE = 0.16f
        val PASS_YELLOW = Color(0xFFFFD21F)
        val PASS_OUTLINE = Color(0xFF14181F)
        val CONTROL_GREEN = Color(0xFF2CF04A)
    }
}
