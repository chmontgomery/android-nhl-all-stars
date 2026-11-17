package com.personal.hockey.render

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.personal.hockey.render.anim.AnimState
import com.personal.hockey.render.anim.ClipLibrary
import com.personal.hockey.render.anim.ONE_TIMER_S
import com.personal.hockey.render.anim.SLAP_WINDUP
import com.personal.hockey.render.anim.blendPose
import com.personal.hockey.render.anim.sweepOffset
import com.personal.hockey.render.figure.GoalieBody
import com.personal.hockey.render.figure.Palette
import com.personal.hockey.render.figure.Pose
import com.personal.hockey.render.figure.SkaterBody
import com.personal.hockey.render.figure.Skeleton.BLADE_HEEL
import com.personal.hockey.render.figure.Skeleton.BLADE_TOE
import com.personal.hockey.render.figure.Skeleton.HAND_L
import com.personal.hockey.render.figure.Skeleton.HAND_R
import com.personal.hockey.render.figure.Skeleton.HEAD
import com.personal.hockey.render.figure.Skeleton.HEAD_FWD
import com.personal.hockey.render.figure.Skeleton.HIP_C
import com.personal.hockey.render.figure.Skeleton.HIP_L
import com.personal.hockey.render.figure.Skeleton.HIP_R
import com.personal.hockey.render.figure.Skeleton.PANTS_BOT
import com.personal.hockey.render.figure.Skeleton.PANTS_TOP
import com.personal.hockey.render.figure.Skeleton.SH_C
import com.personal.hockey.render.figure.Skeleton.SH_L
import com.personal.hockey.render.figure.Skeleton.SH_R
import com.personal.hockey.render.figure.Skeleton.SLOTS
import com.personal.hockey.render.figure.Skeleton.STICK_MID
import com.personal.hockey.render.figure.Skeleton.STICK_TOP
import com.personal.hockey.render.mesh.MeshPainter
import com.personal.hockey.render.mesh.Part
import com.personal.hockey.render.mesh.TriangleSink
import com.personal.hockey.render.mesh.TriangleSinks
import com.personal.hockey.sim.Act
import com.personal.hockey.sim.Goalie
import com.personal.hockey.sim.Rink
import com.personal.hockey.sim.Skater
import com.personal.hockey.sim.Tuning
import com.personal.hockey.teams.Kit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A teams.Kit converted once to Compose colours. */
class KitColors(kit: Kit) {
    val number = Color(kit.number)
    /** Mesh colours by [Palette] slot: this kit's, then the fixed gear colours. */
    val palette = IntArray(Palette.SIZE).also {
        it[Palette.JERSEY] = kit.jersey.toInt(); it[Palette.TRIM] = kit.trim.toInt()
        it[Palette.PANTS] = kit.pants.toInt(); it[Palette.HELMET] = kit.helmet.toInt()
        it[Palette.SKIN] = FigurePainter.SKIN.toArgb(); it[Palette.BOOT] = FigurePainter.BOOT.toArgb()
        it[Palette.STEEL] = FigurePainter.BLADE.toArgb(); it[Palette.STICK] = FigurePainter.STICK.toArgb()
        it[Palette.TAPE] = FigurePainter.TAPE.toArgb(); it[Palette.PAD] = FigurePainter.PAD.toArgb()
        it[Palette.CAGE] = FigurePainter.CAGE.toArgb()
    }
}

/**
 * Jointed hockey figures, nets and the puck. Each figure is posed on its [Skeleton][com.personal.hockey.render.figure.Skeleton]
 * in its own frame (a metres forward along facing, b metres to its left, z metres up). Its joints
 * go to the world and its [SkaterBody] or [GoalieBody] meshes are lit, depth-sorted and sent to
 * [sink] in one batch, with the jersey number printed on the back over them. All scratch state is
 * preallocated, so drawing does not allocate; render thread only.
 */
class FigurePainter(private val sink: TriangleSink = TriangleSinks.default()) {
    /** Triangles added for the last figure drawn, before culling. */
    var lastTriangles = 0
        private set

    private lateinit var cam: Camera
    private val p = FloatArray(3)
    private val mesh = MeshPainter()

    // Pose in the figure frame, then in the world and projected to the screen, per joint slot.
    private val pose = Pose()
    /** The pose of the figure last posed or drawn, in its own frame; for tests. */
    internal val lastPose: Pose get() = pose
    private val la = pose.a
    private val lb = pose.b
    private val lz = pose.z
    private val wx = FloatArray(SLOTS)
    private val wy = FloatArray(SLOTS)
    private val wz = FloatArray(SLOTS)
    private val sx = FloatArray(SLOTS)
    private val sy = FloatArray(SLOTS)

    private var ox = 0f; private var oy = 0f
    private var fx = 1f; private var fy = 0f
    /** Lateral sign (b) of the side nearer the camera. */
    private var near = 1f

    private val path = Path()
    private val arrowPts = FloatArray(6)
    private val ringPath = Path().apply { fillType = PathFillType.EvenOdd }
    private val unitCos = FloatArray(RING_SEGMENTS) { cos(it * 2.0 * PI / RING_SEGMENTS).toFloat() }
    private val unitSin = FloatArray(RING_SEGMENTS) { sin(it * 2.0 * PI / RING_SEGMENTS).toFloat() }
    private val numberText = Array(100) { it.toString() }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val numberEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    /**
     * Maps the number's text box (0..[NUMBER_H] each way, y down) onto the projected back of the
     * figure last drawn; set each draw by [placeNumber].
     */
    internal val numberMatrix = Matrix()
    private val numberSrc = floatArrayOf(0f, 0f, NUMBER_H, 0f, 0f, NUMBER_H)
    private val numberDst = FloatArray(6)

    /** Poses [s] procedurally, lays [anim]'s clip over that, and draws it. */
    fun drawSkater(scope: DrawScope, camera: Camera, s: Skater, kit: KitColors, number: Int, anim: AnimState) {
        poseSkater(camera, s, anim)
        bodySlots()
        body(scope, camera, SkaterBody.parts, kit)
        number(scope, camera, kit, number, SKATER_BACK_M)
    }

    /**
     * Poses [s] procedurally and lays [anim]'s clip over that, without drawing; what [drawSkater] does
     * first. A clip that moves the stick but not the hands gets the hands put back on the stick.
     * The stickhandling sweep shows as far as [AnimState.sweepW], which eases in while carrying.
     * Posing a skater winding up records the pose in [AnimState.strikeFrom] for the shot's downswing.
     */
    fun poseSkater(camera: Camera, s: Skater, anim: AnimState) {
        begin(camera, s.x, s.y, s.facing)
        if (s.act == Act.DOWN) poseDown() else poseSkating(s, anim)
        blendPose(anim, pose, pose)
        val clip = ClipLibrary[anim.clip]
        if (s.act != Act.DOWN && anim.weight > 0f && clip != null && clip.handsFollowStick) holdStick()
        // Winding up, the stick is kept as drawn: a shot's downswing starts from it.
        anim.woundUp = s.act == Act.WINDUP
        if (anim.woundUp) copyPose(pose, anim.strikeFrom)
    }

