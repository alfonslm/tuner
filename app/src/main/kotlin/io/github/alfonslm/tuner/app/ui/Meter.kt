package io.github.alfonslm.tuner.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.alfonslm.tuner.app.unclarity
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Cents shown from the centre to either end of the scale. */
const val METER_RANGE_CENTS = 50f

/** The glow is never drawn narrower than this to either side, however clean the signal. */
private val MIN_HALF_WIDTH = 5.dp

/** The glow reaches this share of the meter's height: lower than a clear note's peak. */
private const val GLOW_TALLNESS = 0.6f

/** How opaque the glow is at its middle... */
private const val GLOW_OPACITY = 0.36f

/** ...and the bulge laid over it. */
private const val BULGE_OPACITY = 0.6f

/**
 * How many times narrower the bulge is than the glow: the average of the
 * estimates within the needle's averaging time is that much more certain than
 * any one of them (about the square root of how many there are).
 */
private const val BULGE_SHARPNESS = 4f

/** The line in the middle of the head. */
private val LINE_WIDTH = 3.dp

/** A spot reaches this many sigmas to either side before it has faded out. */
private const val SPOT_REACH = 2.4f

/** How far a spot has faded at its edge: exp(-4.6) is 1 %. */
private const val SPOT_FALLOFF = 4.6f

/** Colour stops the head's fade is drawn with. */
private const val HEAD_STOPS = 41

/** The width the head is given for a note's first readings, before their scatter is known. */
private const val UNKNOWN_SPREAD_CENTS = 1.5f

/**
 * What the scale shows: the offset in its middle and how far it reaches to
 * either side, in cents.
 */
data class ScaleWindow(val centre: Float = 0f, val range: Float = METER_RANGE_CENTS)

/** Within this distance of the target the scale does not move at all. */
private const val STEADY_CENTS = 25f

/** The needle never comes closer to the end of the scale than about this, as a share of the scale's reach. */
private const val NEEDLE_LIMIT = 0.88f

/** The target stays this far inside the opposite end, so the in-tune zone is always in sight. */
private const val TARGET_INSET = 0.1f

/**
 * Where the scale is for a given reading.
 *
 * Near the target it stands still: -50 to +50 cents, the needle moving over
 * it. A string further out would leave the needle, and its history, pinned to
 * the end, so from 25 cents on the scale begins to follow. It follows on a
 * curve, a little at first and more the further the string is, so the needle
 * keeps moving outwards and nothing jumps. The scale slides only as far as
 * leaves the in-tune zone in sight at the other end; a string further out
 * than that (about 90 cents) makes it show more cents instead.
 */
fun scaleWindow(cents: Float?): ScaleWindow {
    val distance = abs(cents ?: 0f)
    if (distance <= STEADY_CENTS) return ScaleWindow()
    val side = if (cents!! < 0) -1f else 1f
    // Where the needle stands on the scale: as far out as the reading at first, then less and
    // less of each further cent, towards a limit short of the end.
    val room = NEEDLE_LIMIT * METER_RANGE_CENTS - STEADY_CENTS
    val needle = STEADY_CENTS + room * (1 - exp(-(distance - STEADY_CENTS) / room))
    val furthest = METER_RANGE_CENTS * (1 - TARGET_INSET)
    val slide = distance - needle
    if (slide <= furthest) return ScaleWindow(side * slide)
    // Slid as far as it goes: from here the scale shows more. The target keeps its place near
    // one end and the needle its place, in proportion, near the other.
    val range = distance / (1 - TARGET_INSET + needle / METER_RANGE_CENTS)
    return ScaleWindow(side * range * (1 - TARGET_INSET), range)
}

/** A reading has to be at least this clear ([unclarity] at most this) for the scale to follow it. */
private const val FOLLOW_UNCLARITY = 0.6f

/** Readings belong together while each is within this many cents of the one before... */
private const val FOLLOW_JUMP_CENTS = 15f

/** ...and no further apart in time than this (seconds). */
private const val FOLLOW_GAP_SECONDS = 0.25

/** The scale follows readings that have belonged together for this long (seconds). */
private const val FOLLOW_DWELL_SECONDS = 0.4

/**
 * Decides when the scale moves ([scaleWindow] says where to).
 *
 * Left to itself the scale would go wherever the latest reading is, and in a
 * room with a television on that is all over the place: a stray reading two
 * semitones from the nearest string, a slide and a zoom, and back, several
 * times a second. So the scale only follows readings that have earned it: a
 * run of clear ones (the estimates agree and the note is most of what is
 * sounding), each close to the one before, that has lasted a moment. A
 * string does that, also while it is being tuned; noise does not. Until
 * then, and whenever there is no such run, the scale stays where it is.
 */
