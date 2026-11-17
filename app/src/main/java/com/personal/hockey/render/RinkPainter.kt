package com.personal.hockey.render

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.personal.hockey.sim.Rink
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Colours and board text of the building. v1 has one; more rinks are added as data later. */
data class Arena(val primary: Long, val secondary: Long, val trim: Long, val boardText: String) {
    companion object { val ALL_STARS = Arena(0xFF154734, 0xFFA6192E, 0xFFDDCBA4, "NHL ALL STARS") } }

/**
 * Draws the building: stands, boards, glass, ice and markings. All geometry is built in rink
 * space at construction and only projected per frame into reused Paths, so drawing does not allocate.
 */
class RinkPainter(private val arena: Arena) {
    private val primary = Color(arena.primary)
    private val secondary = Color(arena.secondary)
    private val trim = Color(arena.trim)
    private val standTop = shade(primary, 0.55f)
    private val standMid = shade(secondary, 0.7f)

    /** Perimeter from (-HALF_L, 0) round the far side to (HALF_L, 0); the near half is its mirror. */
    private val farHalf = farPerimeter()
    private val markingsRed = Contours.build {
        quad(-LINE_HALF, LINE_HALF, -Rink.HALF_W, Rink.HALF_W)
        val goalY = Rink.HALF_W - Rink.CORNER_R +
            sqrt(Rink.CORNER_R * Rink.CORNER_R - sq(Rink.GOAL_X - (Rink.HALF_L - Rink.CORNER_R)))
        for (s in SIDES) quad(s * Rink.GOAL_X - GOAL_LINE_HALF, s * Rink.GOAL_X + GOAL_LINE_HALF, -goalY, goalY)
        for (sx in SIDES) for (sy in SIDES) {
            ring(sx * Rink.FACEOFF_X, sy * Rink.FACEOFF_Y, CIRCLE_R)
            circle(sx * Rink.FACEOFF_X, sy * Rink.FACEOFF_Y, DOT_R)
        }
    }
    private val creaseLines = Contours.build { for (s in SIDES) creaseOutline(s) }
    private val markingsBlue = Contours.build {
        for (s in SIDES) quad(s * Rink.BLUE_X - LINE_HALF, s * Rink.BLUE_X + LINE_HALF, -Rink.HALF_W, Rink.HALF_W)
        ring(0f, 0f, CIRCLE_R)
        circle(0f, 0f, DOT_R)
    }
    private val creases = Contours.build { for (s in SIDES) crease(s, Rink.CREASE_R) }

    /**
     * The centre-ice logo, two crossed sticks over a puck, painted on the ice in rink space so it
     * stays put as the camera moves. Each stick is its own contour set: the even-odd fill would
     * punch a hole where they cross if they shared one.
     */
    private val logoSticks = arrayOf(Contours.build { stick(1f) }, Contours.build { stick(-1f) })
    private val logoPuck = Contours.build { circle(0f, LOGO_PUCK_Y, LOGO_PUCK_R) }

    private val crowdCount = 700
    private val crowdX = FloatArray(crowdCount)
    private val crowdY = FloatArray(crowdCount)
    private val crowdZ = FloatArray(crowdCount)
    private val crowdColour = IntArray(crowdCount)
    private val crowdPalette = arrayOf(shade(primary, 1.3f), secondary, Color(0xFF6E6A66), Color(0xFF8C6E57), Color(0xFF3E4A5C))

    init {
        val random = Random(CROWD_SEED)
        for (i in 0 until crowdCount) {
            crowdX[i] = random.nextFloat() * 2f * CROWD_HALF_L - CROWD_HALF_L
            crowdY[i] = STAND_Y0 + random.nextFloat() * STAND_DEPTH
            crowdZ[i] = STAND_Z0 + (crowdY[i] - STAND_Y0) * STAND_RISE
            crowdColour[i] = random.nextInt(crowdPalette.size)
        }
    }

