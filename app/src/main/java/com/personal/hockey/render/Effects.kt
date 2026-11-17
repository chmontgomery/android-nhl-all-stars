package com.personal.hockey.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Snow spray and the puck streak. Particles come from one preallocated pool of [capacity]: each flies
 * from where it was sprayed, falls under gravity, settles on the ice and fades out with its life. The
 * streak is the puck's last [STREAK_POINTS] positions while it moves faster than [STREAK_SPEED].
 * Nothing here allocates after construction; render thread only.
 */
class Effects(capacity: Int = 256) {
    private val x = FloatArray(capacity)
    private val y = FloatArray(capacity)
    private val z = FloatArray(capacity)
    /** Velocities in m/s; vx, vy and [life] are read by tests. */
    internal val vx = FloatArray(capacity)
    internal val vy = FloatArray(capacity)
    private val vz = FloatArray(capacity)
    /** Seconds left; 0 for a free slot. */
    internal val life = FloatArray(capacity)
    private val maxLife = FloatArray(capacity)
    /** Where the next spray starts looking for a free slot; when none is free it overwrites from here. */
    private var cursor = 0
    private var seed = 0x2545F491

    /** Particles in flight or settling. */
    var alive = 0
        private set

    // The streak: a ring of puck positions (z: the height it was drawn at), newest at [head].
    private val sx = FloatArray(STREAK_POINTS)
    private val sy = FloatArray(STREAK_POINTS)
    private val sz = FloatArray(STREAK_POINTS)
    private var head = 0

    /** Points in the streak, 0 when there is none. */
    var streakLength = 0
        private set

    /** Height of the newest streak point (for tests). */
    internal val newestStreakZ: Float get() = sz[head]

    private val p = FloatArray(3)
    private val q = FloatArray(3)

    /**
     * Throws [count] particles from (x, y) on the ice along (dirX, dirY) (need not be unit length), at
     * about [speed] m/s with some spread and a kick upward.
     */
    fun spray(x: Float, y: Float, dirX: Float, dirY: Float, count: Int, speed: Float) {
        val len = sqrt(dirX * dirX + dirY * dirY)
        val ux = if (len > 1e-4f) dirX / len else 1f
        val uy = if (len > 1e-4f) dirY / len else 0f
        val n = life.size
        for (k in 0 until count) {
            var i = cursor
            var tries = 0
            while (life[i] > 0f && tries < n) { i = (i + 1) % n; tries++ }
            if (life[i] <= 0f) alive++   // otherwise the pool is full and the slot at the cursor is reused
            cursor = (i + 1) % n
            val turn = (rand() * 2f - 1f) * SPREAD
            val c = cos(turn); val s = sin(turn)
            val v = speed * (SPEED_MIN + (SPEED_MAX - SPEED_MIN) * rand())
            this.x[i] = x; this.y[i] = y; z[i] = LAUNCH_Z
            vx[i] = (ux * c - uy * s) * v
            vy[i] = (ux * s + uy * c) * v
            vz[i] = speed * (LIFT_MIN + (LIFT_MAX - LIFT_MIN) * rand())
            maxLife[i] = LIFE_MIN + (LIFE_MAX - LIFE_MIN) * rand()
            life[i] = maxLife[i]
        }
    }

    /**
     * Hears the puck's position each tick, [z] m up (a shot in flight). While [speed] is over
     * [STREAK_SPEED] the streak keeps its last [STREAK_POINTS] positions; slower, it shrinks away by a
     * point a tick. A jump too long for one tick (a faceoff after a goal) clears it, so it never draws
     * a line across the rink.
     */
    fun notePuck(x: Float, y: Float, z: Float, speed: Float) {
        if (streakLength > 0) {
            val dx = x - sx[head]; val dy = y - sy[head]
            if (dx * dx + dy * dy > STREAK_JUMP * STREAK_JUMP) streakLength = 0
        }
        if (speed <= STREAK_SPEED && streakLength == 0) return
        head = (head + 1) % STREAK_POINTS
        sx[head] = x; sy[head] = y; sz[head] = z
        streakLength = if (speed > STREAK_SPEED) minOf(streakLength + 1, STREAK_POINTS) else max(0, streakLength - 1)
    }

