package io.github.alfonslm.tuner.engine

import io.github.alfonslm.tuner.dsp.AnalyzerPool
import io.github.alfonslm.tuner.dsp.FocusedDetector
import io.github.alfonslm.tuner.dsp.FocusedDetectorOptions
import io.github.alfonslm.tuner.dsp.FocusedEstimate
import io.github.alfonslm.tuner.dsp.FreshOptions
import io.github.alfonslm.tuner.dsp.FoldedSpectrum
import io.github.alfonslm.tuner.dsp.FoldedSpectrumOptions
import io.github.alfonslm.tuner.dsp.PitchEstimate
import io.github.alfonslm.tuner.dsp.SPECTRUM_COLUMNS
import io.github.alfonslm.tuner.dsp.SpectralAnalyzer
import io.github.alfonslm.tuner.dsp.WideDetector
import io.github.alfonslm.tuner.dsp.nextPowerOfTwo
import io.github.alfonslm.tuner.music.AUTO_INSTRUMENT_ID
import io.github.alfonslm.tuner.music.InstrumentIdentifier
import io.github.alfonslm.tuner.music.InstrumentPreset
import io.github.alfonslm.tuner.music.Target
import io.github.alfonslm.tuner.music.TargetSet
import io.github.alfonslm.tuner.music.TargetSetOptions
import io.github.alfonslm.tuner.music.centsBetween
import io.github.alfonslm.tuner.music.centsToRatio
import io.github.alfonslm.tuner.music.createTargetSet
import io.github.alfonslm.tuner.music.automaticPreset
import io.github.alfonslm.tuner.music.findInstrument
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The whole tuner minus the microphone and the screen: feed it audio blocks,
 * get one [TunerReading] back per block. It has no Android dependency, so it
 * is unit-tested on the JVM. Not thread-safe: call it from one thread.
 *
 * Per block:
 *  1. the wide detector looks for any pitched note in the instrument's range;
 *  2. a stability window decides whether that note has settled on one target;
 *  3. in SEARCH mode a settled note engages the lock; in LOCKED mode the
 *     focused detector (harmonic comb around the target) produces the reading
 *     and the wide detector only acts as a watchdog for a change of string;
 *  4. the reading is smoothed for display.
 *
 * Every few blocks the engine also produces a row of the rolling spectrogram
 * for whichever target is on display.
 *
 * With the automatic instrument, the engine starts out chromatic over the range
 * of all instruments and passes every settled note to an [InstrumentIdentifier];
 * once that names an instrument, its strings become the targets.
 *
 * @param blockSize samples per [process] call; only used to convert time constants into frames
 */
class TunerEngine(val sampleRate: Int, val blockSize: Int, config: TunerConfig = TunerConfig()) {

    var config: TunerConfig = config
        private set

    lateinit var targets: TargetSet
        private set

    private class Lock(
        val target: Target,
        val pinned: Boolean,
        val detector: FocusedDetector,
        /** Widest the comb may open: how far the string can be from the target and still be ours. */
        val captureCents: Double,
        /** Current comb half-width; shrinks as the string approaches the target. */
        var widthCents: Double,
        var lastHeard: Double,
    ) {
        val frame = FloatArray(detector.frameLength)
        var misses = 0
        var handoffFrames = 0
    }

    private class Pitch(
        val frequency: Double,
        val target: Target,
        val source: PitchSource,
        val confidence: Double,
        /** Samples in the analysis window the estimate came from. */
        val window: Int,
    )

    private val pool = AnalyzerPool()
    private val detectors = HashMap<String, FocusedDetector>()
    private lateinit var preset: InstrumentPreset
    private lateinit var wideAnalyzer: SpectralAnalyzer
    private lateinit var wideDetector: WideDetector
    private lateinit var widePower: DoubleArray
    private lateinit var wideFrame: FloatArray
    private lateinit var ring: RingBuffer
    private lateinit var stability: StabilityWindow
    private lateinit var smoother: ReadingSmoother
    private var lock: Lock? = null
    private var samples = 0L

    /** Stream position at which the last pluck was noticed. */
    private var pluckedAt: Long? = null

