package io.github.alfonslm.tuner.app

import kotlin.math.max

/** A reading is fully clear up to this much scatter (cents, one standard deviation)... */
private const val CLEAN_SPREAD_CENTS = 1f

/** ...and as unclear as it gets from this much on. */
private const val NOISY_SPREAD_CENTS = 6f

/** A note's first readings, before their scatter is known, count as this unclear. */
private const val UNKNOWN_UNCLARITY = 0.4f

/** A reading is fully clear while the note is at least this share of what is sounding... */
private const val CLEAN_CONFIDENCE = 0.92f

/** ...and as unclear as it gets when it is no more than this. */
private const val MIXED_CONFIDENCE = 0.55f

/**
 * How far a reading is from being one clear note, 0 (clear) to 1 (broken up).
 * Two things say so, and the worse one counts:
 *
 *  - the estimates behind the reading scatter ([spreadCents]): noise, a dying
 *    note, something beating against it;
 *  - the note is not all that is sounding ([confidence], the share of the
 *    sound that is this note): other strings ringing on, somebody else playing.
 *
 * Both are known within a frame or two of the sound changing, which is the
 * point: the needle is an average and takes its time, this does not.
 */
fun unclarity(spreadCents: Float?, confidence: Float?): Float {
    val scattered = if (spreadCents == null) {
        UNKNOWN_UNCLARITY
    } else {
        ((spreadCents - CLEAN_SPREAD_CENTS) / (NOISY_SPREAD_CENTS - CLEAN_SPREAD_CENTS)).coerceIn(0f, 1f)
    }
    val mixed = if (confidence == null) 0f else ((CLEAN_CONFIDENCE - confidence) / (CLEAN_CONFIDENCE - MIXED_CONFIDENCE)).coerceIn(0f, 1f)
    return max(scattered, mixed)
}

/** The line in the waterfall is drawn in full while a reading is at most this unclear... */
private const val TRACE_FULL_UNCLARITY = 0.25f

/** ...and not at all from this on. */
private const val TRACE_GONE_UNCLARITY = 0.7f

/**
 * How firmly the line through the waterfall is drawn for a reading, 0..1. The
 * line says "the note was here"; where the tuner did not know (the estimates
 * scattered, or the note was not most of what was sounding) it has no business
 * saying so, and is not drawn. In between it fades.
 */
fun traceCertainty(unclear: Float): Float =
    ((TRACE_GONE_UNCLARITY - unclear) / (TRACE_GONE_UNCLARITY - TRACE_FULL_UNCLARITY)).coerceIn(0f, 1f)