    /** Moves the particles on by [dt] seconds: gravity on z, friction once they land, and ageing. */
    fun step(dt: Float) {
        if (alive == 0) return
        val drag = max(0f, 1f - ICE_FRICTION * dt)
        for (i in life.indices) {
            if (life[i] <= 0f) continue
            life[i] -= dt
            if (life[i] <= 0f) { life[i] = 0f; alive--; continue }
            vz[i] -= GRAVITY * dt
            x[i] += vx[i] * dt; y[i] += vy[i] * dt; z[i] += vz[i] * dt
            if (z[i] <= 0f) {
                z[i] = 0f; vz[i] = 0f
                vx[i] *= drag; vy[i] *= drag
            }
        }
    }

    /** The streak behind the puck, then the particles: white and pale blue, fading with their life. */
    fun draw(scope: DrawScope, camera: Camera) {
        drawStreak(scope, camera)
        if (alive == 0) return
        for (i in life.indices) {
            if (life[i] <= 0f) continue
            camera.project(x[i], y[i], z[i], p)
            val r = max(PARTICLE_MIN_PX, PARTICLE_R * p[2])
            val a = PARTICLE_ALPHA * life[i] / maxLife[i]
            scope.drawCircle(if (i % 2 == 0) SNOW else SNOW_BLUE, r, Offset(p[0], p[1]), alpha = a)
        }
    }

    /**
     * A tapered translucent white line from just behind the puck (it starts [STREAK_GAP] m back, so the
     * puck itself is never washed out) to its oldest point, narrowing and fading along the way. Each
     * point is drawn at the height the puck flew at, so a shot's streak follows its arc.
     */
    private fun drawStreak(scope: DrawScope, camera: Camera) {
        val n = streakLength
        if (n < 2) return
        var ax = sx[head]; var ay = sy[head]; var az = sz[head]
        var gap = STREAK_GAP
        for (k in 1 until n) {
            val j = (head - k + STREAK_POINTS) % STREAK_POINTS
            val bx = sx[j]; val by = sy[j]; val bz = sz[j]
            val dx = bx - ax; val dy = by - ay
            val len = sqrt(dx * dx + dy * dy)
            if (gap > 0f) {
                if (len <= gap) { gap -= len; ax = bx; ay = by; az = bz; continue }
                val f = gap / len
                ax += dx * f; ay += dy * f; az += (bz - az) * f
                gap = 0f
            }
            val t0 = (k - 1f) / (n - 1f); val t1 = k / (n - 1f)
            camera.project(ax, ay, STREAK_Z + az, p)
            camera.project(bx, by, STREAK_Z + bz, q)
            val width = STREAK_W * p[2] * (1f - 0.5f * (t0 + t1))
            scope.drawLine(STREAK, Offset(p[0], p[1]), Offset(q[0], q[1]), max(1f, width), StrokeCap.Round,
                alpha = STREAK_ALPHA * (1f - t0))
            ax = bx; ay = by; az = bz
        }
    }

    /** Uniform in [0, 1), from a xorshift that needs no allocation. */
    private fun rand(): Float {
        var s = seed
        s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
        seed = s
        return (s ushr 8) / 16777216f
    }

    companion object {
        /** The puck streaks while faster than this, m/s. */
        const val STREAK_SPEED = 20f
        const val STREAK_POINTS = 8
        /** Farther than this between ticks is a teleport, not motion (a 45 m/s puck moves 0.75 m). */
        const val STREAK_JUMP = 2f
        const val STREAK_GAP = 0.3f
        const val STREAK_W = 0.16f
        const val STREAK_Z = 0.04f
        const val STREAK_ALPHA = 0.55f

        const val GRAVITY = 9.8f
        const val ICE_FRICTION = 6f
        const val SPREAD = 0.55f
        const val SPEED_MIN = 0.6f; const val SPEED_MAX = 1.2f
        const val LIFT_MIN = 0.25f; const val LIFT_MAX = 0.7f
        const val LIFE_MIN = 0.35f; const val LIFE_MAX = 0.65f
        const val LAUNCH_Z = 0.03f
        const val PARTICLE_R = 0.045f
        const val PARTICLE_MIN_PX = 1.5f
        const val PARTICLE_ALPHA = 0.9f

        val SNOW = Color(0xFFFFFFFF)
        val SNOW_BLUE = Color(0xFFCFE6FA)
        val STREAK = Color(0xFFFFFFFF)
    }
}