    private fun copyPose(from: Pose, to: Pose) {
        from.a.copyInto(to.a); from.b.copyInto(to.b); from.z.copyInto(to.z)
    }

    /**
     * Fills [out] (x, y) with the world position of the middle of the blade of the skater last posed
     * or drawn, and returns true; returns false when that blade is more than [BLADE_ICE_Z] m above the ice.
     */
    fun bladeOnIce(out: FloatArray): Boolean {
        val a = (la[BLADE_HEEL] + la[BLADE_TOE]) / 2f
        val b = (lb[BLADE_HEEL] + lb[BLADE_TOE]) / 2f
        if ((lz[BLADE_HEEL] + lz[BLADE_TOE]) / 2f > BLADE_ICE_Z) return false
        out[0] = ox + a * fx - b * fy
        out[1] = oy + a * fy + b * fx
        return true
    }

    fun drawGoalie(scope: DrawScope, camera: Camera, g: Goalie, kit: KitColors, number: Int, anim: AnimState) {
        poseGoalie(camera, g, anim)
        bodySlots()
        body(scope, camera, GoalieBody.parts, kit)
        number(scope, camera, kit, number, GOALIE_BACK_M)
    }

    /**
     * Poses [g] in its ready or save pose and lays [anim]'s clip over that, without drawing; what
     * [drawGoalie] does first. A goalie clip keys the hands and the stick's ends; the stick's middle
     * and the elbows are put back between them after blending.
     */
    fun poseGoalie(camera: Camera, g: Goalie, anim: AnimState) {
        begin(camera, g.x, g.y, if (g.side.dir > 0f) 0f else PI.toFloat())
        poseGoalie(armsOut = g.saveFlash < SAVE_FLASH_S)
        blendPose(anim, pose, pose)
        if (anim.weight > 0f && ClipLibrary[anim.clip] != null) {
            lerpSlot(STICK_MID, STICK_TOP, BLADE_HEEL, GOALIE_STICK_MID)
            elbows()
        }
    }

    /** The posed figure's [parts], lit, depth-sorted and sent to [sink] in one batch. */
    private fun body(scope: DrawScope, camera: Camera, parts: Array<Part>, kit: KitColors) {
        val leftX = -fy; val leftY = fx
        for (i in 0 until SLOTS) {
            wx[i] = ox + la[i] * fx + lb[i] * leftX
            wy[i] = oy + la[i] * fy + lb[i] * leftY
            wz[i] = lz[i]
        }
        mesh.begin(camera)
        for (k in parts.indices) mesh.add(parts[k], wx, wy, wz, leftX, leftY, kit.palette)
        lastTriangles = mesh.added
        sink.begin()
        mesh.emit(sink)
        sink.flush(scope)
    }

    /**
     * The puck at (x, y), [z] m above the ice, drawn larger than life so it reads on a phone: a black
     * disc inside a light rim inside a dark rim. The dark rim reads on white ice, the light one against
     * dark kits. On the ice it sits in a soft contact shadow; lifted, [drawPuckShadow] marks the ice.
     */
    fun drawPuckAt(scope: DrawScope, camera: Camera, x: Float, y: Float, z: Float = 0f) {
        camera.project(x, y, z, p)
        val cx = p[0]; val cy = p[1]; val s = p[2]
        val squash = squashAt(camera, x, y, z, cy, s)
        val rx = PUCK_DRAW_R * s
        val ry = rx * squash
        val th = PUCK_THICK * s
        val e = max(1.5f, PUCK_RIM * s)
        if (z <= PUCK_LIFTED_Z) {
            scope.drawOval(SHADOW, Offset(cx - rx * 1.3f + 0.05f * s, cy - ry * 1.3f), Size(rx * 2.6f, ry * 2.6f), alpha = 0.3f)
        }
        puckShape(scope, cx, cy, rx + 2f * e, ry + 2f * e, th, OUTLINE, OUTLINE)
        puckShape(scope, cx, cy, rx + e, ry + e, th, PUCK_RIM_LIGHT, PUCK_RIM_LIGHT)
        puckShape(scope, cx, cy, rx, ry, th, PUCK_SIDE, PUCK_TOP)
    }

    /**
     * The dark spot on the ice at (x, y) under a puck [z] m up: radius [PUCK_SHADOW_R], fading from
     * [PUCK_SHADOW_ALPHA_ICE] with the puck on the ice to [PUCK_SHADOW_ALPHA_HIGH] at [PUCK_SHADOW_HIGH_Z].
     */
    fun drawPuckShadow(scope: DrawScope, camera: Camera, x: Float, y: Float, z: Float) {
        camera.project(x, y, 0f, p)
        val cx = p[0]; val cy = p[1]; val s = p[2]
        val rx = PUCK_SHADOW_R * s
        val ry = rx * squashAt(camera, x, y, 0f, cy, s)
        val f = (z / PUCK_SHADOW_HIGH_Z).coerceIn(0f, 1f)
        val alpha = PUCK_SHADOW_ALPHA_ICE + (PUCK_SHADOW_ALPHA_HIGH - PUCK_SHADOW_ALPHA_ICE) * f
        scope.drawOval(SHADOW, Offset(cx - rx, cy - ry), Size(2f * rx, 2f * ry), alpha = alpha)
    }

    /** A puck-shaped cylinder seen from above: bottom ellipse, side, top ellipse [th] higher. */
    private fun puckShape(scope: DrawScope, cx: Float, cy: Float, rx: Float, ry: Float, th: Float, side: Color, top: Color) {
        scope.drawOval(side, Offset(cx - rx, cy - ry), Size(2f * rx, 2f * ry))
        scope.drawRect(side, Offset(cx - rx, cy - th), Size(2f * rx, th))
        scope.drawOval(top, Offset(cx - rx, cy - ry - th), Size(2f * rx, 2f * ry))
    }

    /**
     * A pass-yellow arrow with a dark outline whose tip is the screen point (x, y), pointing along
     * [angle] (radians, screen axes), sized for [scale] px per metre. The body
     * trails back toward the screen centre, so the whole shape stays inside the viewport when the tip
     * is on an inset edge point.
     */
    fun drawEdgeArrow(scope: DrawScope, x: Float, y: Float, angle: Float, scale: Float) {
        for (pass in 0..1) {
            edgeArrowCorners(x, y, angle, scale, pass == 0, arrowPts)
            path.reset()
            path.moveTo(arrowPts[0], arrowPts[1])
            path.lineTo(arrowPts[2], arrowPts[3])
            path.lineTo(arrowPts[4], arrowPts[5])
            path.close()
            scope.drawPath(path, if (pass == 0) CanvasRenderer.PASS_OUTLINE else CanvasRenderer.PASS_YELLOW)
        }
    }

