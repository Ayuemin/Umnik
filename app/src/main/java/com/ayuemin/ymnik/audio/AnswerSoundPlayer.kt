package com.ayuemin.ymnik.audio

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import com.ayuemin.ymnik.model.AnswerSoundChoice
import com.ayuemin.ymnik.model.UiState
import java.io.File

/** Plays the optional completion sound without coupling Android media APIs to ChatViewModel. */
internal class AnswerSoundPlayer {
    fun play(state: UiState) {
        if (!state.answerSoundEnabled) return

        if (state.answerSoundChoice == AnswerSoundChoice.CUSTOM) {
            val custom = state.answerSoundCustomPath?.let(::File)
            if (custom?.isFile == true) {
                runCatching {
                    val volume = state.answerSoundVolume.coerceIn(0, 100) / 100f
                    MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        setDataSource(custom.absolutePath)
                        setVolume(volume, volume)
                        setOnPreparedListener { it.start() }
                        setOnCompletionListener { it.release() }
                        setOnErrorListener { player, _, _ ->
                            player.release()
                            true
                        }
                        prepareAsync()
                    }
                    return
                }
            }
        }

        runCatching {
            val tone = ToneGenerator(
                AudioManager.STREAM_MUSIC,
                state.answerSoundVolume.coerceIn(0, 100)
            )
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 90)
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { tone.release() } }, 180)
        }
    }
}