    /** Spectrogram analysis for the target currently on display; rebuilt when that changes. */
    private var spectrum: FoldedSpectrum? = null
    private var spectrumTargetId: String? = null
    private var spectrumFrame = FloatArray(0)
    private var blocks = 0L

    /** Automatic mode: the instrument recognised from the strings heard so far. */
    private var identifier = InstrumentIdentifier(config.tunings)
    private var identified: InstrumentPreset? = null

    init {
        rebuild()
    }

    /**
     * Replaces the configuration. The current lock is dropped; audio history is
     * kept, and so is an automatically identified instrument unless the
     * configured instrument or the tunings to choose among changed.
     */
    fun configure(config: TunerConfig) {
        if (config.tunings != this.config.tunings) {
            identifier = InstrumentIdentifier(config.tunings)
            identified = null
        }
        if (config.instrumentId != this.config.instrumentId) forgetInstrument()
        this.config = config
        rebuild()
    }

    /** Forgets all audio and state, e.g. when the microphone is restarted. */
    fun reset() {
        samples = 0
        forgetInstrument()
        rebuild()
    }

    private fun forgetInstrument() {
        identified = null
        identifier.reset()
    }

    fun process(block: FloatArray): TunerReading {
        ring.push(block)
        samples += block.size
        val time = samples.toDouble() / sampleRate

        if (!ring.copyLatest(wideFrame)) return reading(time, -120.0, null, null, false)

        // 1. Wide-band analysis: acquisition while searching, watchdog while locked.
        val rms = wideAnalyzer.powerSpectrum(wideFrame, widePower)
        val levelDb = 20 * log10(rms + 1e-9)
        val audible = levelDb > config.levelGateDb
        // `wide` is good enough to act on; whether it is good enough to show is asked where it is shown.
        val wide = (if (audible) wideDetector.detect(widePower) else null)?.takeIf { it.clarity >= config.wide.minLockClarity }
        if (audible && wideDetector.onset) {
            pluckedAt = samples
            lock?.detector?.pluck()
            smoother.settle()
        }
        val wideTarget = wide?.let { targets.nearest(it.frequency) }

        // 2. Has one note been sounding steadily?
        if (wide != null && wideTarget != null) {
            stability.push(wideTarget, centsBetween(wide.frequency, wideTarget.frequency), wide.fresh)
        } else {
            stability.push(null)
        }
        val settled = stability.consensus()

        // 3. Mode logic.
        var pitch: Pitch? = null
        if (lock != null) pitch = trackLocked(time, audible, wide, settled)
        if (lock == null) {
            if (wide != null && wideTarget != null) pitch = shown(wide, wideTarget)
            if (config.smartFilter == SmartFilterMode.AUTO && settled != null) {
                engage(settled.target, settled.cents, pinned = false, time)
            }
        }
        adaptComb(pitch)

        // The attack of a pluck is not worth showing.
        val plucked = pluckedAt
        if (plucked != null && (samples - plucked) * 1000.0 / sampleRate < config.display.attackHoldMs) pitch = null

        // 4. Display smoothing.
        val row = spectrumRow(time, audible, pitch?.target ?: lock?.target)
        val result = if (pitch == null) {
            reading(time, levelDb, null, null, false, row)
        } else {
            val smoothed = smoother.push(pitch.target.id, centsBetween(pitch.frequency, pitch.target.frequency), time)
            reading(time, levelDb, pitch, smoothed.cents, smoothed.inTune, row)
        }

        // 5. Automatic mode: every settled note is a clue to which instrument this is.
        if (settled != null && identified == null && config.instrumentId == AUTO_INSTRUMENT_ID) {
            identify(settled.target.frequency * centsToRatio(settled.cents), time)
        }
        return result
    }

