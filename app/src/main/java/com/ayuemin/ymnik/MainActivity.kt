package com.ayuemin.ymnik

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.ayuemin.ymnik.ui.YmnikApp

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels { ChatViewModel.Factory(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { YmnikApp(viewModel) }
    }
}
