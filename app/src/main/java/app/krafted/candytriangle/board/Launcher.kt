package app.krafted.candytriangle.board

import app.krafted.candytriangle.engine.Ball
import app.krafted.candytriangle.engine.Peg
import app.krafted.candytriangle.engine.PhysicsListener
import app.krafted.candytriangle.engine.PhysicsParams
import app.krafted.candytriangle.engine.PhysicsWorld
import app.krafted.candytriangle.engine.WallSegment
import app.krafted.candytriangle.level.BoardPoint

/**
 * The Launcher that fires balls into the board (§3.1, §8 `Launcher.kt`).
 *
 * It holds the aiming state clamped to the allowed range, and can simulate the dotted
 * trajectory guide up to the first contact (Peg or Wall).
 */
class Launcher(
    val params: PhysicsParams,
) {
    /**
     * Current aim angle in radians. 0 is straight down, positive is toward +x.
     * Always clamped to `params.aimClampRadians`.
     */
    var aimRadians: Float = 0f
        set(value) {
            field = params.clampAim(value)
        }

    init {
        aimRadians = 0f // Apply initial clamp
    }

    /**
     * Simulates a ball's trajectory up to the first contact and returns a list of path points.
     * This fulfills the Peggle-style dotted aim guide requirement (§2).
     *
     * @param world the simulation world to provide walls and current peg positions
     * @param maxSteps maximum steps to simulate to prevent infinite loops (fallback)
     * @param recordInterval record every Nth step to reduce the number of visual dots
     */
    fun predictTrajectory(
        world: PhysicsWorld,
        maxSteps: Int = 1000,
        recordInterval: Int = 4,
    ): List<BoardPoint> {
        val ghost = Ball(
            id = -1,
            x = params.spawnX,
            y = params.spawnY,
            vx = params.launchVx(aimRadians),
            vy = params.launchVy(aimRadians),
            radius = params.ballRadius,
            skin = app.krafted.candytriangle.level.BallSkin.DEFAULT,
        )

        val path = ArrayList<BoardPoint>(maxSteps / recordInterval + 2)
        var hit = false

        val listener = object : PhysicsListener {
            override fun onPegContact(ball: Ball, peg: Peg, impactSpeed: Float) {
                hit = true
            }

            override fun onWallContact(ball: Ball, wall: WallSegment, impactSpeed: Float) {
                hit = true
            }
        }

        path.add(BoardPoint(ghost.x, ghost.y))

        for (i in 0 until maxSteps) {
            world.integrate(ghost)
            world.resolveContacts(ghost, listener)

            if (hit) {
                // We reached the first bounce, stop and record the impact point.
                path.add(BoardPoint(ghost.x, ghost.y))
                break
            }

            if (i % recordInterval == 0) {
                path.add(BoardPoint(ghost.x, ghost.y))
            }

            if (ghost.y > params.exitY) {
                path.add(BoardPoint(ghost.x, ghost.y))
                break
            }
        }

        return path
    }
}