    /**
     * The next row of the spectrogram, if one is due. Rows are centred on the
     * target on display and keep coming through silence (as empty rows) for as
     * long as there is one, so the picture scrolls evenly.
     */
    private fun spectrumRow(time: Double, audible: Boolean, target: Target?): SpectrumRow? {
        val options = config.spectrogram
        if (!options.enabled || blocks++ % options.everyBlocks != 0L || target == null) return null
        var analysis = spectrum
        if (analysis == null || spectrumTargetId != target.id) {
            analysis = FoldedSpectrum(
                sampleRate,
                target.frequency,
                preset.harmonics,
                FoldedSpectrumOptions(
                    periods = options.periods,
                    maxFrame = (options.maxFrameMs * sampleRate / 1000).roundToInt(),
                    maxPartialHz = config.maxPartialHz,
                    floorFactor = options.floorFactor,
                    minSnrDb = options.minSnrDb,
                    fadeInDb = options.fadeInDb,
                    fullScaleDb = options.fullScaleDb,
                ),
            )
            spectrum = analysis
            spectrumTargetId = target.id
            spectrumFrame = FloatArray(analysis.frameLength)
        }
        if (!ring.copyLatest(spectrumFrame)) return null
        val levels = FloatArray(SPECTRUM_COLUMNS)
        if (audible) analysis.compute(spectrumFrame, levels)
        return SpectrumRow(
            // The row describes the audio in its window, which is centred half a window back.
            time = time - analysis.frameLength / (2.0 * sampleRate),
            duration = options.everyBlocks * blockSize.toDouble() / sampleRate,
            targetId = target.id,
            levels = levels,
        )
    }

    /** Switches to an instrument's own strings once the notes heard so far single one out. */
    private fun identify(frequency: Double, time: Double) {
        identified = identifier.observe(frequency, config.a4) ?: return
        rebuild()
        // The note that settled it is still sounding: carry the lock over to the string it belongs to.
        if (config.smartFilter == SmartFilterMode.AUTO && lock == null) {
            val string = targets.nearest(frequency)
            engage(string, centsBetween(frequency, string.frequency), pinned = false, time)
        }
    }

    /** Locked mode: trust the focused detector, use the wide one to notice that the player moved on. */
    private fun trackLocked(time: Double, audible: Boolean, wide: PitchEstimate?, settled: Consensus?): Pitch? {
        val lock = lock!!
        var focus: FocusedEstimate? = null
        if (audible && ring.copyLatest(lock.frame)) focus = lock.detector.detect(lock.frame, lock.widthCents)

        val elsewhere = !lock.pinned && settled != null && settled.target.id != lock.target.id

        if (focus != null && focus.valid) {
            // The wide detector steadily names another string. Is that a new note, or the same
            // mixture of ringing strings heard differently? It depends on how the two relate.
            var newNote = elsewhere
            if (newNote && wide != null && settled != null) {
                val above = overtoneNumber(wide.frequency, focus.frequency)
                val below = overtoneNumber(focus.frequency, wide.frequency)
                newNote = when {
                    // An overtone of what the comb tracks is usually the wide detector's own
                    // octave error - the mistake the lock exists to prevent - unless the string's
                    // own partials say the sound really has moved up there.
                    above > 1 -> focus.profile.supportFor(above) < config.lock.minOvertoneSupport
                    // A note that what the comb tracks is an overtone of: with both strings
                    // ringing, their common period is the lower one's, so the wide detector says
                    // that. Believe it only if that string has just been plucked.
                    below > 1 -> settled.fresh
                    else -> true
                }
            }
            if (!newNote) {
                lock.lastHeard = time
                val target = followTarget(focus.frequency, time)
                return Pitch(focus.frequency, target, PitchSource.FOCUSED, focus.profile.salience, lock.frame.size)
            }
        }

        if (elsewhere && settled != null && wide != null) {
            // A different note is clearly and steadily sounding: move the filter to it.
            engage(settled.target, settled.cents, pinned = false, time)
            return shown(wide, settled.target)
        }

        if (wide != null && abs(centsBetween(wide.frequency, lock.target.frequency)) <= lock.captureCents) {
            // The comb lost the string (e.g. it was re-tuned in a jump) but it is still in range.
            lock.lastHeard = time
            return shown(wide, lock.target)
        }

        if (!lock.pinned && (time - lock.lastHeard) * 1000 > config.lock.holdMs) this.lock = null
        return null
    }

    /** A wide-band reading for display, if it is clear enough to be shown at all. */
    private fun shown(wide: PitchEstimate, target: Target): Pitch? =
        if (wide.clarity >= config.wide.minClarity) Pitch(wide.frequency, target, PitchSource.WIDE, wide.clarity, wideFrame.size) else null

