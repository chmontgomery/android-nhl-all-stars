package com.personal.hockey.render

import com.personal.hockey.sim.Rink
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Low side-on broadcast camera: a pinhole at rink (focusX, focusY - 34, 12) looking at
 * (focusX, focusY - 1, 0), a pitch of about 20°. It pans along x and y without turning, so a
 * point's screen y and scale depend only on its y - focusY, never on x or focusX.
 *
 * The focal length is a fixed multiple of the viewport height (a fixed vertical field of view)
 * times [zoom]. At zoom 1 a skater is about 1.6 times the size he was with the old raised camera,
 * and at 20:9 about 22 m of rink length is visible at the focus depth. The view no longer fits the
 * rink's width, so the camera follows its target in y too, clamped so the far glass top never drops
 * into view and the near boards never rise above [NEAR_FOOT] of the screen.
 */
class Camera {
    /** Rink x the camera looks at. */
    var focusX = 0f
    /** Rink y the camera looks at; the eye and the look-at point move together. */
    var focusY = 0f
    /** Zoom the camera eases toward in [follow]; 1 is the normal framing. */
    var zoomGoal = 1f
    /** Multiplies the focal length. */
    var zoom = 1f
        private set
    var width = 2400f
        private set
    var height = 1080f
        private set

    /** World position of the eye. */
    val eyeX: Float get() = focusX
    val eyeY: Float get() = focusY + EYE_Y
    val eyeZ: Float get() = EYE_Z

    private var focal = FOCAL_PER_HEIGHT * height
    /** Focal length in pixels: a point at depth d metres is drawn at [focalLength] / d pixels per metre. */
    val focalLength: Float get() = focal
    private var centreY = CENTRE_Y * height
    /** Up to here the camera centres on its target. */
    private var innerFocus = 0f
    /** The furthest the camera goes, with its target at an end goal line or beyond. */
    private var maxFocus = 0f
    /** focusY range: the far glass top at the top edge, the near boards' foot at [NEAR_FOOT]. */
    private var minFocusY = 0f
    private var maxFocusY = 0f

    init { setViewport(width, height) }

    /** Ignores an empty viewport (Compose can report 0×0 on the first pass), keeping the last real one. */
    fun setViewport(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        this.width = width
        this.height = height
        updateLimits()
    }

    /**
     * Eases the zoom toward [zoomGoal] and the focus toward where (targetX, targetY) should be framed.
     * In x, through the middle of the rink the camera centres on it, as far as showing [OVERSHOOT] m
     * past the end boards at the focus depth. Beyond that it keeps going more slowly, until with the
     * target on a goal line or behind it the view shows [END_OVERSHOOT] m of arena past the end
     * boards: play in a near corner then sits clear of the thumb on the stick (left) and the action
     * buttons (right) instead of under them. In y it centres on the target within the clamp.
     */
    fun follow(targetX: Float, targetY: Float, dt: Float) {
        if (zoom != zoomGoal) {
            zoom += (zoomGoal - zoom) * (1f - exp(-dt / ZOOM_TAU))
            if (abs(zoomGoal - zoom) < ZOOM_SNAP) zoom = zoomGoal
            updateLimits()
        }
        val k = 1f - exp(-dt / FOLLOW_TAU)
        focusX += (frame(targetX) - focusX) * k
        focusY += (clampFocusY(targetY) - focusY) * k
    }

    /** Jumps to where [follow] would settle, zoom included. */
    fun snapTo(targetX: Float, targetY: Float = 0f) {
        if (zoom != zoomGoal) {
            zoom = zoomGoal
            updateLimits()
        }
        focusX = frame(targetX)
        focusY = clampFocusY(targetY)
    }

    /** The focus that frames a target at x; see [follow]. */
    private fun frame(x: Float): Float {
        val a = abs(x)
        if (a <= innerFocus || maxFocus <= innerFocus) return clampFocus(x)
        val t = ((a - innerFocus) / (Rink.GOAL_X - innerFocus).coerceAtLeast(1f)).coerceAtMost(1f)
        val f = innerFocus + (maxFocus - innerFocus) * t
        return if (x < 0f) -f else f
    }

