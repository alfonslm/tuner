package io.github.alfonslm.tuner.app

import io.github.alfonslm.tuner.app.audio.AudioSource
import io.github.alfonslm.tuner.app.audio.AudioStream
import io.github.alfonslm.tuner.engine.PitchSource
import io.github.alfonslm.tuner.engine.SmartFilterMode
import java.io.IOException
import java.util.concurrent.CountDownLatch
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

    /** A string-like tone that never ends, delivered slightly faster than real time. */
    private class ToneSource(private val frequency: Double) : AudioSource {
        val opened = AtomicInteger()
        val closed = CountDownLatch(1)

        override fun open(): AudioStream {
            opened.incrementAndGet()
            return object : AudioStream {
                private var n = 0L
                override val sampleRate = SAMPLE_RATE

                override fun read(block: FloatArray): Boolean {
                    Thread.sleep(2)
                    for (i in block.indices) {
                        val phase = 2 * PI * frequency * n++ / SAMPLE_RATE
                        block[i] = (0.2 * (sin(phase) + 0.5 * sin(2 * phase) + 0.3 * sin(3 * phase))).toFloat()
                    }
                    return true
                }

                override fun close() = closed.countDown()
            }
        }
    }

    private fun viewModel(source: AudioSource, settings: SettingsStore = MemorySettings()) =
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
        viewModel.stop()
    }

    @Test
    fun `stopping releases the microphone and clears the display`() {
        val source = ToneSource(A2)
        val viewModel = viewModel(source)
        viewModel.start()
        viewModel.start() // a second press must not open a second stream
        viewModel.await("first reading") { it.shown != null }

        viewModel.stop()

        assertTrue(source.closed.await(2, TimeUnit.SECONDS), "stream was not closed")
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