    /** The farthest any corner of the outlined edge arrow at [scale] px per metre lies from its tip point, px. */
    internal fun edgeArrowReach(scale: Float): Float {
        val e = max(1.5f, MARKER_EDGE * scale)
        return hypot(MARKER_H * scale + e, MARKER_HALF_W * scale + e * 1.6f)
    }

    /** Fills [out] with the 3 corners (x, y pairs) of the arrow, grown by its outline when [outline]. */
    internal fun edgeArrowCorners(x: Float, y: Float, angle: Float, scale: Float, outline: Boolean, out: FloatArray) {
        val c = cos(angle); val s = sin(angle)
        val e = if (outline) max(1.5f, MARKER_EDGE * scale) else 0f
        val tip = e * 1.6f
        val back = -MARKER_H * scale - e
        val hw = MARKER_HALF_W * scale + e * 1.6f
        out[0] = x + c * tip; out[1] = y + s * tip
        out[2] = x + c * back - s * hw; out[3] = y + s * back + c * hw
        out[4] = x + c * back + s * hw; out[5] = y + s * back - c * hw
    }

    /**
     * A ring of radius [radius] m and band width [band] m lying on the ice at (x, y), its inside
     * washed with [colour] at [fillAlpha].
     */
    fun drawGroundRing(scope: DrawScope, camera: Camera, x: Float, y: Float, radius: Float, band: Float, colour: Color, alpha: Float, fillAlpha: Float) {
        if (fillAlpha > 0f) {
            ringPath.reset()
            circle(camera, x, y, radius)
            scope.drawPath(ringPath, colour, fillAlpha)
        }
        ringPath.reset()
        circle(camera, x, y, radius + band / 2f)
        circle(camera, x, y, radius - band / 2f)
        scope.drawPath(ringPath, colour, alpha)
    }

    /**
     * The parts of the net on the goal line at x = [goalX] that lie behind a puck inside it: the far
     * side, back and roof mesh, the far post, the crossbar, and the goal light on top. [outward] is +1
     * if the net extends toward +x. [lit] > 0 turns the goal light on and flashes the mesh red.
     *
     * The camera always looks from the mouth side, so a puck inside the net is seen through the open
     * mouth or the near side mesh and is never behind the roof: drawing the net in these two halves,
     * with the puck between them, shows it inside.
     */
    fun drawNetBack(scope: DrawScope, camera: Camera, goalX: Float, outward: Float, lit: Boolean, timeS: Float) {
        netCorners(camera, goalX, outward)
        val s = p[2]
        panel(scope, N_FB_F, N_FT_F, N_TB_F, N_BB_F, MESH_IN, 0.95f)
        panel(scope, N_TB_N, N_TB_F, N_BB_F, N_BB_N, MESH_IN, 0.95f)
        panel(scope, N_FT_N, N_FT_F, N_TB_F, N_TB_N, MESH, 0.9f)
        val flash = if (lit) GOAL_FLASH_MIN + (1f - GOAL_FLASH_MIN) * abs(sin(timeS * GOAL_FLASH_RATE)) else 0f
        if (flash > 0f) {
            panel(scope, N_FB_F, N_FT_F, N_TB_F, N_BB_F, LAMP_ON, GOAL_FLASH_ALPHA * flash)
            panel(scope, N_TB_N, N_TB_F, N_BB_F, N_BB_N, LAMP_ON, GOAL_FLASH_ALPHA * flash)
            panel(scope, N_FT_N, N_FT_F, N_TB_F, N_TB_N, LAMP_ON, GOAL_FLASH_ALPHA * flash)
        }
        val lw = max(1f, MESH_LINE * s)
        for (i in 1 until MESH_LINES) {
            val t = i / MESH_LINES.toFloat()
            meshLine(scope, N_TB_N, N_TB_F, N_BB_N, N_BB_F, t, lw)
            meshLine(scope, N_TB_N, N_BB_N, N_TB_F, N_BB_F, t, lw)
            meshLine(scope, N_FB_F, N_FT_F, N_BB_F, N_TB_F, t, lw)
        }
        val base = max(1f, 0.05f * s)
        segment(scope, N_BB_N, N_BB_F, MESH_FRAME, base)
        segment(scope, N_BB_F, N_FB_F, MESH_FRAME, base)
        val post = POST_W * s
        val edge = max(1f, 0.025f * s)
        for (pass in 0..1) {
            val c = if (pass == 0) OUTLINE else POST_RED
            val wid = if (pass == 0) post + 2f * edge else post
            segment(scope, N_FB_F, N_FT_F, c, wid)
            segment(scope, N_FT_F, N_FT_N, c, wid)
        }
        goalLight(scope, camera, goalX, outward, flash)
    }

    /**
     * The near side's mesh strands, its base and the near post: in front of a puck inside the net.
     * Faint strands only, no wash, so a puck behind them still reads black.
     */
    fun drawNetFront(scope: DrawScope, camera: Camera, goalX: Float, outward: Float) {
        netCorners(camera, goalX, outward)
        val s = p[2]
        val lw = max(1f, MESH_LINE * s)
        for (i in 1 until MESH_LINES) meshLine(scope, N_FB_N, N_FT_N, N_BB_N, N_TB_N, i / MESH_LINES.toFloat(), lw, NEAR_STRAND_ALPHA)
        segment(scope, N_FB_N, N_BB_N, MESH_FRAME, max(1f, 0.05f * s))
        val post = POST_W * s
        val edge = max(1f, 0.025f * s)
        segment(scope, N_FT_N, N_FB_N, OUTLINE, post + 2f * edge)
        segment(scope, N_FT_N, N_FB_N, POST_RED, post)
    }

    /**
     * Projects the net's corners into the net slots and leaves the goal line's centre projected in p.
     * The base matches the sim's net box (GOAL_HALF_W wide, NET_DEPTH deep); the roof slopes down.
     */
    private fun netCorners(camera: Camera, goalX: Float, outward: Float) {
        cam = camera
        val w = GOAL_HALF_W; val hgt = NET_H; val d = NET_DEPTH
        netPoint(N_FB_N, goalX, -w, 0f); netPoint(N_FT_N, goalX, -w, hgt)
        netPoint(N_FB_F, goalX, w, 0f); netPoint(N_FT_F, goalX, w, hgt)
        netPoint(N_TB_N, goalX + outward * d * 0.45f, -w * 0.92f, hgt * 0.93f)
        netPoint(N_TB_F, goalX + outward * d * 0.45f, w * 0.92f, hgt * 0.93f)
        netPoint(N_BB_N, goalX + outward * d, -w, 0f)
        netPoint(N_BB_F, goalX + outward * d, w, 0f)
        camera.project(goalX, 0f, 0f, p)
    }

