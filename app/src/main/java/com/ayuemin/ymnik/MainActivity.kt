package com.ayuemin.ymnik

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import com.ayuemin.ymnik.ui.UmnikV16Root

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels { ChatViewModel.Factory(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLog.installCrashHandler(applicationContext)
        DiagnosticLog.recordPreviousProcessExit(applicationContext)
        enableEdgeToEdge()
        setContent { UmnikV16Root(viewModel) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val orientation = when (newConfig.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            else -> "undefined"
        }
        DiagnosticLog.record(
            applicationContext,
            "LIFECYCLE",
            "MainActivity onConfigurationChanged; orientation=$orientation; shell=${AsyncJobEvents.shellActivity.value != null}"
        )
    }

    override fun onStart() {
        super.onStart()
        DiagnosticLog.record(
            applicationContext,
            "LIFECYCLE",
            "MainActivity onStart; shell=${AsyncJobEvents.shellActivity.value != null}"
        )
    }

    override fun onStop() {
        DiagnosticLog.record(
            applicationContext,
            "LIFECYCLE",
            "MainActivity onStop; shell=${AsyncJobEvents.shellActivity.value != null}"
        )
        super.onStop()
    }
}