class ScaleFollower(initial: ScaleWindow = ScaleWindow()) {
    var window: ScaleWindow = initial
        private set

    private var runSince = 0.0
    private var lastTime = Double.NEGATIVE_INFINITY
    private var lastCents = 0f

    /**
     * @param cents the reading, or `null` if there is none
     * @param unclear how far it is from one clear note, see [unclarity]
     * @param time when it was taken, in seconds on any steadily running clock
     */
    fun follow(cents: Float?, unclear: Float, time: Double): ScaleWindow {
        if (cents == null || unclear > FOLLOW_UNCLARITY) return window
        if (time - lastTime > FOLLOW_GAP_SECONDS || abs(cents - lastCents) > FOLLOW_JUMP_CENTS) runSince = time
        lastTime = time
        lastCents = cents
        if (time - runSince >= FOLLOW_DWELL_SECONDS) window = scaleWindow(cents)
        return window
    }
}

/** The most the scale's acceleration may change, in cents per second cubed. */
const val SCALE_JERK = 12_000f

/** How eagerly a [JerkLimited] value closes in: higher arrives sooner and leans harder on the jerk limit. */
private const val CLOSING = 0.9f

/** The stiffest it gets, close to the goal (per second): what decides how quickly the last cent is covered. */
private const val MAX_STIFFNESS = 20f

/** Steps are no longer than this (seconds); a longer frame is taken in several. */
private const val MOTION_STEP = 0.008f

/** Within this of the goal and this slow, the value is put on the goal and comes to rest. */
private const val REST_DISTANCE = 0.01f
private const val REST_SPEED = 0.1f

/**
 * A value that goes to wherever it is told without ever being jerked there:
 * how quickly its acceleration may change is limited ([jerk]), and nothing
 * else is. A short way is covered quickly, a long way takes longer (roughly
 * with the cube root of the distance), and there is no fixed time either
 * takes. It can be given a new goal at any moment, also on the way to the old
 * one, and bends towards it as smoothly.
 *
 * It is a critically damped pull towards the goal whose stiffness depends on
 * the distance left, chosen so that the pull by itself asks for about as much
 * jerk as is allowed; what it still asks beyond that is cut off. It does not
 * overshoot.
 */
class JerkLimited(start: Float, private val jerk: Float = SCALE_JERK) {
    var position: Float = start
        private set
    private var velocity = 0f
    private var acceleration = 0f

    fun atRestOn(goal: Float): Boolean = position == goal && velocity == 0f && acceleration == 0f

    /** Moves on by [seconds] towards [goal] and returns where the value is then. */
    fun step(goal: Float, seconds: Float): Float {
        var left = seconds
        while (left > 0f) {
            val time = min(left, MOTION_STEP)
            left -= time
            val away = goal - position
            if (abs(away) <= REST_DISTANCE && abs(velocity) <= REST_SPEED) {
                position = goal
                velocity = 0f
                acceleration = 0f
                break
            }
            val stiffness = min(MAX_STIFFNESS, CLOSING * (jerk / abs(away)).pow(1f / 3))
            val wanted = stiffness * stiffness * away - 2 * stiffness * velocity
            acceleration += (wanted - acceleration).coerceIn(-jerk * time, jerk * time)
            velocity += acceleration * time
            position += velocity * time
        }
        return position
    }
}

/** How solid the line still is in a fully unclear reading: all but gone. */
private const val NOISY_OPACITY = 0.06f

/** A fully unclear reading's peak is this share of the meter's height: the glow's, and a little. */
private const val NOISY_TALLNESS = 0.68f

/** The glow of a fully unclear reading is at least this wide (cents, as a standard deviation). */
private const val UNCLEAR_SPREAD_CENTS = 5f

/** A reading that is only being held on screen (the note has stopped) is drawn this much fainter, glow and all. */
private const val HELD_DIMMING = 0.45f

/**
 * How solid the line in the needle is drawn: the reading is only as crisp as
 * the note is clear. Solid for a clear note, all but gone for a broken-up one
 * ([unclarity]); fainter again for a reading that is only held.
 */
fun needleOpacity(unclear: Float, held: Boolean): Float {
    val clear = 1f - (1f - NOISY_OPACITY) * unclear.coerceIn(0f, 1f)
    return if (held) clear * HELD_DIMMING else clear
}

/**
 * How tall the needle's peak is drawn, as a share of the meter's height: full
 * for a clear note, shorter the less clear, down to where it barely stands out
 * of the glow.
 */
fun headTallness(unclear: Float): Float = 1f - (1f - NOISY_TALLNESS) * unclear.coerceIn(0f, 1f)

