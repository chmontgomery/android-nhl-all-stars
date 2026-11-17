package com.personal.hockey.render.figure

import com.personal.hockey.render.figure.Skeleton.ANKLE
import com.personal.hockey.render.figure.Skeleton.BLADE_HEEL
import com.personal.hockey.render.figure.Skeleton.BLADE_TOE
import com.personal.hockey.render.figure.Skeleton.ELBOW
import com.personal.hockey.render.figure.Skeleton.HAND_L
import com.personal.hockey.render.figure.Skeleton.HAND_R
import com.personal.hockey.render.figure.Skeleton.HEAD
import com.personal.hockey.render.figure.Skeleton.HEAD_FWD
import com.personal.hockey.render.figure.Skeleton.HEEL
import com.personal.hockey.render.figure.Skeleton.HIP_C
import com.personal.hockey.render.figure.Skeleton.HIP_L
import com.personal.hockey.render.figure.Skeleton.HIP_R
import com.personal.hockey.render.figure.Skeleton.KNEE
import com.personal.hockey.render.figure.Skeleton.PANTS_BOT
import com.personal.hockey.render.figure.Skeleton.PANTS_TOP
import com.personal.hockey.render.figure.Skeleton.SH_C
import com.personal.hockey.render.figure.Skeleton.SH_L
import com.personal.hockey.render.figure.Skeleton.SH_R
import com.personal.hockey.render.figure.Skeleton.STICK_TOP
import com.personal.hockey.render.figure.Skeleton.TOE
import com.personal.hockey.render.mesh.Mesh
import com.personal.hockey.render.mesh.MeshBuilder
import com.personal.hockey.render.mesh.Part

/** Colour slots of a figure's palette: kit colours first, then the fixed gear colours. */
object Palette {
    const val JERSEY = 0; const val TRIM = 1; const val PANTS = 2; const val HELMET = 3; const val SKIN = 4
    const val BOOT = 5; const val STEEL = 6; const val STICK = 7; const val TAPE = 8; const val PAD = 9
    const val CAGE = 10; const val SIZE = 11
}

/**
 * A skater's low-poly body, built once: every part hangs on a bone of [Skeleton] (see [Mesh] for the
 * bone frame). On the torso bone (hips to shoulders) +b is the back; on the pants bone (pointing down)
 * +b is the front; on the head, skate and blade bones (pointing forward) +b is up. The head bone is
 * 1 m long, so its fractions are metres.
 */
object SkaterBody {
    /** Depth bias, metres, keeping nested layers (pants, jersey, pads) in order; see [Part.nearer]. */
    private const val LAYER_M = 0.2f

