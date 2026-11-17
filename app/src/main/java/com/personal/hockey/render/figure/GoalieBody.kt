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
import com.personal.hockey.render.figure.Skeleton.STICK_MID
import com.personal.hockey.render.figure.Skeleton.STICK_TOP
import com.personal.hockey.render.figure.Skeleton.TOE
import com.personal.hockey.render.figure.SkaterBody.tube
import com.personal.hockey.render.mesh.Mesh
import com.personal.hockey.render.mesh.MeshBuilder
import com.personal.hockey.render.mesh.Part

/**
 * A goalie's low-poly body, built once, on the same bones and frames as [SkaterBody]: a wide chest
 * protector, thick leg pads from above the knee to the skate, a catcher with a pocket on the left
 * hand, a blocker board on the right, a paddle stick and a mask in the helmet colour with a cage.
 * On the forearm bones (elbow to hand) +a is the figure's left and +b is up when the arm points ahead.
 */
object GoalieBody {
    /** Depth bias, metres, keeping nested layers in order; see [Part.nearer]. */
    private const val LAYER_M = 0.2f
    /** Depth bias of an inlay over the part it sits proud of. */
    private const val INLAY_M = 0.03f

    /** The jersey over the chest protector: wider than a skater's, trim hem and a trim band under the yoke. */
    private val torso = tube(0.3f, 0.35f, 6, 2.8f, kb = 0.85f, slot = Palette.JERSEY,
        bands = floatArrayOf(0f, 0.15f, 0.6f, 0.74f), bandSlot = Palette.TRIM)
    /** The chest protector's shoulder floaters: broader than a skater's shoulder pads. */
    private val yoke = MeshBuilder.placed(MeshBuilder.ellipsoid(0.15f, 0.42f, 0.22f, 6, 4, Palette.JERSEY), t = 1f, e = -0.03f)
    private val pants = tube(0.27f, 0.31f, 6, 2.6f, kb = 0.65f, slot = Palette.PANTS)
    private val thigh = tube(0.14f, 0.12f, 5, 2f, slot = Palette.PANTS)
    /**
     * A leg pad on the shin bone (knee to ankle; +b is the front): a thick white block with two trim
     * stripes, from well above the knee to over the boot. Rigid, so it keeps its size whatever the bend.
     */
    private val pad = rigid(
        MeshBuilder.placed(
            MeshBuilder.capsule(0.16f, 0.14f, 6, 2, Palette.PAD, floatArrayOf(0.2f, 0.28f, 0.56f, 0.64f), Palette.TRIM),
            b = 0.04f, kb = 1.1f,
        ),
        t = 0f, e = -0.12f, length = 0.44f,
    )
    /** Upper arm and forearm, padded thicker than a skater's; the trim cuff runs to the hand. */
    private val upperArm = tube(0.1f, 0.11f, 5, 1.5f, slot = Palette.JERSEY)
    private val forearm = tube(0.1f, 0.09f, 5, 1.5f, slot = Palette.JERSEY, bands = floatArrayOf(0.55f, 1f), bandSlot = Palette.TRIM)
    /** The catcher past the left hand: a broad white mitt, its trim pocket open ahead and outward. */
    private val catcher = MeshBuilder.join(
        MeshBuilder.placed(MeshBuilder.ellipsoid(0.11f, 0.13f, 0.18f, 6, 4, Palette.PAD), t = 1f, e = 0.06f),
        MeshBuilder.placed(MeshBuilder.ellipsoid(0.06f, 0.08f, 0.12f, 5, 3, Palette.TRIM), t = 1f, e = 0.12f, a = 0.06f, b = 0.02f),
    )
    /** The blocker on the back of the right hand: a white board standing outward of it, along the forearm. */
    private val blocker = rigid(MeshBuilder.placed(MeshBuilder.taperedBox(0f, 1f, 0.05f, 0.22f, 0.05f, 0.22f, Palette.PAD), a = -0.07f),
        t = 1f, e = -0.1f, length = 0.34f)
    /** A trim panel proud of both faces of the blocker, so it shows from either side. */
    private val blockerPanel = rigid(MeshBuilder.placed(MeshBuilder.taperedBox(0f, 1f, 0.07f, 0.12f, 0.07f, 0.12f, Palette.TRIM), a = -0.07f),
        t = 1f, e = -0.02f, length = 0.18f)
    /** The mask in the helmet colour, down over the chin, with a dark opening at the front. */
    private val mask = MeshBuilder.join(
        MeshBuilder.placed(MeshBuilder.ellipsoid(0.15f, 0.145f, 0.17f, 8, 4, Palette.HELMET), e = -0.03f),
        MeshBuilder.placed(MeshBuilder.ellipsoid(0.1f, 0.09f, 0.11f, 5, 2, Palette.TAPE), e = 0.04f, b = -0.02f),
    )
    /** The cage over the opening: three bars across it, reaching back to the cheeks, and three down its front. */
    private val cage = MeshBuilder.join(
        cageRail(0.04f), cageRail(-0.02f), cageRail(-0.08f),
        cageBar(0f), cageBar(0.055f), cageBar(-0.055f),
    )