    /**
     * Hand the lock over to a neighbouring target when the string has been tuned
     * past the midpoint (chromatic mode: into the next semitone). Returns the
     * target the current frame should be reported against.
     */
    private fun followTarget(frequency: Double, time: Double): Target {
        val lock = lock!!
        if (lock.pinned) return lock.target
        val nearest = targets.nearest(frequency)
        val toLocked = abs(centsBetween(frequency, lock.target.frequency))
        val toNearest = abs(centsBetween(frequency, nearest.frequency))
        if (nearest.id == lock.target.id || toLocked - toNearest < config.lock.handoffHysteresisCents) {
            lock.handoffFrames = 0
            return lock.target
        }
        if (++lock.handoffFrames < config.lock.handoffFrames) return lock.target
        engage(nearest, centsBetween(frequency, nearest.frequency), pinned = false, time)
        return nearest
    }

    private fun engage(target: Target, offsetCents: Double, pinned: Boolean, time: Double) {
        val focus = config.focus
        val captureCents = if (pinned) focus.pinnedCaptureCents else targets.captureCents(target)
        val detector = detectors.getOrPut("${target.id}:$pinned") {
            FocusedDetector(
                pool,
                sampleRate,
                target.frequency,
                preset.harmonics,
                FocusedDetectorOptions(
                    periods = focus.periods,
                    maxFrame = (focus.maxFrameMs * sampleRate / 1000).roundToInt(),
                    minPeriods = focus.minPeriods,
                    minFrame = (focus.minFrameMs * sampleRate / 1000).roundToInt(),
                    maxWidthCents = captureCents,
                    maxPartialHz = config.maxPartialHz,
                    minPeak = focus.minPeak,
                    minProminence = focus.minProminence,
                    minExplained = focus.minExplained,
                    minFundamentalSupport = focus.minFundamentalSupport,
                    maxSubharmonic = if (pinned) focus.pinnedMaxSubharmonic else focus.maxSubharmonic,
                    blockSize = blockSize,
                    fresh = freshOptions(guard = 0),
                ),
            )
        }
        // Let it learn what was sounding before this note, from the audio still in the buffer.
        detector.begin(pluckedAt?.let { samples - it }) { samplesAgo, frame -> ring.copyLatest(frame, samplesAgo) }
        lock = Lock(
            target = target,
            pinned = pinned,
            detector = detector,
            captureCents = captureCents,
            widthCents = (abs(offsetCents) + focus.marginCents).coerceIn(focus.marginCents, captureCents),
            lastHeard = time,
        )
    }

    /**
     * Keeps the comb just wide enough for where the string currently is: it opens
     * at once when the pitch moves away and closes gradually as the string nears
     * the target, so the filter is at its most selective when precision matters.
     */
    private fun adaptComb(pitch: Pitch?) {
        val lock = lock ?: return
        val focus = config.focus
        if (pitch != null && pitch.target.id == lock.target.id) {
            val offset = abs(centsBetween(pitch.frequency, lock.target.frequency))
            val wanted = (offset + focus.marginCents).coerceIn(focus.marginCents, lock.captureCents)
            lock.widthCents =
                if (wanted >= lock.widthCents) wanted else lock.widthCents + focus.narrowingRate * (wanted - lock.widthCents)
            lock.misses = 0
        } else if (++lock.misses >= config.lock.reopenAfterFrames) {
            lock.widthCents = lock.captureCents
        }
    }