    /** A red lamp on a short stem above the back of the net: dark until [flash] > 0, then glowing. */
    private fun goalLight(scope: DrawScope, camera: Camera, goalX: Float, outward: Float, flash: Float) {
        val lx = goalX + outward * NET_DEPTH * 0.6f
        camera.project(lx, 0f, NET_H * 0.85f, p)
        val bx = p[0]; val by = p[1]
        camera.project(lx, 0f, NET_H + LAMP_LIFT, p)
        val cx = p[0]; val cy = p[1]; val s = p[2]
        val r = LAMP_R * s
        val e = max(1f, OUTLINE_M * s)
        line(scope, bx, by, cx, cy, OUTLINE, max(1f, 0.08f * s), StrokeCap.Butt)
        if (flash > 0f) {
            for (k in LAMP_HALO.indices) scope.drawCircle(LAMP_ON, r * LAMP_HALO[k], Offset(cx, cy), alpha = LAMP_HALO_ALPHA[k] * flash)
        }
        scope.drawRect(OUTLINE, Offset(cx - r - e, cy), Size(2f * (r + e), 0.45f * r + e))
        scope.drawCircle(OUTLINE, r + e, Offset(cx, cy))
        scope.drawCircle(if (flash > 0f) LAMP_ON else LAMP_OFF, r, Offset(cx, cy))
        if (flash > 0f) scope.drawCircle(LAMP_CORE, 0.4f * r, Offset(cx - 0.25f * r, cy - 0.3f * r))
    }

    // ---- posing -------------------------------------------------------------------------------

    private fun begin(camera: Camera, x: Float, y: Float, facing: Float) {
        cam = camera
        ox = x; oy = y
        fx = cos(facing); fy = sin(facing)
        // The figure's left (+b) is world (-fy, fx); its y component fx > 0 means left is farther.
        near = if (fx > 0f) -1f else 1f
    }

    private fun set(i: Int, a: Float, b: Float, z: Float) { la[i] = a; lb[i] = b; lz[i] = z }

    private fun lerpSlot(i: Int, from: Int, to: Int, t: Float) =
        set(i, la[from] + (la[to] - la[from]) * t, lb[from] + (lb[to] - lb[from]) * t, lz[from] + (lz[to] - lz[from]) * t)

    private fun poseSkating(s: Skater, anim: AnimState) {
        val speed = sqrt(s.vx * s.vx + s.vy * s.vy)
        val sp = (speed / Tuning.MAX_SPEED).coerceIn(0f, 1f)
        val phase = s.stride * TWO_PI / STRIDE_M
        // Gliding (stick released), both skates ride the ice under a lower, more forward-leaning body.
        val glide = anim.glideW
        var hipA = 0f; var hipB = 0f
        var hipZ = HIP_Z - GLIDE_DROP * glide - 0.03f * sp * abs(cos(phase)) * (1f - glide)
        var rise = TORSO_RISE
        var lean = 0.16f + 0.2f * sp + GLIDE_LEAN * glide
        var shB = 0f
        var amp = 0.05f + 0.22f * sp   // fore-aft half-travel of a skate through its stride
        var push = 0f
        var lift = 0f
        // Crossover: fast and turning hard, the outside skate recovers across the inside one.
        val turning = smoothstep(CROSS_TURN, CROSS_TURN + 1f, abs(anim.turnRate)) * smoothstep(CROSS_SPEED, CROSS_SPEED + 1f, speed)
        val inside = if (anim.turnRate > 0f) 1f else -1f   // the side (sign of b) the turn goes to
        val stopW = anim.stopW
        when (s.act) {
            Act.BODY -> { hipZ -= 0.14f; lean = 0.48f; push = 0.2f }
            Act.RECOVER -> { hipZ -= 0.17f; rise = 0.38f; lean = 0.45f; amp *= 0.5f }
            Act.POKE -> lean = 0.4f
            Act.WINDUP -> { lean = 0.04f; lift = slapLift(s.windup, anim); hipZ -= SLAP_CROUCH * lift }
            Act.DEKE -> {
                // Lean hard toward where the skater is sliding; facing is frozen during a deke.
                var va = 0f; var vb = 1f
                if (speed > 0.5f) { va = (s.vx * fx + s.vy * fy) / speed; vb = (-s.vx * fy + s.vy * fx) / speed }
                lean += 0.35f * va; shB = 0.42f * vb; hipB = 0.12f * vb; hipA = 0.05f * va
            }
            else -> {}
        }
        if (turning > 0f) shB += CROSS_LEAN * turning * inside * (1f - stopW)
        if (stopW > 0f) { hipZ -= STOP_DROP * stopW; lean = mix(lean, STOP_LEAN, stopW) }
        // Toes point to the side the stick pushes to, so right-side stops are not mirrored.
        val toeDir = if (fx * s.input.moveY - fy * s.input.moveX >= 0f) 1f else -1f
        // How far out to the side a push reaches: full from a third of top speed; a crossover steps
        // across from a narrow stance instead.
        val reach = smoothstep(0f, PUSH_FULL_SP, sp) * (1f - turning)
        for (side in 0..1) {
            val sigma = if (side == 0) 1f else -1f
            val ph = if (side == 0) phase else phase + PI.toFloat()
            // Push (ph 0..π): on the ice, the skate travels from under the hip, ahead, out diagonally to
            // full extension, slightly behind. Recovery (π..2π): lifted a little, it swings back under the hip.
            val c = cos(ph); val sn = sin(ph)
            val out = (1f - c) / 2f * reach
            val rec = if (sn < 0f) -sn else 0f
            var fa = hipA + amp * c
            var fb = hipB + sigma * (STANCE_B + (PUSH_OUT - STANCE_B) * out)
            var fz = STRIDE_LIFT * sp * rec
            if (turning > 0f && sigma == -inside) {
                fb += inside * CROSS_REACH * turning * rec
                fz += CROSS_LIFT * turning * rec   // high enough to step over the inside skate
            }
            var toeOut = TOE_OUT * out   // radians the toe turns out as the skate pushes
            if (glide > 0f) {
                fa = mix(fa, hipA, glide); fb = mix(fb, hipB + sigma * STANCE_B, glide)
                fz *= 1f - glide; toeOut *= 1f - glide
            }
            val hip = if (side == 0) HIP_L else HIP_R
            set(hip, hipA, hipB + sigma * 0.11f, hipZ)
            set(hip + 1, (hipA + fa) / 2f + 0.17f, (hipB + sigma * 0.11f + fb) / 2f, (hipZ + fz) / 2f + 0.04f)
            set(hip + 2, fa, fb, fz + 0.13f)
            set(hip + 3, fa - 0.13f, fb - sigma * 0.13f * toeOut, fz + 0.06f)
            set(hip + 4, fa + 0.19f, fb + sigma * 0.19f * toeOut, fz + 0.04f)
            if (stopW > 0f) {
                // Skates turn side-on to the way it is sliding and plant, eased in by stopW.
                val sb = hipB + sigma * STOP_STANCE
                stopSlot(hip + 1, stopW, hipA + 0.17f, hipB + sigma * (STOP_STANCE + 0.11f) / 2f, (hipZ + 0.13f) / 2f + 0.04f)
                stopSlot(hip + 2, stopW, hipA, sb, 0.13f)
                stopSlot(hip + 3, stopW, hipA, sb - toeDir * 0.13f, 0.06f)
                stopSlot(hip + 4, stopW, hipA, sb + toeDir * 0.19f, 0.04f)
            }
        }
        val shA = hipA + lean
        val shZ = hipZ + rise
        set(HIP_C, hipA, hipB, hipZ)
        // Stickhandling: the blade sweeps forehand to backhand, the shoulders turning against it; eased
        // in and out by sweepW, so a pickup, release, wind-up or deke never snaps the blade sideways.
        val sweep = sweepOffset(anim, speed)
        val u = sin(anim.sweepPhase)
        val roll = u * u * u * anim.sweepW
        val sweepShB = SWEEP_SHOULDER * sweep
        set(SH_C, shA, hipB + shB + sweepShB, shZ)
        set(SH_L, shA + (if (near > 0f) push else 0f), hipB + shB + sweepShB + SHOULDER_B, shZ - 0.02f)
        set(SH_R, shA + (if (near < 0f) push else 0f), hipB + shB + sweepShB - SHOULDER_B, shZ - 0.02f)
        set(HEAD, shA + 0.07f + 0.15f * lean, hipB + shB * 1.1f, shZ + 0.25f)   // the head stays level
        set(HEAD_FWD, la[HEAD] + 1f, lb[HEAD], lz[HEAD])

        // Stick: blade on the ice under the puck, 0.9 m ahead, rolling over at the ends of its sweep;
        // the top hand just in front of the hips.
        set(STICK_TOP, hipA + 0.12f, hipB, hipZ + 0.22f)
        set(BLADE_HEEL, CARRY_A - 0.17f, sweep, 0.02f + BLADE_ROLL_LIFT * abs(roll))
        set(BLADE_TOE, CARRY_A + 0.17f, sweep + 0.03f, 0.02f)
        val dekeW = anim.dekeW
        if (dekeW > 0f) {
            // Deking, the blade eases over toward the side the skater leans to.
            val db = DEKE_BLADE * lb[SH_C].coerceIn(-1f, 1f)
            set(BLADE_HEEL, CARRY_A - 0.17f, mix(lb[BLADE_HEEL], db, dekeW), mix(lz[BLADE_HEEL], 0.02f, dekeW))
            set(BLADE_TOE, CARRY_A + 0.17f, mix(lb[BLADE_TOE], db + 0.03f, dekeW), 0.02f)
        }
        when (s.act) {
            Act.WINDUP -> {
                // From the carry, the blade draws back low beside the back hip (wrist shot); past the
                // slap threshold it morphs into the stick lifted high behind the shoulder.
                val d = smoothstep(0f, DRAW_BACK_S, s.windup)
                windupSlot(STICK_TOP, d, lift, hipA + 0.18f, hipB - 0.05f, hipZ + 0.28f, hipA + 0.12f, hipB + 0.08f, hipZ + 0.4f)
                windupSlot(BLADE_HEEL, d, lift, -0.35f, 0.42f, 0.14f, -0.7f, 0.42f, 1.95f)
                windupSlot(BLADE_TOE, d, lift, -0.19f, 0.72f, 0.16f, -0.85f, 0.55f, 2.22f)
            }
            Act.POKE -> {
                set(STICK_TOP, hipA + lean + 0.3f, hipB, hipZ + 0.12f)
                set(BLADE_HEEL, POKE_REACH - 0.32f, 0f, 0.02f)
                set(BLADE_TOE, POKE_REACH, 0f, 0.02f)
            }
            else -> {}
        }
        holdStick()
    }

