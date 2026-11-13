package io.github.alfonslm.tuner

import io.github.alfonslm.tuner.dsp.SpectralAnalyzer
import io.github.alfonslm.tuner.engine.TunerConfig
import io.github.alfonslm.tuner.engine.TunerEngine
import io.github.alfonslm.tuner.engine.TunerReading
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test

/** Reads a mono 32-bit float WAV file (`ffmpeg -i in.m4a -ac 1 -ar 48000 -c:a pcm_f32le out.wav`). */
fun readWav(file: File): Pair<Int, FloatArray> {
    val bytes = file.readBytes()
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var position = 12
    var rate = 0
    while (position + 8 <= bytes.size) {
        val id = String(bytes, position, 4)
        val size = buffer.getInt(position + 4)
        if (id == "fmt ") rate = buffer.getInt(position + 12)
        if (id == "data") {
            val count = min(size, bytes.size - position - 8) / 4
            return rate to FloatArray(count) { buffer.getFloat(position + 8 + 4 * it) }
        }
        position += 8 + size + (size and 1)
    }
    error("no data chunk")
}

/**
 * Not tests: reports on what the engine makes of recorded audio, for looking
 * into what a real instrument and microphone do that the synthesizer does not.
 * Each does nothing unless its environment variable is set, and prints lines
 * that start with a tag (run Gradle with `--rerun -i` and grep for it):
 *
 *  - `TUNER_SUMMARY=dir[:dir]` (tag `SUM`): one line per WAV file, each note
 *    the engine heard with when, the median offset, the 5th to 95th percentile,
 *    and `Jn` if the reading jumped by more than 3 cents n times.
 *  - `TUNER_RECORDINGS=dir` (tag `REC`): a timeline of readings every
 *    `TUNER_STEP` seconds (default 0.25), next to the unsmoothed ones.
 *  - `TUNER_PARTIALS=file,f0,from,to,step` (tag `PART`): frequency (cents from
 *    k·f0) and level (dB) of the first eight partials over time, measured with
 *    a long window and nothing of the tuner's own.
 *
 * `TUNER_INSTRUMENTS` (comma separated, default `chromatic` for the summary
 * and `guitar` for the timeline) picks the instrument setting.
 */
class RecordingReport {

    @Test
    fun summary() {
        val dirs = System.getenv("TUNER_SUMMARY") ?: return
        for (dir in dirs.split(":")) for (file in File(dir).listFiles { f -> f.name.endsWith(".wav") }!!.sorted()) {
            val (rate, signal) = readWav(file)
            for (instrument in (System.getenv("TUNER_INSTRUMENTS") ?: "chromatic").split(",")) {
                val engine = TunerEngine(rate, 1024, TunerConfig(instrumentId = instrument))
                val readings = (0..signal.size - 1024 step 1024).map { engine.process(signal.copyOfRange(it, it + 1024)) }
                // Notes: runs of readings on one target, split at gaps of more than 0.3 s.
                val sb = StringBuilder("SUM %-10s %-9s".format(file.name, instrument))
                var run = ArrayList<TunerReading>()
                fun flush() {
                    if (run.size >= 5) {
                        val c = run.map { it.cents!! }.sorted()
                        var jumps = 0
                        for (i in 1 until run.size) if (abs(run[i].cents!! - run[i - 1].cents!!) > 3) jumps++
                        sb.append(" | %s %.1f-%.1fs %+.1f (%+.1f..%+.1f)%s".format(run[0].target!!.label, run.first().time, run.last().time,
                            c[c.size / 2], c[(c.size * 0.05).toInt()], c[min(c.size - 1, (c.size * 0.95).toInt())], if (jumps > 0) " J$jumps" else ""))
                    } else if (run.isNotEmpty()) sb.append(" | ?%s %.1fs %+.0f x%d".format(run[0].target!!.label, run[0].time, run[0].cents, run.size))
                    run = ArrayList()
                }
                for (r in readings) {
                    if (!r.voiced) continue
                    if (run.isNotEmpty() && (r.target!!.id != run.last().target!!.id || r.time - run.last().time > 0.3)) flush()
                    run += r
                }
                flush()
                println(sb)
            }
        }
    }

