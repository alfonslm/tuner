package io.github.alfonslm.tuner.app

import io.github.alfonslm.tuner.app.audio.AudioSource
import io.github.alfonslm.tuner.engine.TunerConfig
import io.github.alfonslm.tuner.engine.TunerEngine
import io.github.alfonslm.tuner.engine.TunerReading
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/** Samples per analysis step: about 21 ms at 48 kHz, i.e. roughly 47 readings per second. */
const val BLOCK_SIZE = 1024

/**
 * Audio in, readings out. Collecting the flow opens the source and runs the
 * engine on [audioDispatcher]; cancelling the collection closes the source.
 *
 * The engine lives entirely on the audio thread. Configuration changes reach
 * it through [config], which is checked between blocks, so no locking is
 * needed.
 *
 * Analysis is never held back by the UI: readings queue up for a collector
 * that falls behind, and if it falls more than about a second behind the
 * oldest are dropped. (They are queued rather than conflated because some of
 * them carry a row of the spectrogram, which would leave a hole if skipped.)
 */
fun tunerReadings(
    source: AudioSource,
    config: StateFlow<TunerConfig>,
    audioDispatcher: CoroutineDispatcher,
): Flow<TunerReading> = flow {
    source.open().use { stream ->
        var applied = config.value
        val engine = TunerEngine(stream.sampleRate, BLOCK_SIZE, applied)
        val block = FloatArray(BLOCK_SIZE)
        while (currentCoroutineContext().isActive && stream.read(block)) {
            val wanted = config.value
            if (wanted != applied) {
                engine.configure(wanted)
                applied = wanted
            }
            emit(engine.process(block))
        }
    }
}.flowOn(audioDispatcher).buffer(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
