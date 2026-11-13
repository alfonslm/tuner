package io.github.alfonslm.tuner.app

import io.github.alfonslm.tuner.app.audio.AudioSource
import io.github.alfonslm.tuner.engine.TunerConfig
import io.github.alfonslm.tuner.engine.TunerEngine
import io.github.alfonslm.tuner.engine.TunerReading
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
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
 * needed. A collector that is slower than the audio only ever sees the latest
 * reading: analysis is never held back by the UI.
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
}.flowOn(audioDispatcher).conflate()