    private fun stopSlot(i: Int, w: Float, a: Float, b: Float, z: Float) =
        set(i, mix(la[i], a, w), mix(lb[i], b, w), mix(lz[i], z, w))

    /**
     * How far a wind-up has gone from the wrist draw-back to the slap lift, 0..1: through the slap
     * threshold on the wind-up time, or straight to the lift while a pass just received makes this a
     * one-timer (easing back down as that window closes).
     */
    private fun slapLift(windup: Float, anim: AnimState): Float {
        val slap = smoothstep(SLAP_WINDUP - 0.05f, SLAP_WINDUP + 0.1f, windup)
        if (!anim.pickupFromPass) return slap
        return max(slap, 1f - smoothstep(ONE_TIMER_S - ONE_TIMER_EASE_S, ONE_TIMER_S, anim.sincePickup))
    }

    /** Moves slot [i] by [d] from where it is toward the draw-back (wa, wb, wz) morphed by [lift] into the lift (sa, sb, sz). */
    private fun windupSlot(i: Int, d: Float, lift: Float, wa: Float, wb: Float, wz: Float, sa: Float, sb: Float, sz: Float) =
        set(i, mix(la[i], mix(wa, sa, lift), d), mix(lb[i], mix(wb, sb, lift), d), mix(lz[i], mix(wz, sz, lift), d))

    /** The slots a skater's meshes hang on that follow from the pose: the pants along the torso axis round the hips. */
    private fun bodySlots() {
        val ax = la[SH_C] - la[HIP_C]; val bx = lb[SH_C] - lb[HIP_C]; val zx = lz[SH_C] - lz[HIP_C]
        val len = sqrt(ax * ax + bx * bx + zx * zx)
        val ua = ax / len; val ub = bx / len; val uz = zx / len
        set(PANTS_TOP, la[HIP_C] + PANTS_UP * ua, lb[HIP_C] + PANTS_UP * ub, lz[HIP_C] + PANTS_UP * uz)
        set(PANTS_BOT, la[HIP_C] - PANTS_DOWN * ua, lb[HIP_C] - PANTS_DOWN * ub, lz[HIP_C] - PANTS_DOWN * uz)
    }

    /** Hands on the stick: the far arm holds the top, the near arm the shaft lower down. */
    private fun holdStick() {
        val top = if (near > 0f) HAND_R else HAND_L
        val low = if (near > 0f) HAND_L else HAND_R
        set(top, la[STICK_TOP], lb[STICK_TOP], lz[STICK_TOP] - 0.05f)
        lerpSlot(low, STICK_TOP, BLADE_HEEL, 0.38f)
        lerpSlot(STICK_MID, STICK_TOP, BLADE_HEEL, 0.6f)
        elbows()
    }