    private val icePath = Path()
    private val path = Path().apply { fillType = PathFillType.EvenOdd }
    private val p = FloatArray(3)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = arena.primary.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    /** Stands, far boards and glass, ice, markings and the centre-ice mark. */
    fun draw(scope: DrawScope, camera: Camera) {
        drawStands(scope, camera)

        icePath.reset()
        val n = farHalf.size / 2
        for (i in 0 until n) lineOrMove(icePath, camera, farHalf[2 * i], farHalf[2 * i + 1], 0f, i == 0)
        for (i in n - 1 downTo 0) lineOrMove(icePath, camera, farHalf[2 * i], -farHalf[2 * i + 1], 0f, false)
        icePath.close()
        scope.drawPath(icePath, ICE)

        // Lines, creases and logo are painted under the ice: the ice shows through them a little.
        fill(scope, camera, creases, CREASE, UNDER_ICE_ALPHA)
        fill(scope, camera, markingsBlue, BLUE, UNDER_ICE_ALPHA)
        fill(scope, camera, markingsRed, RED, UNDER_ICE_ALPHA)
        fill(scope, camera, creaseLines, RED, UNDER_ICE_ALPHA)
        for (stick in logoSticks) fill(scope, camera, stick, primary, UNDER_ICE_ALPHA)
        fill(scope, camera, logoPuck, PUCK_BLACK, UNDER_ICE_ALPHA)

        drawWall(scope, camera, ySign = 1f, alpha = 1f)
        drawBoardText(scope, camera)
        band(camera, ySign = 1f, BOARD_H, BOARD_H + GLASS_H)
        scope.drawPath(path, GLASS)
        band(camera, ySign = 1f, BOARD_H + GLASS_H - GLASS_EDGE, BOARD_H + GLASS_H)
        scope.drawPath(path, GLASS_EDGE_COLOUR)
    }

    /** Called after skaters; low, 35% alpha. */
    fun drawNearBoards(scope: DrawScope, camera: Camera) {
        drawWall(scope, camera, ySign = -1f, alpha = NEAR_ALPHA)
    }

    /**
     * Whether the near boards, drawn over the ice, cover any of a disc of radius [r] m on the ice at
     * (x, y) on screen: its nearest edge is below the top of the boards in front of it.
     */
    fun nearBoardsCover(camera: Camera, x: Float, y: Float, r: Float): Boolean {
        // The boards in front: the straight near side, or the corner arc beyond its ends.
        val ax = abs(x) - (Rink.HALF_L - Rink.CORNER_R)
        val boardY = if (ax <= 0f) -Rink.HALF_W
            else -(Rink.HALF_W - Rink.CORNER_R) - sqrt(max(0f, Rink.CORNER_R * Rink.CORNER_R - ax * ax))
        camera.project(x, boardY, BOARD_H, p)
        val boardTop = p[1]
        camera.project(x, y - r, 0f, p)
        return p[1] > boardTop
    }

    private fun drawStands(scope: DrawScope, camera: Camera) {
        scope.drawRect(STAND_LOW)
        camera.project(camera.focusX, Rink.HALF_W, BOARD_H, p)
        val boardTop = p[1]
        scope.drawRect(standTop, Offset.Zero, Size(camera.width, boardTop / 3f))
        scope.drawRect(standMid, Offset(0f, boardTop / 3f), Size(camera.width, boardTop / 3f))
        for (i in 0 until crowdCount) {
            camera.project(crowdX[i], crowdY[i], crowdZ[i], p)
            val r = CROWD_R * p[2]
            if (p[0] < -r || p[0] > camera.width + r || p[1] < -r || p[1] > boardTop + r) continue
            scope.drawCircle(crowdPalette[crowdColour[i]], r, Offset(p[0], p[1]))
        }
    }

    private fun drawWall(scope: DrawScope, camera: Camera, ySign: Float, alpha: Float) {
        band(camera, ySign, 0f, BOARD_H)
        scope.drawPath(path, Color.White, alpha)
        band(camera, ySign, 0f, KICK_H)
        scope.drawPath(path, trim, alpha)
        band(camera, ySign, BOARD_H - RAIL_H, BOARD_H)
        scope.drawPath(path, primary, alpha)
    }

    /** Screen rects (left, top, right, bottom) the board text fades away from: the scoreboard, the pause button. */
    private val clear = FloatArray(4 * MAX_CLEAR)

    /** The board text's width per pixel of text size, measured once so drawing never measures. */
    private val textWidthPerPx = textPaint.run { textSize = 100f; measureText(arena.boardText) / 100f }

    /**
     * Keeps the board text out from under an on-screen overlay: the text in slot [slot] (0 until
     * [MAX_CLEAR]) fades out as it slides under the rect, in canvas pixels. An empty rect clears the slot.
     */
    fun keepClear(slot: Int, left: Float, top: Float, right: Float, bottom: Float) {
        clear[4 * slot] = left; clear[4 * slot + 1] = top; clear[4 * slot + 2] = right; clear[4 * slot + 3] = bottom
    }

