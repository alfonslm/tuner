package io.github.alfonslm.tuner.app.audio

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.annotation.RequiresPermission
import java.io.IOException
import kotlin.math.max

/**
 * The phone microphone, as raw as Android will give it.
 *
 * Phones process microphone audio for speech by default: noise suppression
 * treats a sustained string as background noise and removes it, automatic gain
 * pumps the level, and both smear the partials the tuner measures. So:
 *  - the UNPROCESSED source is used where the device supports it, otherwise
 *    VOICE_RECOGNITION (the least processed source every device must offer);
 *  - any noise suppressor / AGC / echo canceller the device still attaches to
 *    the recording session is switched off explicitly;
 *  - samples are read as float PCM, so there is no clipping or re-scaling.
 */
class MicrophoneSource(private val context: Context) : AudioSource {

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun open(): AudioStream {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val unprocessed = audioManager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION

        for (sampleRate in SAMPLE_RATES) {
            val record = createRecord(source, sampleRate) ?: continue
            val effects = disableVoiceProcessing(record.audioSessionId)
            record.startRecording()
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                return MicrophoneStream(record, sampleRate, effects)
            }
            effects.forEach(AudioEffect::release)
            record.release()
        }
        throw IOException("Could not open the microphone")
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createRecord(source: Int, sampleRate: Int): AudioRecord? {
        val minBytes = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBytes <= 0) return null
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .build(),
                )
                // Room for about a third of a second, so a slow analysis frame never drops audio.
                .setBufferSizeInBytes(max(minBytes, sampleRate / 3 * Float.SIZE_BYTES))
                .build()
        } catch (_: UnsupportedOperationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        return record
    }

    private fun disableVoiceProcessing(sessionId: Int): List<AudioEffect> {
        val effects = ArrayList<AudioEffect>(3)
        fun switchOff(available: Boolean, create: () -> AudioEffect?) {
            if (!available) return
            // Some devices advertise an effect and then fail to create it; that just means there is nothing to disable.
            val effect = runCatching(create).getOrNull() ?: return
            runCatching { effect.enabled = false }
            effects += effect
        }
        switchOff(NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(sessionId) }
        switchOff(AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(sessionId) }
        switchOff(AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(sessionId) }
        return effects
    }

    private class MicrophoneStream(
        private val record: AudioRecord,
        override val sampleRate: Int,
        private val effects: List<AudioEffect>,
    ) : AudioStream {

        override fun read(block: FloatArray): Boolean =
            record.read(block, 0, block.size, AudioRecord.READ_BLOCKING) == block.size

        override fun close() {
            runCatching { record.stop() }
            effects.forEach(AudioEffect::release)
            record.release()
        }
    }

    private companion object {
        val SAMPLE_RATES = intArrayOf(48_000, 44_100)
    }
}