    private fun elbows() {
        for (side in 0..1) {
            val sigma = if (side == 0) 1f else -1f
            val sh = if (side == 0) SH_L else SH_R
            val hand = if (side == 0) HAND_L else HAND_R
            set(sh + 1, (la[sh] + la[hand]) / 2f - 0.07f, (lb[sh] + lb[hand]) / 2f + sigma * 0.1f, (lz[sh] + lz[hand]) / 2f - 0.08f)
        }
    }

    /** Lying on the ice after a check, fallen forward, stick dropped beside. */
    private fun poseDown() {
        for (side in 0..1) {
            val sigma = if (side == 0) 1f else -1f
            val hip = if (side == 0) HIP_L else HIP_R
            set(hip, -0.12f, sigma * 0.12f, 0.15f)
            set(hip + 1, -0.5f, sigma * 0.16f, 0.17f)
            set(hip + 2, -0.85f, sigma * 0.17f, 0.12f)
            set(hip + 3, -0.86f, sigma * 0.17f, 0.24f)   // face down: soles up, toes on the ice
            set(hip + 4, -0.98f, sigma * 0.17f, 0.03f)
            set(if (side == 0) SH_L else SH_R, 0.45f, sigma * 0.2f, 0.2f)
            set(if (side == 0) HAND_L else HAND_R, 0.78f, sigma * 0.38f, 0.06f)
        }
        set(HIP_C, -0.12f, 0f, 0.16f)
        set(SH_C, 0.45f, 0f, 0.2f)
        set(HEAD, 0.8f, 0f, 0.17f)
        set(HEAD_FWD, 0.8f, 0f, -0.83f)   // face down on the ice, the top of the helmet forward
        set(STICK_TOP, -0.3f, near * 0.6f, 0.03f)
        set(BLADE_HEEL, 0.9f, near * 0.62f, 0.02f)
        set(BLADE_TOE, 1.2f, near * 0.66f, 0.02f)
        lerpSlot(STICK_MID, STICK_TOP, BLADE_HEEL, 0.6f)
        elbows()
    }

    /** Squatting goalie facing out of the net; gloves wide, or flung out just after a save. */
    private fun poseGoalie(armsOut: Boolean) {
        for (side in 0..1) {
            val sigma = if (side == 0) 1f else -1f
            val hip = if (side == 0) HIP_L else HIP_R
            set(hip, 0f, sigma * 0.16f, 0.62f)
            set(hip + 1, 0.22f, sigma * 0.22f, 0.36f)
            set(hip + 2, 0.08f, sigma * 0.44f, 0.12f)
            set(hip + 3, -0.05f, sigma * 0.44f, 0.05f)
            set(hip + 4, 0.2f, sigma * 0.44f, 0.04f)
            set(if (side == 0) SH_L else SH_R, 0.2f, sigma * 0.25f, 1.08f)
        }
        set(HIP_C, 0f, 0f, 0.62f)
        set(SH_C, 0.2f, 0f, 1.1f)
        set(HEAD, 0.25f, 0f, 1.38f)
        set(HEAD_FWD, la[HEAD] + 1f, lb[HEAD], lz[HEAD])
        // Glove on the left hand, blocker and stick in the right.
        if (armsOut) {
            set(HAND_L, 0.25f, 0.88f, 1.3f)
            set(HAND_R, 0.28f, -0.82f, 1.02f)
            set(BLADE_HEEL, 0.5f, -0.6f, 0.02f)
            set(BLADE_TOE, 0.5f, -0.22f, 0.02f)
        } else {
            set(HAND_L, 0.42f, 0.46f, 0.78f)
            set(HAND_R, 0.44f, -0.42f, 0.6f)
            set(BLADE_HEEL, 0.62f, -0.18f, 0.02f)
            set(BLADE_TOE, 0.62f, 0.3f, 0.02f)
        }
        set(STICK_TOP, la[HAND_R], lb[HAND_R], lz[HAND_R] + 0.12f)
        lerpSlot(STICK_MID, STICK_TOP, BLADE_HEEL, GOALIE_STICK_MID)
        elbows()
    }

    // ---- drawing parts ------------------------------------------------------------------------

    /**
     * The jersey number printed on the back, turning and tilting with the torso; it fades out as the
     * figure turns side-on and is not drawn at all facing the camera ([backNumberAlpha]). Drawn after
     * the mesh, with a contrasting edge (dark round a light number, light round a dark one) so it
     * reads on the jersey. [backM] is how far behind the spine the jersey's back is, metres.
     */
    private fun number(scope: DrawScope, camera: Camera, kit: KitColors, number: Int, backM: Float) {
        if (number !in numberText.indices) return   // hockey numbers are 0..99; others are not drawn
        val alpha = backNumberAlpha(camera)
        if (alpha < 0.01f) return
        val pxPerUnit = placeNumber(camera, backM)
        val size = NUMBER_TEXT * NUMBER_H
        val fill = kit.number
        val light = 0.2126f * fill.red + 0.7152f * fill.green + 0.0722f * fill.blue > 0.5f
        numberPaint.textSize = size
        numberPaint.color = fill.toArgb()   // .color must be set before .alpha: setColor resets alpha
        numberPaint.alpha = (numberPaint.alpha * alpha).roundToInt()
        numberEdgePaint.textSize = size
        numberEdgePaint.color = (if (light) OUTLINE else NUMBER_LIGHT_EDGE).toArgb()
        numberEdgePaint.alpha = (numberEdgePaint.alpha * alpha).roundToInt()
        numberEdgePaint.strokeWidth = max(NUMBER_EDGE * size, NUMBER_EDGE_MIN_PX / max(pxPerUnit, 1e-3f))
        val canvas = scope.drawContext.canvas.nativeCanvas
        val text = numberText[number]
        // Centred across the box, the digits' height (about 0.72 of the text size) centred down it.
        val x = NUMBER_H / 2f
        val baseY = NUMBER_H / 2f + 0.36f * size
        canvas.save()
        canvas.concat(numberMatrix)
        canvas.drawText(text, x, baseY, numberEdgePaint)
        canvas.drawText(text, x, baseY, numberPaint)
        canvas.restore()
    }

    /**
     * How squarely the back of the figure last posed faces [camera], as the number's alpha: the back
     * normal (-facing, level) dotted with the unit vector from the torso's centre to the eye, eased
     * from 0 at [NUMBER_FADE_0] to 1 at [NUMBER_FADE_1].
     */
    internal fun backNumberAlpha(camera: Camera): Float {
        val a = (la[HIP_C] + la[SH_C]) / 2f
        val b = (lb[HIP_C] + lb[SH_C]) / 2f
        val dx = camera.eyeX - (ox + a * fx - b * fy)
        val dy = camera.eyeY - (oy + a * fy + b * fx)
        val dz = camera.eyeZ - (lz[HIP_C] + lz[SH_C]) / 2f
        val d = (-fx * dx - fy * dy) / sqrt(dx * dx + dy * dy + dz * dz)
        return smoothstep(NUMBER_FADE_0, NUMBER_FADE_1, d)
    }

