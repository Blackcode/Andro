package com.blackcode.cascoscan.detect

import kotlin.math.abs
import kotlin.math.exp

/**
 * Small membership functions shared by the classifier and the scorer.
 *
 * Every geometric test in this engine is a matter of degree - a hand-drafted circle is not exactly
 * round, a sleeve labelled 110 measures 112 - so thresholds are expressed as soft ramps instead of
 * hard cut-offs. That keeps one noisy pixel from flipping a verdict.
 */
internal object Fuzzy {

    fun clamp01(v: Double) = v.coerceIn(0.0, 1.0)

    /** 1.0 at or below [good], 0.0 at or above [bad], linear in between. Penalises growth. */
    fun falling(v: Double, good: Double, bad: Double): Double {
        require(bad > good) { "bad must exceed good" }
        return clamp01((bad - v) / (bad - good))
    }

    /** 0.0 at or below [bad], 1.0 at or above [good], linear in between. Rewards growth. */
    fun rising(v: Double, bad: Double, good: Double): Double {
        require(good > bad) { "good must exceed bad" }
        return clamp01((v - bad) / (good - bad))
    }

    /** 1.0 at [center], falling linearly to 0.0 at +/- [halfWidth]. */
    fun bell(v: Double, center: Double, halfWidth: Double): Double {
        require(halfWidth > 0.0)
        return clamp01(1.0 - abs(v - center) / halfWidth)
    }

    /**
     * Plateau membership: 0 outside [absMin, absMax], 1 inside [softMin, softMax], linear on the
     * shoulders. The natural shape for "a plausible penetration is 60-1200 mm, and anything
     * outside 30-3000 mm is not a penetration at all".
     */
    fun trapezoid(v: Double, absMin: Double, softMin: Double, softMax: Double, absMax: Double): Double {
        require(absMin <= softMin && softMin <= softMax && softMax <= absMax)
        return when {
            v <= absMin || v >= absMax -> 0.0
            v < softMin -> (v - absMin) / (softMin - absMin)
            v > softMax -> (absMax - v) / (absMax - softMax)
            else -> 1.0
        }
    }

    fun logistic(x: Double) = 1.0 / (1.0 + exp(-x))
}
