package com.personal.hockey.input

enum class Btn { SHOOT, PASS, DEKE, POKE, BODY }

class ButtonSpot(val btn: Btn, val cx: Float, val cy: Float, val radius: Float)

/** Screen geometry of the touch controls, in pixels. Pure arithmetic; no Compose types. */
class ControlsLayout(
    val width: Float,
    val height: Float,
    val density: Float,
    insetLeft: Float = 0f,
    insetTop: Float = 0f,
    insetRight: Float = 0f,
    insetBottom: Float = 0f,
) {
    val stickZoneRight: Float = width * 0.45f
    val stickRadius: Float = 56f * density
    val stickRestX: Float = insetLeft + 96f * density
    val stickRestY: Float = height - insetBottom - 96f * density

    val buttonRadius: Float = 40f * density
    val touchRadius: Float = buttonRadius + 12f * density

    private val offence: List<ButtonSpot>
    private val defence: List<ButtonSpot>

    init {
        val right = width - insetRight
        val bottom = height - insetBottom
        // Centres sit far enough in from the safe edges that rounded screen corners never clip a button.
        val slotAx = right - 162f * density
        val slotBx = right - 68f * density
        val lowY = bottom - 72f * density
        val highY = bottom - 176f * density
        offence = listOf(
            ButtonSpot(Btn.SHOOT, slotBx, highY, buttonRadius),
            ButtonSpot(Btn.PASS, slotAx, lowY, buttonRadius),
            ButtonSpot(Btn.DEKE, slotBx, lowY, buttonRadius),
        )
        defence = listOf(
            ButtonSpot(Btn.POKE, slotAx, lowY, buttonRadius),
            ButtonSpot(Btn.BODY, slotBx, lowY, buttonRadius),
        )
    }

    fun buttons(hasPuck: Boolean): List<ButtonSpot> = if (hasPuck) offence else defence

    /**
     * Bottom-left region, from the left screen edge, around the resting stick, where an edge swipe
     * should move the stick rather than trigger Back: the bottom [GESTURE_EXCLUSION_DP] of the screen,
     * the system's per-edge cap.
     * As (left, top, right, bottom) in pixels.
     */
    val gestureExclusion: FloatArray = floatArrayOf(
        0f,
        (height - GESTURE_EXCLUSION_DP * density).coerceAtLeast(0f),
        stickRestX + stickRadius,
        height,
    )

    private companion object {
        const val GESTURE_EXCLUSION_DP = 200f
    }
}