    /**
     * Sets [numberMatrix] so the text box lands on the back of the figure last posed: centred
     * [NUMBER_UP] of the way from the hips to the shoulders and [backM] + [NUMBER_PROUD_M] behind the
     * spine, [NUMBER_W_M] across the shoulders' axis and [NUMBER_H_M] up the spine. Text-space left is
     * the figure's left shoulder side, which is on the left seen from behind. Returns the screen pixels
     * per text unit (the longer of the box's projected sides), for a minimum stroke width.
     */
    private fun placeNumber(camera: Camera, backM: Float): Float {
        // Spine (up the torso) and shoulder (toward the figure's left) unit axes, in the figure frame.
        var ua = la[SH_C] - la[HIP_C]; var ub = lb[SH_C] - lb[HIP_C]; var uz = lz[SH_C] - lz[HIP_C]
        var len = sqrt(ua * ua + ub * ub + uz * uz)
        ua /= len; ub /= len; uz /= len
        var sa = la[SH_L] - la[SH_R]; var sb = lb[SH_L] - lb[SH_R]; var sz = lz[SH_L] - lz[SH_R]
        val along = sa * ua + sb * ub + sz * uz
        sa -= along * ua; sb -= along * ub; sz -= along * uz
        len = sqrt(sa * sa + sb * sb + sz * sz)
        if (len < 1e-4f) { sa = 0f; sb = 1f; sz = 0f } else { sa /= len; sb /= len; sz /= len }
        // Back = spine × shoulder axis: for an upright figure, straight behind it.
        val na = ub * sz - uz * sb; val nb = uz * sa - ua * sz; val nz = ua * sb - ub * sa
        val back = backM + NUMBER_PROUD_M
        val ca = mix(la[HIP_C], la[SH_C], NUMBER_UP) + na * back
        val cb = mix(lb[HIP_C], lb[SH_C], NUMBER_UP) + nb * back
        val cz = mix(lz[HIP_C], lz[SH_C], NUMBER_UP) + nz * back
        val hw = NUMBER_W_M / 2f; val hh = NUMBER_H_M / 2f
        numberCorner(camera, 0, ca + sa * hw + ua * hh, cb + sb * hw + ub * hh, cz + sz * hw + uz * hh)   // text (0, 0)
        numberCorner(camera, 2, ca - sa * hw + ua * hh, cb - sb * hw + ub * hh, cz - sz * hw + uz * hh)   // text (H, 0)
        numberCorner(camera, 4, ca + sa * hw - ua * hh, cb + sb * hw - ub * hh, cz + sz * hw - uz * hh)   // text (0, H)
        numberMatrix.setPolyToPoly(numberSrc, 0, numberDst, 0, 3)
        val across = hypot(numberDst[2] - numberDst[0], numberDst[3] - numberDst[1])
        val down = hypot(numberDst[4] - numberDst[0], numberDst[5] - numberDst[1])
        return max(across, down) / NUMBER_H
    }

    /** Projects figure-frame point (a, b, z) into [numberDst] at [i]. */
    private fun numberCorner(camera: Camera, i: Int, a: Float, b: Float, z: Float) {
        camera.project(ox + a * fx - b * fy, oy + a * fy + b * fx, z, p)
        numberDst[i] = p[0]; numberDst[i + 1] = p[1]
    }

    // ---- primitives ---------------------------------------------------------------------------

    private fun line(scope: DrawScope, x0: Float, y0: Float, x1: Float, y1: Float, colour: Color, width: Float, cap: StrokeCap = StrokeCap.Round) =
        scope.drawLine(colour, Offset(x0, y0), Offset(x1, y1), width, cap)

    private fun segment(scope: DrawScope, i: Int, j: Int, colour: Color, width: Float) =
        line(scope, sx[i], sy[i], sx[j], sy[j], colour, width)

    private fun netPoint(i: Int, x: Float, y: Float, z: Float) {
        cam.project(x, y, z, p)
        sx[i] = p[0]; sy[i] = p[1]
    }

    private fun panel(scope: DrawScope, a: Int, b: Int, c: Int, d: Int, colour: Color, alpha: Float) {
        path.reset()
        path.moveTo(sx[a], sy[a]); path.lineTo(sx[b], sy[b]); path.lineTo(sx[c], sy[c]); path.lineTo(sx[d], sy[d])
        path.close()
        scope.drawPath(path, colour, alpha)
    }

    /** A mesh strand across the quad: from t along edge a→b to t along edge c→d. */
    private fun meshLine(scope: DrawScope, a: Int, b: Int, c: Int, d: Int, t: Float, width: Float, alpha: Float = 1f) {
        scope.drawLine(MESH_LINE_COLOUR, Offset(sx[a] + (sx[b] - sx[a]) * t, sy[a] + (sy[b] - sy[a]) * t),
            Offset(sx[c] + (sx[d] - sx[c]) * t, sy[c] + (sy[d] - sy[c]) * t), width, StrokeCap.Butt, alpha = alpha)
    }

    private fun circle(camera: Camera, x: Float, y: Float, r: Float) {
        for (i in 0 until RING_SEGMENTS) {
            camera.project(x + r * unitCos[i], y + r * unitSin[i], 0f, p)
            if (i == 0) ringPath.moveTo(p[0], p[1]) else ringPath.lineTo(p[0], p[1])
        }
        ringPath.close()
    }

    /** Ratio of a metre's screen length along y to one along x, at (x, y) in the plane [z] m above the ice. */
    private fun squashAt(camera: Camera, x: Float, y: Float, z: Float, cy: Float, s: Float): Float {
        camera.project(x, y + 1f, z, p)
        return (cy - p[1]) / s
    }

