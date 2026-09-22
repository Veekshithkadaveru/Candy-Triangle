package app.krafted.candytriangle.board

import app.krafted.candytriangle.engine.Peg
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.RowCol

/**
 * A solid gem entity and multiplier value (§4.2, §8).
 * 
 * Gems share the `Peg` type in the `PhysicsWorld` because they are solid colliders.
 * This class provides the gameplay meaning (type, multiplier) and delegates active state
 * to the underlying `Peg`.
 */
class Gem(
    val type: GemType,
    /** The corresponding solid body in the physics engine. */
    val peg: Peg,
    val rc: RowCol,
) {
    /** 
     * Deactivating the gem automatically removes it from the physical board simulation.
     */
    var active: Boolean
        get() = peg.active
        set(value) {
            peg.active = value
        }
        
    val x: Float get() = peg.x
    val y: Float get() = peg.y
    val multiplier: Int get() = type.multiplier
}