    /**
     * The jersey: a trim hem, and a trim band just under the shoulder pads. Above the band it is
     * jersey-coloured, like the pads over it, so a sorting slip there never shows.
     */
    private val torso = tube(0.25f, 0.28f, 6, 2.8f, kb = 0.64f, slot = Palette.JERSEY,
        bands = floatArrayOf(0f, 0.15f, 0.6f, 0.74f), bandSlot = Palette.TRIM)
    /** Shoulder pads under the jersey: a broad ellipsoid sitting on the shoulders. */
    private val yoke = MeshBuilder.placed(MeshBuilder.ellipsoid(0.14f, 0.34f, 0.21f, 6, 4, Palette.JERSEY), t = 1f, e = -0.03f)
    /** A short barrel, flaring toward the bottom. */
    private val pants = tube(0.235f, 0.275f, 6, 2.6f, kb = 0.63f, slot = Palette.PANTS)
    private val thigh = tube(0.13f, 0.1f, 5, 2f, slot = Palette.PANTS)
    /** Sock over the shin pad, its trim band running down into the boot. */
    private val shin = tube(0.1f, 0.075f, 5, 1.5f, slot = Palette.JERSEY, bands = floatArrayOf(0.6f, 1f), bandSlot = Palette.TRIM)
    /** Boot and blade; goalies wear the same skates. */
    internal val skate = MeshBuilder.join(
        MeshBuilder.placed(MeshBuilder.taperedBox(-0.12f, 0.82f, 0.12f, 0.15f, 0.1f, 0.09f, Palette.BOOT), b = 0.05f),
        MeshBuilder.placed(MeshBuilder.taperedBox(-0.15f, 1.08f, 0.018f, 0.045f, 0.018f, 0.045f, Palette.STEEL), b = -0.03f),
    )
    /** Wider at the elbow, for the elbow pad. */
    private val upperArm = tube(0.085f, 0.095f, 5, 1.5f, slot = Palette.JERSEY)
    /**
     * The forearm with its trim band, and the glove past the hand in the pants colour as before. One
     * part, so the glove and the cuff sort against each other triangle by triangle.
     */
    private val forearm = MeshBuilder.join(
        tube(0.085f, 0.07f, 5, 1.5f, slot = Palette.JERSEY, bands = floatArrayOf(0.55f, 1f), bandSlot = Palette.TRIM),
        MeshBuilder.placed(MeshBuilder.ellipsoid(0.1f, 0.08f, 0.085f, 4, 3, Palette.PANTS), t = 1f, e = 0.02f),
    )
    /** Helmet shell, the face under its front, and a visor strip across the eyes. */
    private val head = MeshBuilder.join(
        MeshBuilder.placed(MeshBuilder.ellipsoid(0.155f, 0.14f, 0.15f, 8, 4, Palette.HELMET), e = -0.015f, b = 0.035f),
        MeshBuilder.placed(MeshBuilder.ellipsoid(0.11f, 0.09f, 0.1f, 5, 2, Palette.SKIN), e = 0.04f, b = -0.04f),
        MeshBuilder.taperedBox(0.1f, 0.14f, 0.2f, 0.03f, 0.18f, 0.025f, Palette.TAPE),
    )
    private val shaft = MeshBuilder.taperedBox(-0.04f, 1f, 0.04f, 0.032f, 0.035f, 0.028f, Palette.STICK)
    private val blade = MeshBuilder.placed(MeshBuilder.taperedBox(-0.06f, 1f, 0.025f, 0.08f, 0.025f, 0.065f, Palette.TAPE), b = 0.03f)

    /**
     * A tapered tube of half-width [r0] to [r1] m (to the side) and [kb] times that deep, whose rounded
     * ends reach only 1/[flat] of the radius past its joints. Short ends keep a limb's cap from poking
     * through the next part, where the far-to-near sort would show it. [GoalieBody] builds with it too.
     */
    internal fun tube(
        r0: Float, r1: Float, sides: Int, flat: Float, kb: Float = 1f, slot: Int,
        bands: FloatArray = FloatArray(0), bandSlot: Int = slot,
    ): Mesh = MeshBuilder.placed(MeshBuilder.capsule(r0 / flat, r1 / flat, sides, 1, slot, bands, bandSlot), ka = flat, kb = flat * kb)

    val parts: Array<Part> = arrayOf(
        // Pants under the jersey under the pads, whatever the lean: leaning hard away from the camera
        // puts the jersey's middle about 0.13 m farther than the pants' (and the pads' than the jersey's).
        Part(PANTS_TOP, PANTS_BOT, pants, nearer = -LAYER_M),
        Part(HIP_C, SH_C, torso),
        Part(HIP_C, SH_C, yoke, nearer = LAYER_M),
        Part(HIP_L, HIP_L + KNEE, thigh), Part(HIP_R, HIP_R + KNEE, thigh),
        Part(HIP_L + KNEE, HIP_L + ANKLE, shin), Part(HIP_R + KNEE, HIP_R + ANKLE, shin),
        Part(HIP_L + HEEL, HIP_L + TOE, skate), Part(HIP_R + HEEL, HIP_R + TOE, skate),
        Part(SH_L, SH_L + ELBOW, upperArm), Part(SH_R, SH_R + ELBOW, upperArm),
        Part(SH_L + ELBOW, HAND_L, forearm), Part(SH_R + ELBOW, HAND_R, forearm),
        Part(HEAD, HEAD_FWD, head),
        Part(STICK_TOP, BLADE_HEEL, shaft),
        Part(BLADE_HEEL, BLADE_TOE, blade),
    )
}
