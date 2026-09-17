package com.ayuemin.umniklab

import android.Manifest
import android.app.Activity
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var apiField: EditText
    private lateinit var promptField: EditText
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val refresher = object : Runnable {
        override fun run() {
            refreshOutput()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationsIfNeeded()
        setContentView(buildUi())
        LabState.log(this, "APP", "opened Android=${Build.VERSION.SDK_INT} pid=${android.os.Process.myPid()}")
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "Umnik Background Lab"
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "Чистый тест фоновой сети: FGS и Android 14+ UIDT. API-ключ хранится только в приватных данных этого тестового APK."
            textSize = 14f
            setPadding(0, dp(6), 0, dp(12))
        })

        apiField = EditText(this).apply {
            hint = "OpenRouter API key"
            setSingleLine(true)
            setText(LabState.apiKey(this@MainActivity))
        }
        root.addView(apiField)

        promptField = EditText(this).apply {
            hint = "Длинный тестовый запрос"
            minLines = 4
            maxLines = 8
            setText(LabState.prompt(this@MainActivity).ifBlank { LabState.DEFAULT_PROMPT })
        }
        root.addView(promptField)

        root.addView(Button(this).apply {
            text = "1. FGS test"
            setOnClickListener { startFgsTest() }
        })

        root.addView(Button(this).apply {
            text = if (Build.VERSION.SDK_INT >= 34) "2. UIDT test" else "2. UIDT test (нужен Android 14+)"
            isEnabled = Build.VERSION.SDK_INT >= 34
            setOnClickListener { startUidtTest() }
        })

        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(12), 0, dp(8))
        }
        root.addView(statusView)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        controls.addView(Button(this).apply {
            text = "Копировать лог"
            setOnClickListener {
                val text = LabState.readLog(this@MainActivity)
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("background-lab.log", text))
                Toast.makeText(this@MainActivity, "Лог скопирован", Toast.LENGTH_SHORT).show()
            }
        })
        controls.addView(Button(this).apply {
            text = "Очистить"
            setOnClickListener {
                LabState.clearLog(this@MainActivity)
                refreshOutput()
            }
        })
        root.addView(controls)

        logView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(8), 0, dp(16))
        }

        val scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        refreshOutput()
        return root
    }

    private fun validateAndSave(): Boolean {
        val key = apiField.text.toString().trim()
        if (key.isBlank()) {
            Toast.makeText(this, "Введите OpenRouter API key", Toast.LENGTH_LONG).show()
            return false
        }
        val prompt = promptField.text.toString().ifBlank { LabState.DEFAULT_PROMPT }
        LabState.saveInput(this, key, prompt)
        return true
    }

    private fun startFgsTest() {
        if (!validateAndSave()) return
        LabState.clearLog(this)
        LabState.log(this, "APP", "FGS test requested while activity visible")
        val intent = Intent(this, LabForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "FGS запущен. Теперь погасите экран.", Toast.LENGTH_LONG).show()
        refreshOutput()
    }

    private fun startUidtTest() {
        if (Build.VERSION.SDK_INT < 34 || !validateAndSave()) return
        LabState.clearLog(this)
        LabState.log(this, "APP", "UIDT test requested while activity visible")

        val job = JobInfo.Builder(JOB_ID, ComponentName(this, LabUidtJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setEstimatedNetworkBytes(256_000L, 2_000_000L)
            .build()

        val scheduler = getSystemService(JobScheduler::class.java)
        val result = scheduler.schedule(job)
        LabState.log(this, "APP", "UIDT schedule result=$result jobId=$JOB_ID")
        if (result == JobScheduler.RESULT_SUCCESS) {
            Toast.makeText(this, "UIDT запущен. Теперь погасите экран.", Toast.LENGTH_LONG).show()
        } else {
            LabState.setStatus(this, "UIDT не удалось запланировать: result=$result")
        }
        refreshOutput()
    }

    private fun refreshOutput() {
        if (!::statusView.isInitialized || !::logView.isInitialized) return
        statusView.text = "Статус: ${LabState.status(this)}"
        logView.text = LabState.readLog(this)
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
        }
    }

    companion object {
        private const val JOB_ID = 7301
    }
}
