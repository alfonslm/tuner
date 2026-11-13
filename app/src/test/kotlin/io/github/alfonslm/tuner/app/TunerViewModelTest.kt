package io.github.alfonslm.tuner.app

import io.github.alfonslm.tuner.app.audio.AudioSource
import io.github.alfonslm.tuner.app.audio.AudioStream
import io.github.alfonslm.tuner.engine.PitchSource
import io.github.alfonslm.tuner.engine.SmartFilterMode
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

private const val SAMPLE_RATE = 48_000
private const val A2 = 110.0

/** Drives the real pipeline (view model → flow → engine) with a synthetic microphone. */
@OptIn(ExperimentalCoroutinesApi::class)
class TunerViewModelTest {
    private val audioThread = Executors.newSingleThreadExecutor()
    private val audioDispatcher = audioThread.asCoroutineDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        // Let the audio loop wind down before the main dispatcher it reports to goes away.
        audioThread.shutdown()
        assertTrue(audioThread.awaitTermination(5, TimeUnit.SECONDS), "audio thread still running")
        Dispatchers.resetMain()
    }

    private class MemorySettings(var saved: Settings = Settings()) : SettingsStore {
        override fun load() = saved

        override fun save(settings: Settings) {
            saved = settings
        }
    }

    /**
     * String-like tones delivered slightly faster than real time: each frequency
     * for [secondsEach], the last one forever. Every open starts from the beginning.
     */
    private class ToneSource(private vararg val frequencies: Double, private val secondsEach: Double = 1.0) : AudioSource {
        val opened = AtomicInteger()
        val closed = AtomicInteger()

        override fun open(): AudioStream {
            opened.incrementAndGet()
            return object : AudioStream {
                private var n = 0L
                private var phase = 0.0
                override val sampleRate = SAMPLE_RATE

                override fun read(block: FloatArray): Boolean {
                    Thread.sleep(2)
                    for (i in block.indices) {
                        val index = (n++ / (secondsEach * SAMPLE_RATE)).toInt().coerceAtMost(frequencies.lastIndex)
                        phase += 2 * PI * frequencies[index] / SAMPLE_RATE
                        block[i] = (0.2 * (sin(phase) + 0.5 * sin(2 * phase) + 0.3 * sin(3 * phase))).toFloat()
                    }
                    return true
                }

                override fun close() {
                    closed.incrementAndGet()
                }
            }
        }
    }

    private val guitar = Settings(instrumentId = "guitar")

    private fun viewModel(source: AudioSource, settings: SettingsStore = MemorySettings(guitar)) =
        TunerViewModel(source, settings, audioDispatcher)

    private fun TunerViewModel.await(what: String, predicate: (TunerUiState) -> Boolean): TunerUiState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            state.value.takeIf(predicate)?.let { return it }
            Thread.sleep(5)
        }
        fail("Timed out waiting for: $what. Last state: ${state.value}")
    }

    @Test
    fun `a sounding string is found, locked onto and shown with its offset`() {
        val viewModel = viewModel(ToneSource(A2 * 2.0.pow(7.0 / 1200)))
        viewModel.start()
        val state = viewModel.await("lock on A2") { it.latest?.lock != null && it.shown?.source == PitchSource.FOCUSED }
        assertTrue(state.running && state.live)
        assertEquals("A2", state.shown!!.target!!.label)
        assertTrue(abs(state.shown.cents!! - 7) < 1, "cents: ${state.shown.cents}")

        // The waterfall follows along: a steady tone leaves a steady history.
        val traced = viewModel.await("a second of history") { it.trace.points.size >= 40 }
        assertTrue(traced.trace.points.takeLast(20).all { abs(it.cents - 7) < 1 })
        assertEquals(traced.shown!!.target!!.id, traced.trace.targetId)
        viewModel.stop()
        assertTrue(viewModel.state.value.trace.points.isEmpty())
    }

    @Test
    fun `stopping releases the microphone and clears the display`() {
        val source = ToneSource(A2)
        val viewModel = viewModel(source)
        viewModel.start()
        viewModel.start() // a second press must not open a second stream
        viewModel.await("first reading") { it.shown != null }

        viewModel.stop()

        viewModel.await("stream closed") { source.closed.get() == 1 }
        assertEquals(1, source.opened.get())
        with(viewModel.state.value) {
            assertTrue(!running)
            assertNull(latest)
            assertNull(shown)
        }
    }

    @Test
    fun `a microphone that cannot be opened is reported and the tuner stops`() {
        val viewModel = viewModel(AudioSource { throw IOException("busy") })
        viewModel.start()
        val state = viewModel.await("error") { it.error != null }
        assertEquals(TunerError.MICROPHONE_UNAVAILABLE, state.error)
        viewModel.await("stopped") { !it.running }
    }

    @Test
    fun `settings changes reach the running engine`() {
        val viewModel = viewModel(ToneSource(A2))
        viewModel.start()
        viewModel.await("lock") { it.latest?.lock != null }

        viewModel.setSmartFilter(false)
        viewModel.await("wide-band reading without a lock") { it.latest?.lock == null && it.latest?.source == PitchSource.WIDE }

        // Pinning a different string makes the tuner deaf to this one.
        val d3 = viewModel.state.value.strings.first { it.label == "D3" }
        viewModel.togglePin(d3.id)
        viewModel.await("pinned to D3, A2 ignored") { it.latest?.lock?.pinned == true && it.latest.lock?.target?.label == "D3" && !it.live }
        viewModel.stop()
    }

    @Test
    fun `automatic mode recognises the instrument from two strings and offers its strings`() {
        val e2 = 82.41
        val source = ToneSource(e2, A2)
        val viewModel = viewModel(source, MemorySettings())
        assertEquals("auto", viewModel.state.value.config.instrumentId)
        assertTrue(viewModel.state.value.strings.isEmpty())

        viewModel.start()
        // One string: tuned chromatically, instrument still unknown.
        val first = viewModel.await("first string") { it.shown?.target?.label == "E2" }
        assertNull(first.detectedInstrument)
        assertTrue(first.strings.isEmpty())

        val known = viewModel.await("guitar recognised") { it.detectedInstrument != null && it.shown?.target?.id == "s1:A2" }
        assertEquals("guitar", known.detectedInstrument!!.id)
        assertEquals(listOf("E2", "A2", "D3", "G3", "B3", "E4"), known.strings.map { it.label })
        assertEquals("auto", known.config.instrumentId) // the setting itself stays on Automatic

        // The recognised instrument's strings can be pinned like any other.
        viewModel.togglePin("s2:D3")
        viewModel.await("pinned to D3") { it.latest?.lock?.pinned == true && it.detectedInstrument?.id == "guitar" }

        // Choosing Automatic again starts over: new session, nothing recognised, no pin.
        viewModel.setInstrument("auto")
        with(viewModel.state.value) {
            assertTrue(running)
            assertNull(detectedInstrument)
            assertNull(config.pinnedTargetId)
            assertTrue(strings.isEmpty())
        }
        viewModel.await("recognised again") { it.detectedInstrument?.id == "guitar" }
        assertEquals(2, source.opened.get())

        viewModel.stop()
        assertNull(viewModel.state.value.detectedInstrument)
        assertTrue(viewModel.state.value.strings.isEmpty())
    }

    @Test
    fun `settings are restored, saved on change, and a pin does not outlive its instrument`() {
        val settings = MemorySettings(Settings(instrumentId = "violin", a4 = 442.0, smartFilter = SmartFilterMode.OFF))
        val viewModel = viewModel(ToneSource(A2), settings)
        with(viewModel.state.value) {
            assertEquals(listOf("G3", "D4", "A4", "E5"), strings.map { it.label })
            assertEquals(442.0, config.a4)
            assertEquals(SmartFilterMode.OFF, config.smartFilter)
        }

        viewModel.togglePin(viewModel.state.value.strings[1].id)
        assertEquals(viewModel.state.value.strings[1].id, viewModel.state.value.config.pinnedTargetId)
        viewModel.togglePin(viewModel.state.value.strings[1].id)
        assertNull(viewModel.state.value.config.pinnedTargetId)

        viewModel.togglePin(viewModel.state.value.strings[1].id)
        viewModel.setInstrument("guitar")
        viewModel.setReferencePitch(500.0) // out of range: clamped
        with(viewModel.state.value) {
            assertNull(config.pinnedTargetId)
            assertEquals(6, strings.size)
            assertEquals(466.0, config.a4)
        }
        assertEquals(Settings("guitar", 466.0, SmartFilterMode.OFF), settings.saved)

        viewModel.setInstrument("chromatic")
        assertTrue(viewModel.state.value.strings.isEmpty())
    }
}