    /** Rink (x, y, height z) → out[0]=screen x, out[1]=screen y, out[2]=pixels per metre at that depth. */
    fun project(x: Float, y: Float, z: Float, out: FloatArray) {
        val dy = y - focusY - EYE_Y
        val dz = z - EYE_Z
        val depth = max(dy * FWD_Y + dz * FWD_Z, MIN_DEPTH)
        val up = dy * UP_Y + dz * UP_Z
        val s = focal / depth
        out[0] = width * 0.5f + (x - focusX) * s
        out[1] = centreY - up * s
        out[2] = s
    }

    /** Recomputes everything that depends on the viewport and the zoom. */
    private fun updateLimits() {
        focal = FOCAL_PER_HEIGHT * height * zoom
        centreY = CENTRE_Y * height
        val halfVisible = width / 2f * DEPTH_AT_FOCUS / focal
        innerFocus = max(0f, Rink.HALF_L + OVERSHOOT - halfVisible)
        maxFocus = max(innerFocus, Rink.HALF_L + END_OVERSHOOT - halfVisible)
        maxFocusY = focusYPutting(Rink.HALF_W, GLASS_TOP_Z, 0f)
        minFocusY = focusYPutting(-Rink.HALF_W, 0f, NEAR_FOOT * height)
        if (minFocusY > maxFocusY) {
            // A viewport too tall for the clamp: sit midway.
            val mid = (minFocusY + maxFocusY) / 2f
            minFocusY = mid; maxFocusY = mid
        }
        focusX = clampFocus(focusX)
        focusY = clampFocusY(focusY)
    }

    /**
     * The focusY that projects rink point (y, z) to screen y [screenY]. From project():
     * up / depth = (centreY - screenY) / focal, which is linear in dy = y - focusY - EYE_Y.
     */
    private fun focusYPutting(y: Float, z: Float, screenY: Float): Float {
        val k = (centreY - screenY) / focal
        val dz = z - EYE_Z
        val dy = dz * (k * FWD_Z - UP_Z) / (UP_Y - k * FWD_Y)
        return y - EYE_Y - dy
    }

    private fun clampFocus(x: Float): Float = x.coerceIn(-maxFocus, maxFocus)

    private fun clampFocusY(y: Float): Float = y.coerceIn(minFocusY, maxFocusY)

    private companion object {
        // The eye and the look-at point, as offsets in y from focusY.
        const val EYE_Y = -34f
        const val EYE_Z = 12f
        const val LOOK_Y = -1f
        const val LOOK_Z = 0f
        /** At zoom 1: 2.35 (the old raised camera) × 1.6. */
        const val FOCAL_PER_HEIGHT = 3.58f
        const val CENTRE_Y = 0.5f
        const val OVERSHOOT = 1.5f
        const val END_OVERSHOOT = 4f
        const val FOLLOW_TAU = 0.25f
        const val ZOOM_TAU = 0.4f
        /** Close enough to the goal to land on it, so the limits stop being recomputed. */
        const val ZOOM_SNAP = 1e-4f
        const val MIN_DEPTH = 0.1f
        /** RinkPainter's far glass top (BOARD_H + GLASS_H): never dropped below the top edge. */
        const val GLASS_TOP_Z = 2.67f
        /**
         * Highest the foot of the near boards gets, as a fraction of the height. Like the old camera's
         * framing it leaves a strip of near stands, so a puck on the near boards sits above the bottom.
         */
        const val NEAR_FOOT = 0.87f

        val VIEW_LEN = sqrt((LOOK_Y - EYE_Y) * (LOOK_Y - EYE_Y) + (LOOK_Z - EYE_Z) * (LOOK_Z - EYE_Z))
        val FWD_Y = (LOOK_Y - EYE_Y) / VIEW_LEN
        val FWD_Z = (LOOK_Z - EYE_Z) / VIEW_LEN
        // Up = right (1, 0, 0) × forward.
        val UP_Y = -FWD_Z
        val UP_Z = FWD_Y
        /** Depth of a point on the ice at y = focusY. */
        val DEPTH_AT_FOCUS = (0f - EYE_Y) * FWD_Y + (0f - EYE_Z) * FWD_Z
    }
}