    @Test
    fun timeline() {
        val dir = System.getenv("TUNER_RECORDINGS") ?: return
        val step = (System.getenv("TUNER_STEP") ?: "0.25").toDouble()
        for (file in File(dir).listFiles { f -> f.name.endsWith(".wav") }!!.sorted()) {
            val (rate, signal) = readWav(file)
            for (instrument in (System.getenv("TUNER_INSTRUMENTS") ?: "guitar").split(",")) {
                val config = TunerConfig(instrumentId = instrument)
                val engine = TunerEngine(rate, 1024, config)
                val rawEngine = TunerEngine(rate, 1024, config.copy(display = config.display.copy(smoothingMs = 0.0, medianFrames = 1)))
                println("REC ${file.name} [$instrument] rate $rate, ${"%.1f".format(signal.size.toDouble() / rate)} s")
                var next = 0.0
                val line = StringBuilder()
                val rawCents = ArrayList<Double>()
                for (start in 0..signal.size - 1024 step 1024) {
                    val block = signal.copyOfRange(start, start + 1024)
                    val r = engine.process(block)
                    val raw = rawEngine.process(block)
                    raw.cents?.let { rawCents += it }
                    if (r.time >= next) {
                        next += step
                        val stripe = r.spectrum?.levels?.let { l -> val i = l.indices.maxBy { l[it] }; if (l[i] > 0.05f) "%+3d".format(i - (l.size - 1) / 2) else " . " } ?: "   "
                        println("REC %5.2fs %6.1fdB conf %.2f %-6s %-4s %s %s raw %s stripe %s  rawRange %s".format(
                            r.time, r.levelDb, r.confidence, r.mode.name.take(6), r.target?.label ?: "-",
                            r.cents?.let { "%+6.1f".format(it) } ?: "   -  ",
                            r.spreadCents?.let { "±%4.1f".format(it) } ?: "     ",
                            raw.cents?.let { "%+6.1f".format(it) } ?: "   -  ", stripe,
                            if (rawCents.isEmpty()) "" else "%+.1f..%+.1f".format(rawCents.min(), rawCents.max())))
                        rawCents.clear()
                    }
                }
            }
        }
    }

    @Test
    fun partials() {
        val spec = System.getenv("TUNER_PARTIALS") ?: return   // file,f0,from,to,step
        val (path, f0s, froms, tos, steps) = spec.split(",")
        val (rate, signal) = readWav(File(path))
        val f0 = f0s.toDouble()
        val frame = 8192
        val analyzer = SpectralAnalyzer(rate, 32768, frame)
        val power = DoubleArray(analyzer.bins)
        var t = froms.toDouble()
        while (t <= tos.toDouble()) {
            val end = (t * rate).toInt()
            if (end - frame >= 0 && end <= signal.size) {
                val rms = analyzer.powerSpectrum(signal.copyOfRange(end - frame, end), power)
                val sb = StringBuilder("PART %5.2fs %6.1fdB |".format(t, 20 * log10(rms + 1e-12)))
                for (k in 1..8) {
                    val lo = (k * f0 * 2.0.pow(-60.0 / 1200) / analyzer.binHz).toInt()
                    val hi = (k * f0 * 2.0.pow(60.0 / 1200) / analyzer.binHz).toInt()
                    var p = lo
                    for (b in lo..hi) if (power[b] > power[p]) p = b
                    val a = ln(power[p - 1]); val b = ln(power[p]); val c = ln(power[p + 1])
                    val shift = 0.5 * (a - c) / (a - 2 * b + c)
                    val cents = 1200 * log2((p + shift) * analyzer.binHz / (k * f0))
                    sb.append(" %+5.1f/%3.0f".format(cents, 10 * log10(power[p] + 1e-30)))
                }
                println(sb)
            }
            t += steps.toDouble()
        }
    }
}
