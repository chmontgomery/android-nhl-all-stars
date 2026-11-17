package com.personal.hockey.render.figure

/**
 * Joint slots of a figure's skeleton. Each leg is hip, knee, ankle, heel, toe in that order (offsets
 * [KNEE]..[TOE] from [HIP_L] or [HIP_R]); each arm is shoulder then elbow ([ELBOW] from [SH_L] or [SH_R]).
 * [HEAD_FWD] is a pseudo-joint 1 m ahead of [HEAD], so the head's bone points along the facing and its
 * fractions read as metres. [PANTS_TOP] and [PANTS_BOT] are derived from the hips and shoulders.
 */
object Skeleton {
    const val HIP_L = 0; const val HIP_R = 5
    const val SH_L = 10; const val SH_R = 12
    const val HAND_L = 14; const val HAND_R = 15
    const val HIP_C = 16; const val SH_C = 17; const val HEAD = 18; const val HEAD_FWD = 19
    const val STICK_TOP = 20; const val STICK_MID = 21; const val BLADE_HEEL = 22; const val BLADE_TOE = 23
    const val PANTS_TOP = 24; const val PANTS_BOT = 25
    const val SLOTS = 26

    // Offsets within a leg or an arm.
    const val KNEE = 1; const val ANKLE = 2; const val HEEL = 3; const val TOE = 4
    const val ELBOW = 1
}

/** Joint positions in the figure's frame per slot: [a] metres forward along its facing, [b] to its left, [z] up. */
class Pose {
    val a = FloatArray(Skeleton.SLOTS)
    val b = FloatArray(Skeleton.SLOTS)
    val z = FloatArray(Skeleton.SLOTS)
}