    /** A bar across the face [b] m up, as deep as the cage, so it shows from the side as well. */
    private fun cageRail(b: Float): Mesh =
        MeshBuilder.placed(MeshBuilder.taperedBox(0.04f, 0.18f, 0.17f, 0.024f, 0.15f, 0.024f, Palette.CAGE), b = b)

    /** An upright bar down the front of the cage, [a] m to the side. */
    private fun cageBar(a: Float): Mesh =
        MeshBuilder.placed(MeshBuilder.taperedBox(0.16f, 0.185f, 0.024f, 0.17f, 0.024f, 0.17f, Palette.CAGE), a = a, b = -0.02f)

    private val shaft = MeshBuilder.taperedBox(-0.04f, 1f, 0.04f, 0.032f, 0.035f, 0.028f, Palette.STICK)
    /** The goalie stick's wide paddle, flat in the plane of the blade. */
    private val paddle = MeshBuilder.taperedBox(0f, 1f, 0.09f, 0.025f, 0.09f, 0.025f, Palette.STICK)
    private val blade = MeshBuilder.placed(MeshBuilder.taperedBox(-0.06f, 1f, 0.025f, 0.09f, 0.025f, 0.08f, Palette.TAPE), b = 0.035f)

    /**
     * [mesh], built along t from 0 to 1, made rigid: [length] metres long starting [e] m past bone
     * fraction [t], so it keeps its size however long the bone is.
     */
    private fun rigid(mesh: Mesh, t: Float, e: Float, length: Float): Mesh {
        val n = mesh.vertexCount
        return Mesh(
            t = FloatArray(n) { t }, e = FloatArray(n) { e + mesh.t[it] * length + mesh.e[it] },
            a = mesh.a.copyOf(), b = mesh.b.copyOf(),
            nt = mesh.nt.copyOf(), na = mesh.na.copyOf(), nb = mesh.nb.copyOf(),
            slot = mesh.slot.copyOf(), tri = mesh.tri.copyOf(),
        )
    }

    val parts: Array<Part> = arrayOf(
        // Pants under the jersey under the shoulder floaters, as on a skater.
        Part(PANTS_TOP, PANTS_BOT, pants, nearer = -LAYER_M),
        Part(HIP_C, SH_C, torso),
        Part(HIP_C, SH_C, yoke, nearer = LAYER_M),
        Part(HIP_L, HIP_L + KNEE, thigh), Part(HIP_R, HIP_R + KNEE, thigh),
        Part(HIP_L + HEEL, HIP_L + TOE, SkaterBody.skate), Part(HIP_R + HEEL, HIP_R + TOE, SkaterBody.skate),
        Part(HIP_L + KNEE, HIP_L + ANKLE, pad), Part(HIP_R + KNEE, HIP_R + ANKLE, pad),
        Part(SH_L, SH_L + ELBOW, upperArm), Part(SH_R, SH_R + ELBOW, upperArm),
        Part(SH_L + ELBOW, HAND_L, forearm), Part(SH_R + ELBOW, HAND_R, forearm),
        Part(SH_L + ELBOW, HAND_L, catcher),
        Part(SH_R + ELBOW, HAND_R, blocker), Part(SH_R + ELBOW, HAND_R, blockerPanel, nearer = INLAY_M),
        Part(HEAD, HEAD_FWD, mask), Part(HEAD, HEAD_FWD, cage, nearer = INLAY_M),
        Part(STICK_TOP, STICK_MID, shaft),
        Part(STICK_MID, BLADE_HEEL, paddle),
        Part(BLADE_HEEL, BLADE_TOE, blade),
    )
}