    internal companion object {
        // Net corners reuse the skeleton's slots: front bottom/top, top back, bottom back; near and far.
        const val N_FB_N = 0; const val N_FT_N = 1; const val N_FB_F = 2; const val N_FT_F = 3
        const val N_TB_N = 4; const val N_TB_F = 5; const val N_BB_N = 6; const val N_BB_F = 7

        const val TWO_PI = (2.0 * PI).toFloat()
        const val STRIDE_M = 3.6f        // metres skated per full two-leg stride cycle
        const val STRIDE_LIFT = 0.05f    // m the recovering skate lifts at top speed
        /** m from the hips' middle a pushing skate reaches at full extension, and where a skate rests or glides. */
        const val PUSH_OUT = 0.38f; const val STANCE_B = 0.17f
        /** Fraction of top speed from which a push reaches all the way out. */
        const val PUSH_FULL_SP = 0.35f
        /** Radians a pushing skate's toe turns out at full extension. */
        const val TOE_OUT = 0.4f
        /** Gliding: m the hips drop, and the extra forward lean of the shoulders, m. */
        const val GLIDE_DROP = 0.08f; const val GLIDE_LEAN = 0.1f
        const val CROSS_TURN = 1.5f      // rad/s of turn from which the outside skate crosses over
        const val CROSS_SPEED = 4f       // m/s from which it does
        const val CROSS_LEAN = 0.25f     // m the shoulders lean to the inside
        const val CROSS_REACH = 0.6f     // m the recovering outside skate swings toward the inside
        const val CROSS_LIFT = 0.07f     // extra m it lifts to step over the inside skate
        const val STOP_DROP = 0.15f      // m the hips drop in a hockey stop
        const val STOP_LEAN = -0.1f
        const val STOP_STANCE = 0.24f    // m each skate sits out from the hips' middle
        const val HIP_Z = 0.93f
        const val TORSO_RISE = 0.52f
        /** Metres ahead of the skater the puck is carried, where the blade's middle rests. */
        const val CARRY_A = Tuning.CARRY_OFFSET
        /** A blade whose middle is higher than this is off the ice. */
        const val BLADE_ICE_Z = 0.1f
        /** Shoulder lateral shift per metre of blade offset, against the sweep. */
        const val SWEEP_SHOULDER = -0.4f
        const val BLADE_ROLL_LIFT = 0.04f
        /** Blade lateral shift per metre of the deke's shoulder lean. */
        const val DEKE_BLADE = 0.15f
        const val POKE_REACH = 1.8f
        /** Seconds of wind-up over which the blade draws back from the carry. */
        const val DRAW_BACK_S = 0.12f
        /** Metres the hips drop (knees bend) with the stick lifted for a slapshot. */
        const val SLAP_CROUCH = 0.1f
        /** Seconds before the one-timer window closes over which a one-timer's lift eases back down. */
        const val ONE_TIMER_EASE_S = 0.15f
        const val SAVE_FLASH_S = 0.3f
        /** Where a goalie's stick middle sits, from the top (0) to the blade's heel (1). */
        const val GOALIE_STICK_MID = 0.55f

        /** Pants top above, and bottom below, the hip centre along the torso, metres. */
        const val PANTS_UP = 0.1f; const val PANTS_DOWN = 0.16f
        /** Shoulder joints either side of the spine, metres. */
        const val SHOULDER_B = 0.2f

        const val OUTLINE_M = 0.035f
        /**
         * The number's text box is NUMBER_H text units square; the text size is [NUMBER_TEXT] of that.
         * Large units: at a text size near 1, text layout rounds the advances and the digits overlap.
         */
        const val NUMBER_H = 100f; const val NUMBER_TEXT = 0.9f
        /** Edge stroke as a fraction of the text size, and at least this many screen pixels. */
        const val NUMBER_EDGE = 0.16f; const val NUMBER_EDGE_MIN_PX = 2f
        /** The text box on the back: metres across the shoulders' axis and up the spine. */
        const val NUMBER_W_M = 0.3f; const val NUMBER_H_M = 0.3f
        /** Where its centre sits, from the hips (0) to the shoulders (1). */
        const val NUMBER_UP = 0.6f
        /** Metres it sits behind the jersey's back. */
        const val NUMBER_PROUD_M = 0.02f
        /**
         * The jersey's back behind the spine at the number, metres: the flat back face of the six-sided
         * torso tube (radius r × kb × sin 60°) in SkaterBody and GoalieBody.
         */
        const val SKATER_BACK_M = 0.15f; const val GOALIE_BACK_M = 0.24f
        /** The back's facing term at which the number starts to show, and is fully shown. */
        const val NUMBER_FADE_0 = 0.1f; const val NUMBER_FADE_1 = 0.5f
        const val PUCK_DRAW_R = 0.15f; const val PUCK_THICK = 0.05f; const val PUCK_RIM = 0.035f
        /** Above this, m, a puck is in the air: no contact shadow, a [drawPuckShadow] on the ice instead. */
        const val PUCK_LIFTED_Z = 0.01f
        const val PUCK_SHADOW_R = PUCK_DRAW_R * 1.1f
        const val PUCK_SHADOW_ALPHA_ICE = 0.45f; const val PUCK_SHADOW_ALPHA_HIGH = 0.35f; const val PUCK_SHADOW_HIGH_Z = 1.1f
        const val MARKER_TIP_Z = 2.0f; const val MARKER_H = 0.5f; const val MARKER_HALF_W = 0.32f; const val MARKER_EDGE = 0.08f
        const val RING_SEGMENTS = 32

        const val GOAL_HALF_W = Rink.GOAL_HALF_W
        const val NET_DEPTH = Rink.NET_DEPTH
        const val NET_H = 1.2f
        const val POST_W = 0.09f
        const val MESH_LINE = 0.02f
        const val MESH_LINES = 6
        const val NEAR_STRAND_ALPHA = 0.4f
        const val LAMP_R = 0.3f; const val LAMP_LIFT = 0.55f
        val LAMP_HALO = floatArrayOf(4f, 2.8f, 1.8f)
        val LAMP_HALO_ALPHA = floatArrayOf(0.12f, 0.2f, 0.35f)
        const val GOAL_FLASH_MIN = 0.45f; const val GOAL_FLASH_RATE = 9f; const val GOAL_FLASH_ALPHA = 0.45f

        val OUTLINE = Color(0xFF15181C)
        val SHADOW = Color(0xFF0B1A2A)
        val SKIN = Color(0xFFF0C29E)
        val BOOT = Color(0xFF202226)
        val BLADE = Color(0xFFC9D2DA)
        val STICK = Color(0xFFD9A75E)
        val TAPE = Color(0xFF1B1D21)
        val PAD = Color(0xFFF7F7F2)
        val CAGE = Color(0xFFE9EDF0)
        val PUCK_SIDE = Color(0xFF0E0E10)
        val PUCK_TOP = Color(0xFF202226)
        val PUCK_RIM_LIGHT = Color(0xFFFFFFFF)
        val NUMBER_LIGHT_EDGE = Color(0xFFFFFFFF)
        val POST_RED = Color(0xFFD7182A)
        val MESH = Color(0xFFE4E9EE)
        val MESH_IN = Color(0xFFC3CCD5)
        val MESH_LINE_COLOUR = Color(0xFF8E99A5)
        val MESH_FRAME = Color(0xFFF2F4F6)
        val LAMP_ON = Color(0xFFFF2A1A)
        val LAMP_OFF = Color(0xFF5C1414)
        val LAMP_CORE = Color(0xFFFFD0C0)

        fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

        /** 0 below [e0], 1 above [e1], and an S-curve between. */
        fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