/** Space left at either end of the scale; the waterfall below uses the same so the two line up. */
val METER_INSET = 2.dp

/**
 * A linear cents scale with a moving needle. The green zone marks the in-tune
 * tolerance; readings beyond the scale rest against its end.
 *
 * The needle is three soft things laid over each other. A glow, fading from
 * its middle towards all its edges, is as wide as the estimates behind the
 * reading scatter (their standard deviation is its sigma), to each side by as
 * much as they stray to that side: scatter that is all sharp gives a glow that
 * leans sharp. A bulge in it,
 * several times narrower, is where their average, the reading, is. A line
 * through the middle is only as crisp as the note is clear ([unclarity],
 * [needleOpacity]): solid for one clean note, fading as the estimates scatter
 * or as other sounds join it, gone when the sound is broken up. The bulge and
 * the line are also shorter the less clear the note is ([headTallness]), and
 * the glow is low and at least a few cents wide then. So a clear note is a
 * tall sharp needle, and a weak, noisy or crowded one a short soft peak that
 * runs out into a wide low glow. The look changes within a frame or two of the
 * sound changing, faster than the reading itself, which is an average.
 *
 * @param cents offset to show, or `null` to park the needle on the target
 * @param spreadCents standard deviation of the estimates behind [cents], if known
 * @param confidence the share of what is sounding that is the note being read, if known
 * @param dimmed true for a held reading (the note has stopped)
 * @param window what the scale shows, see [scaleWindow]
 * @param spreadFlatCents the scatter of the estimates that fall below the reading...
 * @param spreadSharpCents ...and of those that fall above it; the glow reaches as far to each side
 */
