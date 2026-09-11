package com.ayuemin.ymnik.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.max

class WavRecorder(private val context: Context) {
    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
    }

    @Volatile private var recording = false
    private var audioRecord: AudioRecord? = null
    private var writerThread: Thread? = null
    private var targetFile: File? = null

    val isRecording: Boolean get() = recording

    @SuppressLint("MissingPermission")
    fun start(): File {
        if (recording) return targetFile ?: error("Запись уже идёт")
        cleanupOldRecordings()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) error("Не удалось подготовить микрофон")
        val bufferSize = max(minBuffer * 2, 4096)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error("Микрофон недоступен")
        }

        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        val file = File(dir, "voice_${System.currentTimeMillis()}.wav")
        FileOutputStream(file).use { output -> output.write(wavHeader(0)) }

        audioRecord = recorder
        targetFile = file
        recording = true
        recorder.startRecording()

        writerThread = thread(name = "UmnikVoiceRecorder") {
            val buffer = ByteArray(bufferSize)
            FileOutputStream(file, true).use { output ->
                while (recording) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) output.write(buffer, 0, read)
                }
                output.flush()
            }
        }
        return file
    }

    fun stop(): File? {
        val file = targetFile ?: return null
        if (!recording) return file.takeIf { it.isFile && it.length() > 44 }

        recording = false
        val recorder = audioRecord
        runCatching { recorder?.stop() }
        runCatching { writerThread?.join(2500) }
        runCatching { recorder?.release() }
        audioRecord = null
        writerThread = null

        if (!file.isFile || file.length() <= 44L) {
            file.delete()
            targetFile = null
            return null
        }
        patchHeader(file)
        targetFile = null
        return file
    }

    fun cancel() {
        val file = if (recording) stop() else targetFile
        file?.delete()
        targetFile = null
    }

    private fun patchHeader(file: File) {
        val dataSize = (file.length() - 44L).coerceAtLeast(0L)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(wavHeader(dataSize))
        }
    }

    private fun wavHeader(dataSize: Long): ByteArray {
        val safeData = dataSize.coerceAtMost(0xFFFF_FFFFL).toInt()
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + safeData)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort())
            putShort(CHANNELS.toShort())
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(BITS_PER_SAMPLE.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(safeData)
        }.array()
    }

    private fun cleanupOldRecordings() {
        val dir = File(context.cacheDir, "voice")
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}