    private fun drawBoardText(scope: DrawScope, camera: Camera) {
        camera.project(0f, Rink.HALF_W, TEXT_BASE_Z, p)
        val baseY = p[1]
        val size = TEXT_H * p[2]
        textPaint.textSize = size
        val halfW = 0.5f * textWidthPerPx * size
        scope.drawIntoCanvas { canvas ->
            for (x in TEXT_XS) {
                camera.project(x, Rink.HALF_W, TEXT_BASE_Z, p)
                val alpha = boardTextAlpha(p[0] - halfW, baseY - size, p[0] + halfW, baseY, clear, size * FADE_PER_TEXT)
                if (alpha <= 0f) continue
                textPaint.alpha = (255f * alpha).toInt()
                canvas.nativeCanvas.drawText(arena.boardText, p[0], baseY, textPaint)
            }
        }
    }

    /** Fills [path] with the vertical strip of boards between heights z0 and z1 along one half. */
    private fun band(camera: Camera, ySign: Float, z0: Float, z1: Float) {
        path.reset()
        val n = farHalf.size / 2
        for (i in 0 until n) lineOrMove(path, camera, farHalf[2 * i], ySign * farHalf[2 * i + 1], z0, i == 0)
        for (i in n - 1 downTo 0) lineOrMove(path, camera, farHalf[2 * i], ySign * farHalf[2 * i + 1], z1, false)
        path.close()
    }

    private fun fill(scope: DrawScope, camera: Camera, shapes: Contours, colour: Color, alpha: Float = 1f) {
        path.reset()
        val xy = shapes.xy
        for (c in shapes.starts.indices) {
            val from = shapes.starts[c]
            val to = if (c + 1 < shapes.starts.size) shapes.starts[c + 1] else xy.size / 2
            for (i in from until to) lineOrMove(path, camera, xy[2 * i], xy[2 * i + 1], 0f, i == from)
            path.close()
        }
        scope.drawPath(path, colour, alpha)
    }

    private fun lineOrMove(target: Path, camera: Camera, x: Float, y: Float, z: Float, move: Boolean) {
        camera.project(x, y, z, p)
        if (move) target.moveTo(p[0], p[1]) else target.lineTo(p[0], p[1])
    }

    private companion object {
        val ICE = Color(0xFFF4F8FB)
        val RED = Color(0xFFC8102E)
        val BLUE = Color(0xFF1D4F9C)
        val CREASE = Color(0xFFA9D6F2)
        val GLASS = Color(0x1FFFFFFF)
        val GLASS_EDGE_COLOUR = Color(0x59FFFFFF)
        val STAND_LOW = Color(0xFF2A2D30)

        val SIDES = floatArrayOf(-1f, 1f)
        const val LINE_HALF = 0.15f
        const val GOAL_LINE_HALF = 0.05f
        const val CIRCLE_R = 4.5f
        const val RING_HALF = 0.05f
        const val DOT_R = 0.3f
        const val SEGMENTS = 48

        const val BOARD_H = 1.07f
        const val KICK_H = 0.25f
        const val RAIL_H = 0.12f
        const val GLASS_H = 1.6f
        const val GLASS_EDGE = 0.05f
        const val NEAR_ALPHA = 0.35f
        /** Board signs at centre ice and either side, so one is clear of the scoreboard and pause button. */
        val TEXT_XS = floatArrayOf(-11f, 0f, 11f)
        const val TEXT_BASE_Z = 0.4f
        const val TEXT_H = 0.5f
        /** The board text fades over this many text heights as it nears an overlay. */
        const val FADE_PER_TEXT = 1.5f
        val PUCK_BLACK = Color(0xFF111111)
        const val LOGO_PUCK_Y = -2.05f
        const val LOGO_PUCK_R = 0.6f
        /**
         * Lines, creases and the logo read as painted under the ice: still plain to see, but a shade
         * softer than the players and the real puck on top of it.
         */
        const val UNDER_ICE_ALPHA = 0.72f

        const val CROWD_SEED = 1105
        const val CROWD_HALF_L = 48f
        const val CROWD_R = 0.22f
        const val STAND_Y0 = 14.5f
        const val STAND_DEPTH = 3.5f
        const val STAND_Z0 = 1.3f
        const val STAND_RISE = 0.75f

        fun sq(v: Float) = v * v
        fun shade(c: Color, f: Float) =
            Color((c.red * f).coerceAtMost(1f), (c.green * f).coerceAtMost(1f), (c.blue * f).coerceAtMost(1f), c.alpha)

        fun farPerimeter(): FloatArray {
            val cx = Rink.HALF_L - Rink.CORNER_R
            val cy = Rink.HALF_W - Rink.CORNER_R
            val arc = 12
            val out = FloatArray((2 * (arc + 1) + 2) * 2)
            var k = 0
            fun put(x: Float, y: Float) { out[k++] = x; out[k++] = y }
            put(-Rink.HALF_L, 0f)
            for (i in 0..arc) {
                val a = PI.toFloat() - i * (PI.toFloat() / 2f) / arc
                put(-cx + Rink.CORNER_R * cos(a), cy + Rink.CORNER_R * sin(a))
            }
            for (i in 0..arc) {
                val a = PI.toFloat() / 2f - i * (PI.toFloat() / 2f) / arc
                put(cx + Rink.CORNER_R * cos(a), cy + Rink.CORNER_R * sin(a))
            }
            put(Rink.HALF_L, 0f)
            return out
        }
    }