    private fun rebuild() {
        // An instrument setting that names no tuning in use (it was switched off or deleted) means automatic.
        val configured = findInstrument(config.instrumentId, config.tunings)
        if (configured == null) config = config.copy(instrumentId = AUTO_INSTRUMENT_ID, pinnedTargetId = null)
        preset = identified ?: configured ?: automaticPreset(config.tunings)
        targets = createTargetSet(
            preset,
            config.a4,
            TargetSetOptions(
                rangeMarginCents = config.wide.rangeMarginCents,
                captureOverlapCents = config.focus.captureOverlapCents,
                maxCaptureCents = config.focus.maxCaptureCents,
            ),
        )
        val minHz = targets.minHz

        val longestLag = ceil(sampleRate / minHz).toInt() + 4
        val wideLength = max(config.wide.minFrameMs * sampleRate / 1000, config.wide.periods * sampleRate / minHz).roundToInt()
        wideAnalyzer = pool.get(sampleRate, nextPowerOfTwo(wideLength + longestLag), wideLength)
        wideDetector = WideDetector(
            wideAnalyzer,
            minHz,
            targets.maxHz,
            config.wide.peakPickRatio,
            config.maxPartialHz,
            // Compare only with audio that ended before the current window began.
            freshOptions(guard = (wideLength + blockSize - 1) / blockSize + 1),
        )
        widePower = DoubleArray(wideAnalyzer.bins)
        wideFrame = FloatArray(wideLength)

        val capacity = ceil(
            maxOf(
                wideLength.toDouble(),
                config.focus.maxFrameMs * sampleRate / 1000,
                config.focus.minFrameMs * sampleRate / 1000,
                config.focus.minPeriods * sampleRate / minHz,
                config.spectrogram.maxFrameMs * sampleRate / 1000,
                config.fresh.lookBackMs * sampleRate / 1000,
            ),
        ).toInt()
        if (!::ring.isInitialized || ring.capacity != capacity) ring = RingBuffer(capacity)
        if (samples == 0L) ring.clear()

        val blockMs = blockSize * 1000.0 / sampleRate
        stability = StabilityWindow(ceil(config.lock.stableMs / blockMs).toInt(), config.lock.maxSpreadCents)
        smoother = ReadingSmoother(config.display, blockSize.toDouble() / sampleRate)
        detectors.clear()
        lock = null
        pluckedAt = null
        spectrum = null
        spectrumTargetId = null

        // A pin only makes sense for a target of the current instrument.
        val pinned = config.pinnedTargetId?.let(targets::byId)
        if (pinned != null) {
            engage(pinned, config.focus.pinnedCaptureCents, pinned = true, samples.toDouble() / sampleRate)
        } else if (config.pinnedTargetId != null) {
            config = config.copy(pinnedTargetId = null)
        }
    }

    private fun freshOptions(guard: Int): FreshOptions? {
        val fresh = config.fresh
        if (!fresh.enabled) return null
        val blockMs = blockSize * 1000.0 / sampleRate
        val stride = max(1, (fresh.stepMs / blockMs).roundToInt())
        return FreshOptions(
            entries = max(2, ceil(fresh.memoryMs / (stride * blockMs)).toInt()),
            stride = stride,
            guard = guard,
            fadeFrames = fresh.fadeMs / blockMs,
            onsetRise = fresh.onsetRise,
            minShare = fresh.minShare,
            minClarity = fresh.minClarity,
        )
    }

    private fun reading(
        time: Double,
        levelDb: Double,
        pitch: Pitch?,
        cents: Double?,
        inTune: Boolean,
        spectrum: SpectrumRow? = null,
    ): TunerReading {
        val lock = lock
        return TunerReading(
            time = time,
            delay = if (pitch == null) 0.0 else pitch.window / 2.0 / sampleRate + smoother.delay,
            instrumentId = preset.id,
            mode = if (lock != null) TunerMode.LOCKED else TunerMode.SEARCH,
            levelDb = levelDb,
            source = pitch?.source,
            target = pitch?.target ?: lock?.target,
            frequency = if (pitch != null && cents != null) pitch.target.frequency * centsToRatio(cents) else null,
            cents = cents,
            confidence = pitch?.confidence ?: 0.0,
            inTune = inTune,
            lock = lock?.let { LockInfo(it.target, it.pinned, it.widthCents) },
            spectrum = spectrum,
        )
    }
}

/** Which multiple of [fundamental] the [frequency] is (1 = the same pitch), or 0 if it is none of them. */
private fun overtoneNumber(frequency: Double, fundamental: Double): Int {
    val ratio = frequency / fundamental
    val multiple = ratio.roundToInt()
    return if (multiple >= 1 && abs(1200 * log2(ratio / multiple)) <= 40) multiple else 0
}
