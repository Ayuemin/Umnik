package com.ayuemin.umniklab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class LabUidtJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    private val startedAt = ConcurrentHashMap<Int, Long>()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        LabState.log(this, "UIDT", "job service created pid=${android.os.Process.myPid()}")
    }

    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        running = true
        startedAt[params.jobId] = SystemClock.elapsedRealtime()
        setNotification(
            params,
            NOTIFICATION_ID,
            notification("UIDT test выполняется"),
            JOB_END_NOTIFICATION_POLICY_REMOVE
        )
        LabState.setStatus(this, "UIDT test: запрос выполняется")
        LabState.log(this, "UIDT", "onStartJob jobId=${params.jobId} userInitiated=true")

        executor.execute {
            try {
                val result = LabNetwork.execute(this, "UIDT")
                val elapsed = elapsedMs(params.jobId)
                LabState.setStatus(
                    this,
                    "UIDT SUCCESS · ${result.elapsedMs / 1000}s · ${result.textChars} символов"
                )
                LabState.log(
                    this,
                    "UIDT",
                    "SUCCESS jobId=${params.jobId} lifetime=${elapsed}ms generation=${result.generationId ?: "none"}"
                )
            } catch (t: Throwable) {
                val elapsed = elapsedMs(params.jobId)
                LabState.setStatus(this, "UIDT FAIL · ${t::class.java.simpleName}: ${t.message.orEmpty()}")
                LabState.log(this, "UIDT", "FAIL jobId=${params.jobId} lifetime=${elapsed}ms", t)
            } finally {
                running = false
                startedAt.remove(params.jobId)
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val reason = if (Build.VERSION.SDK_INT >= 31) params.stopReason else -1
        val elapsed = elapsedMs(params.jobId)
        LabState.log(
            this,
            "UIDT",
            "onStopJob jobId=${params.jobId} running=$running stopReason=$reason lifetime=${elapsed}ms"
        )
        if (running) LabNetwork.cancel()
        running = false
        startedAt.remove(params.jobId)
        return false
    }

    private fun elapsedMs(jobId: Int): Long {
        val start = startedAt[jobId] ?: return -1L
        return (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
    }

    private fun notification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("Umnik Background Lab · UIDT")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Background Lab UIDT", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        LabState.log(this, "UIDT", "job service destroyed running=$running")
        if (running) LabNetwork.cancel()
        executor.shutdownNow()
        startedAt.clear()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "lab_uidt"
        private const val NOTIFICATION_ID = 7201
    }
}