    /** Closed polygons in rink space (metres, z = 0): flat (x, y) pairs plus each contour's first point. */
    private class Contours(val xy: FloatArray, val starts: IntArray) {
        class Builder {
            val xy = ArrayList<Float>()
            val starts = ArrayList<Int>()
            private fun begin() { starts += xy.size / 2 }
            private fun pt(x: Float, y: Float) { xy += x; xy += y }

            fun quad(x0: Float, x1: Float, y0: Float, y1: Float) {
                begin(); pt(x0, y0); pt(x1, y0); pt(x1, y1); pt(x0, y1)
            }

            fun circle(cx: Float, cy: Float, r: Float) {
                begin()
                for (i in 0 until SEGMENTS) {
                    val a = i * 2f * PI.toFloat() / SEGMENTS
                    pt(cx + r * cos(a), cy + r * sin(a))
                }
            }

            /** Painted with even-odd fill, an outer and inner circle make a line-width ring. */
            fun ring(cx: Float, cy: Float, r: Float) {
                circle(cx, cy, r + RING_HALF)
                circle(cx, cy, r - RING_HALF)
            }

            /** Half-disc in front of the goal line at x = side * GOAL_X, bulging toward centre ice. */
            fun crease(side: Float, r: Float) {
                begin()
                for (i in 0..SEGMENTS / 2) {
                    val a = -PI.toFloat() / 2f + i * PI.toFloat() / (SEGMENTS / 2)
                    pt(side * (Rink.GOAL_X - r * cos(a)), r * sin(a))
                }
            }

            /**
             * One logo stick, knob up the ice to one side and blade out along the ice to the other:
             * [side] 1 runs knob far-left to blade near-right, -1 mirrors it. Metres from centre ice.
             */
            fun stick(side: Float) {
                begin()
                for (i in STICK.indices step 2) pt(side * STICK[i], STICK[i + 1])
            }

            fun creaseOutline(side: Float) {
                crease(side, Rink.CREASE_R + RING_HALF)
                crease(side, Rink.CREASE_R - RING_HALF)
            }
        }

        companion object {
            fun build(block: Builder.() -> Unit): Contours {
                val b = Builder().apply(block)
                return Contours(b.xy.toFloatArray(), b.starts.toIntArray())
            }
        }
    }
}

/** Overlay slots the board text keeps clear of ([RinkPainter.keepClear]). */
const val CLEAR_SCOREBOARD = 0
const val CLEAR_PAUSE = 1
const val MAX_CLEAR = 2

/**
 * How opaque board text spanning ([left], [top])..([right], [bottom]) on screen should be: 1 when
 * it is at least [fade] px clear of every rect in [rects] (4 floats each, left, top, right, bottom;
 * an empty rect is ignored), falling to 0 as its nearer edge reaches a rect it overlaps vertically.
 */
internal fun boardTextAlpha(left: Float, top: Float, right: Float, bottom: Float, rects: FloatArray, fade: Float): Float {
    var alpha = 1f
    var i = 0
    while (i + 3 < rects.size) {
        val rl = rects[i]; val rt = rects[i + 1]; val rr = rects[i + 2]; val rb = rects[i + 3]
        i += 4
        if (rr <= rl || rb <= rt || bottom <= rt || top >= rb) continue
        // How far the text has slid into the rect grown by [fade] on each side.
        val into = min(right - (rl - fade), (rr + fade) - left)
        if (into <= 0f) continue
        alpha = min(alpha, max(0f, 1f - into / fade))
    }
    return alpha
}

/**
 * Outline of a logo stick at centre ice, (x, y) pairs in metres: up the shaft's left edge from the
 * heel to the knob, down its right edge to the blade, out to the toe and back along the blade's
 * underside round the heel.
 */
private val STICK = floatArrayOf(
    1.37f, -2.09f, -1.83f, 2.31f, -1.57f, 2.49f, 1.63f, -1.91f,
    2.85f, -1.95f, 3.0f, -2.05f, 3.0f, -2.25f, 2.9f, -2.35f,
    1.55f, -2.35f, 1.42f, -2.28f,
)