@Composable
fun Meter(
    cents: Float?,
    spreadCents: Float?,
    confidence: Float?,
    tone: Color,
    inTuneCents: Float,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
    window: ScaleWindow = ScaleWindow(),
    spreadFlatCents: Float? = spreadCents,
    spreadSharpCents: Float? = spreadCents,
) {
    val centre = window.centre
    val range = window.range
    val needle by animateFloatAsState(
        targetValue = cents ?: 0f,
        animationSpec = tween(durationMillis = 80),
        label = "needle",
    )
    val position = ((needle - centre) / range).coerceIn(-1f, 1f)
    // Marks thin out as the scale shows more.
    val labelStep = if (range <= 60f) 25 else if (range <= 130f) 50 else 100
    val tickStep = labelStep / 5
    val labels = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = TunerColors.Muted)
    // How clear the reading is decides how the head looks. Getting worse shows at once;
    // getting better takes a moment, so that the head does not flicker.
    val unclearNow = if (cents == null) 0f else unclarity(spreadCents, confidence)
    val shown = remember { floatArrayOf(0f) }
    val worse = unclearNow > shown[0]
    shown[0] = unclearNow
    val unclear by animateFloatAsState(
        targetValue = unclearNow,
        animationSpec = tween(durationMillis = if (worse) 50 else 300),
        label = "unclear",
    )
    // The glow reaches as far to each side as the estimates stray to that side. What makes a
    // reading unclear without saying where (company) widens both sides alike.
    val least = unclearNow * UNCLEAR_SPREAD_CENTS
    val sigmaFlat by animateFloatAsState(
        targetValue = if (cents == null) 0f else max(spreadFlatCents ?: UNKNOWN_SPREAD_CENTS, least),
        animationSpec = tween(durationMillis = if (worse) 50 else 300),
        label = "sigmaFlat",
    )
    val sigmaSharp by animateFloatAsState(
        targetValue = if (cents == null) 0f else max(spreadSharpCents ?: UNKNOWN_SPREAD_CENTS, least),
        animationSpec = tween(durationMillis = if (worse) 50 else 300),
        label = "sigmaSharp",
    )
    val tallness = headTallness(unclear)
    val needleAlpha = needleOpacity(unclear, held = false)
    // A reading that is only held is fainter as a whole, glow included.
    val headDim by animateFloatAsState(if (dimmed || cents == null) HELD_DIMMING else 1f, label = "headDim")

    // The same information is spoken by the cents readout next to it.
    Column(modifier.clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The numbers, wherever the scale currently is.
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(16.dp),
        ) {
            val inset = METER_INSET.toPx()
            val half = size.width / 2 - inset
            var mark = ceil((centre - range) / labelStep).toInt() * labelStep
            while (mark <= centre + range) {
                val text = labels.measure(if (mark > 0) "+$mark" else if (mark < 0) "−${-mark}" else "0", labelStyle)
                val markX = size.width / 2 + (mark - centre) / range * half
                // Centred on its mark, but never hanging over the end of the scale.
                val left = (markX - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
                drawText(text, topLeft = Offset(left, size.height - text.size.height))
                mark += labelStep
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(44.dp),
        ) {
            val inset = METER_INSET.toPx()
            val half = size.width / 2 - inset
            val centreX = size.width / 2
            val centreY = size.height / 2
            fun x(fraction: Float) = centreX + fraction * half

            // In-tune zone, as much of it as is on the scale.
            val zoneLeft = x(((-inTuneCents - centre) / range).coerceIn(-1f, 1f))
            val zoneRight = x(((inTuneCents - centre) / range).coerceIn(-1f, 1f))
            if (zoneRight > zoneLeft) {
                drawRoundRect(
                    color = TunerColors.Good.copy(alpha = 0.22f),
                    topLeft = Offset(zoneLeft, 6.dp.toPx()),
                    size = Size(zoneRight - zoneLeft, size.height - 12.dp.toPx()),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }

            // Scale: five ticks to a number, the numbered ones taller.
            drawLine(TunerColors.Line, Offset(x(-1f), centreY), Offset(x(1f), centreY), strokeWidth = 2.dp.toPx())
            var tick = ceil((centre - range) / tickStep).toInt() * tickStep
            while (tick <= centre + range) {
                val tickHalf = (if (tick % labelStep == 0) 10.dp else 5.dp).toPx()
                val tickX = x((tick - centre) / range)
                drawLine(TunerColors.Line, Offset(tickX, centreY - tickHalf), Offset(tickX, centreY + tickHalf), strokeWidth = 1.dp.toPx())
                tick += tickStep
            }

            // The head: three things laid over each other, all of them soft.
            //  - A glow as wide as the single estimates scatter (their standard deviation is its
            //    sigma), fading from its middle towards all its edges.
            //  - A bulge in it, several times narrower: where their average, the reading, is.
            //  - A line through the middle, which is only as crisp as the note is clear: solid on
            //    a clean signal, fading as the estimates scatter, gone when the sound is broken up.
            // The line and the bulge are also shorter the less clear the note is, and the glow is
            // low. So a clear note is a tall sharp needle, and a weak or broken-up one a short
            // soft peak that runs out into a wide low glow.
            val centreHead = Offset(x(position), centreY)
            val glowFlat = max(SPOT_REACH * sigmaFlat / range * half, MIN_HALF_WIDTH.toPx())
            val glowSharp = max(SPOT_REACH * sigmaSharp / range * half, MIN_HALF_WIDTH.toPx())
            val bulgeWide = max((glowFlat + glowSharp) / 2 / BULGE_SHARPNESS, MIN_HALF_WIDTH.toPx())
            // The clearer the note, the taller the peak; the glow under it stays low, so a weak
            // signal's short peak runs out into it.
            val peakTall = centreY * tallness
            val glowTall = centreY * GLOW_TALLNESS
            fun spot(wide: Float, tall: Float, opacity: Float, steepness: Float) {
                val stops = Array(HEAD_STOPS) { index ->
                    val place = index / (HEAD_STOPS - 1f)
                    place to tone.copy(alpha = opacity * headDim * exp(-SPOT_FALLOFF * place.pow(steepness)))
                }
                // A round spot, squeezed sideways to the width it should have.
                withTransform({ scale(scaleX = wide / tall, scaleY = 1f, pivot = centreHead) }) {
                    drawCircle(Brush.radialGradient(*stops, center = centreHead, radius = tall), radius = tall, center = centreHead)
                }
            }
            // The glow in two halves, each as wide as the estimates stray to its side: scatter that
            // is all sharp gives a glow that leans sharp.
            // The side with more of the scatter is also the brighter one.
            val flatShare = sigmaFlat * sigmaFlat / (sigmaFlat * sigmaFlat + sigmaSharp * sigmaSharp).coerceAtLeast(1e-6f)
            val flatGlow = GLOW_OPACITY * (0.5f + flatShare)
            val sharpGlow = GLOW_OPACITY * (1.5f - flatShare)
            clipRect(left = x(-1f), right = centreHead.x) { spot(glowFlat, glowTall, flatGlow, steepness = 2f) }
            clipRect(left = centreHead.x, right = x(1f)) { spot(glowSharp, glowTall, sharpGlow, steepness = 2f) }
            clipRect(left = x(-1f), right = x(1f)) { spot(bulgeWide, peakTall, BULGE_OPACITY, steepness = 4f) }
            val lineHalf = peakTall - LINE_WIDTH.toPx() / 2
            drawLine(
                color = tone.copy(alpha = needleAlpha * headDim),
                start = Offset(centreHead.x, centreY - lineHalf),
                end = Offset(centreHead.x, centreY + lineHalf),
                strokeWidth = LINE_WIDTH.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}
