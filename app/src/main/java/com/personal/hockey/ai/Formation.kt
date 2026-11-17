package com.personal.hockey.ai

import com.personal.hockey.sim.Position
import com.personal.hockey.sim.Rink
import com.personal.hockey.sim.Side
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where each position stands when it isn't playing the puck. Worked out in the side's own frame
 * (attacking +x, own net at -GOAL_X); AWAY is the same picture mirrored through centre ice.
 */
object Formation {
    /** Spots are kept this far inside the boards. */
    private const val BOARD_MARGIN = 1f
    /** Spots are kept this far from a goal-line centre: out of the crease and the net. */
    private const val CREASE_CLEAR = Rink.CREASE_R + 0.6f
    private const val SLOT_X = 19f
    private const val C_AHEAD = 6f
    private const val WING_WIDE = 8f
    private const val WING_AHEAD = 4f
    private const val WING_MAX_X = 22f
    private const val D_WIDE = 6f
    private const val D_TRAIL = 3f
    private const val D_DEF_FRACTION = 0.4f   // defenders this far from own net toward the puck
    private const val D_DEF_WIDE = 2f
    private const val POINT_COVER_X = Rink.BLUE_X + 1.5f
    private const val POINT_COVER_WIDE = 7f
    private const val C_SHADOW = 4f

    /** Home spot for a position given puck location and who has it. Writes [x, y] to out. */
    fun spot(side: Side, pos: Position, puckX: Float, puckY: Float, possession: Side?, out: FloatArray) {
        val d = side.dir
        val px = puckX * d; val py = puckY * d
        when (possession) {
            side -> attacking(pos, px, py, out)
            null -> {
                attacking(pos, px, py, out)
                val ax = out[0]; val ay = out[1]
                defending(pos, px, py, out)
                out[0] = (ax + out[0]) * 0.5f; out[1] = (ay + out[1]) * 0.5f
            }
            else -> defending(pos, px, py, out)
        }
        keepLegal(out)
        out[0] *= d; out[1] *= d
    }

    /** Own frame: C up the slot ahead of the puck, wings wide and ahead, defenders at the blue line. */
    private fun attacking(pos: Position, px: Float, py: Float, out: FloatArray) {
        when (pos) {
            Position.C -> {
                val dx = SLOT_X - px; val dy = -py
                val len = sqrt(dx * dx + dy * dy)
                if (len > C_AHEAD) { out[0] = px + dx / len * C_AHEAD; out[1] = py + dy / len * C_AHEAD }
                else { out[0] = SLOT_X; out[1] = 0f }
            }
            Position.LW -> { out[0] = min(px + WING_AHEAD, WING_MAX_X); out[1] = WING_WIDE }
            Position.RW -> { out[0] = min(px + WING_AHEAD, WING_MAX_X); out[1] = -WING_WIDE }
            // At the offensive blue line, but never further up ice than just behind the puck.
            Position.LD -> { out[0] = min(Rink.BLUE_X + 1f, px - D_TRAIL); out[1] = D_WIDE }
            Position.RD -> { out[0] = min(Rink.BLUE_X + 1f, px - D_TRAIL); out[1] = -D_WIDE }
        }
    }

    /** Own frame: defenders between puck and net, wings on the points, C shadowing goal-side. */
    private fun defending(pos: Position, px: Float, py: Float, out: FloatArray) {
        val netX = -Rink.GOAL_X
        when (pos) {
            Position.C -> {
                val dx = netX - px; val dy = -py
                val len = sqrt(dx * dx + dy * dy)
                if (len > C_SHADOW) { out[0] = px + dx / len * C_SHADOW; out[1] = py + dy / len * C_SHADOW }
                else { out[0] = netX; out[1] = 0f }
            }
            Position.LW, Position.RW -> {
                out[0] = max(-POINT_COVER_X, min(px - WING_AHEAD, Rink.BLUE_X))
                out[1] = if (pos == Position.LW) POINT_COVER_WIDE else -POINT_COVER_WIDE
            }
            Position.LD, Position.RD -> {
                val bx = netX + (px - netX) * D_DEF_FRACTION
                val by = py * D_DEF_FRACTION
                out[0] = min(bx, px - 1f)
                out[1] = by + if (pos == Position.LD) D_DEF_WIDE else -D_DEF_WIDE
            }
        }
    }

    /** Out of both creases, then inside the boards. Symmetric, so mirroring commutes with it. */
    private fun keepLegal(out: FloatArray) {
        for (g in GOAL_SIDES) {
            val dx = out[0] - g * Rink.GOAL_X; val dy = out[1]
            val d2 = dx * dx + dy * dy
            if (d2 >= CREASE_CLEAR * CREASE_CLEAR) continue
            val d = sqrt(d2)
            if (d > 1e-4f) { out[0] = g * Rink.GOAL_X + dx / d * CREASE_CLEAR; out[1] = dy / d * CREASE_CLEAR }
            else out[0] = g * (Rink.GOAL_X - CREASE_CLEAR)
        }
        clampInside(out[0], out[1], out)
    }

    private val GOAL_SIDES = floatArrayOf(-1f, 1f)

    /** Pulls (x, y) to at least [BOARD_MARGIN] inside the boards; writes the point to out[0..1]. */
    private fun clampInside(x: Float, y: Float, out: FloatArray) {
        val r = BOARD_MARGIN
        val cx = Rink.HALF_L - Rink.CORNER_R; val cy = Rink.HALF_W - Rink.CORNER_R
        val sx = if (x < 0f) -1f else 1f; val sy = if (y < 0f) -1f else 1f
        var ax = min(abs(x), Rink.HALF_L - r); var ay = min(abs(y), Rink.HALF_W - r)
        if (ax > cx && ay > cy) {
            val dx = ax - cx; val dy = ay - cy
            val dd = sqrt(dx * dx + dy * dy)
            val lim = Rink.CORNER_R - r
            if (dd > lim) { ax = cx + dx / dd * lim; ay = cy + dy / dd * lim }
        }
        out[0] = sx * ax; out[1] = sy * ay
    }
}
