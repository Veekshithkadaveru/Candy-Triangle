package app.krafted.candytriangle.engine

import kotlin.math.PI
import kotlin.math.floor

/**
 * Sine and cosine of an angle given in **turns** — fractions of a full cycle, `2 PI` radians — for
 * the per-step code: allocation-free, and bit-identical on the JVM and on ART.
 *
 * ## Why not `StrictMath`
 *
 * `StrictMath` (fdlibm) is bit-identical everywhere, and launch trig still uses it
 * ([PhysicsParams.launchVx], once per shot). But on JDK 21+ `StrictMath.sin`/`cos` are a pure-Java
 * fdlibm port that allocates a `double[2]` at the top of every call — 32 B, even for a tiny argument
 * and after JIT warm-up — and [Peg.updateMotion] runs for every moving peg on every step, against a
 * step contract of zero allocation. ART's `StrictMath` happens to be native today, but the engine
 * must not depend on which JDK internal it lands on. `Math.sin`/`cos` do not allocate, but they are
 * not reproducible across platforms, so they are out too.
 *
 * ## Why this is still bit-identical
 *
 * It uses only `floor`, `rint`, `+`, `-` and `*` on doubles. Each is exactly specified by Java and
 * IEEE-754 correctly rounded on every VM, and Java semantics forbid contracting `a * b + c` into a
 * fused multiply-add. The same input therefore gives the same bits on a JVM test host and on a phone
 * — `StrictMath`'s guarantee, without its allocation.
 *
 * ## Method
 *
 * 1. **Reduce in turns, exactly.** `q = 4 (t - floor(t))` is the angle in quarter turns, in
 *    `[0, 4]`; for `t >= 0` both operations are exact (Sterbenz's lemma, then a power-of-two scale).
 *    `n = rint(q)` is the nearest quarter turn and `r = q - n`, in `[-1/2, 1/2]`, is exact too. No
 *    rounded multiple of PI is ever subtracted — the classic source of argument-reduction error —
 *    and quarter turns come out exact: `sinTurns(0.25)` is exactly 1, `cosTurns(0.5)` exactly -1.
 * 2. **Scale once.** `x = r * PI / 2`, `|x| <= PI / 4`: the one rounded step of the reduction.
 * 3. **Polynomial.** Taylor series on `|x| <= PI / 4`, Horner in `x^2`: sine to `x^15`, cosine to
 *    `x^16`. The first omitted terms are under 5e-17 and 3e-18 there — below a double's resolution.
 * 4. **Quadrant.** `n mod 4` picks sine or cosine of `x`, and the sign.
 *
 * Accuracy: within 4e-16 of the true value over a whole turn (see `DeterministicTrigTest`) — some
 * nine orders of magnitude below the float that [Peg] rounds the result to. A non-finite input gives
 * NaN; a negative one is reduced with a single rounding, so it is merely very accurate, not exact.
 */
internal object DeterministicTrig {

    /** `sin(2 PI turns)`. */
    fun sinTurns(turns: Double): Double {
        val quarters = 4.0 * (turns - floor(turns))
        val nearest = Math.rint(quarters)
        val x = (quarters - nearest) * HALF_PI
        return when (nearest.toInt() and 3) {
            0 -> sinPoly(x)
            1 -> cosPoly(x)
            2 -> -sinPoly(x)
            else -> -cosPoly(x)
        }
    }

    /** `cos(2 PI turns)`. */
    fun cosTurns(turns: Double): Double {
        val quarters = 4.0 * (turns - floor(turns))
        val nearest = Math.rint(quarters)
        val x = (quarters - nearest) * HALF_PI
        return when (nearest.toInt() and 3) {
            0 -> cosPoly(x)
            1 -> -sinPoly(x)
            2 -> -cosPoly(x)
            else -> sinPoly(x)
        }
    }

    /**
     * `sin(x)` for `|x| <= PI / 4`. Written `x + x z P(z)` so the exact leading term is added last:
     * the correction is at most `x^3 / 6`, so its rounding error is scaled down with it.
     */
    private fun sinPoly(x: Double): Double {
        val z = x * x
        return x + x * z * (S3 + z * (S5 + z * (S7 + z * (S9 + z * (S11 + z * (S13 + z * S15))))))
    }

    /** `cos(x)` for `|x| <= PI / 4`. */
    private fun cosPoly(x: Double): Double {
        val z = x * x
        return 1.0 +
            z * (C2 + z * (C4 + z * (C6 + z * (C8 + z * (C10 + z * (C12 + z * (C14 + z * C16)))))))
    }

    /** A quarter turn, radians. Halving is exact, so this is exactly `PI / 2` in double. */
    private const val HALF_PI: Double = PI / 2

    // Taylor coefficients, (-1)^k / n!, each the double nearest the exact rational. The factorials
    // are all exact doubles (16! is about 2.1e13, under 2^53).
    private const val S3: Double = -1.0 / 6.0
    private const val S5: Double = 1.0 / 120.0
    private const val S7: Double = -1.0 / 5040.0
    private const val S9: Double = 1.0 / 362880.0
    private const val S11: Double = -1.0 / 39916800.0
    private const val S13: Double = 1.0 / 6227020800.0
    private const val S15: Double = -1.0 / 1307674368000.0

    private const val C2: Double = -1.0 / 2.0
    private const val C4: Double = 1.0 / 24.0
    private const val C6: Double = -1.0 / 720.0
    private const val C8: Double = 1.0 / 40320.0
    private const val C10: Double = -1.0 / 3628800.0
    private const val C12: Double = 1.0 / 479001600.0
    private const val C14: Double = -1.0 / 87178291200.0
    private const val C16: Double = 1.0 / 20922789888000.0
}
