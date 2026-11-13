package io.github.alfonslm.tuner.app.audio

import java.io.Closeable

/** Where the tuner's audio comes from. The microphone in the app, a synthetic signal in tests. */
fun interface AudioSource {
    /** Starts capturing. Throws if the source cannot be opened. */
    fun open(): AudioStream
}

interface AudioStream : Closeable {
    val sampleRate: Int

    /** Blocks until [block] has been filled completely. Returns false when the stream has ended or failed. */
    fun read(block: FloatArray): Boolean
}
