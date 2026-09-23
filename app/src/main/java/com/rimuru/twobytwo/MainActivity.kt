package com.rimuru.twobytwo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rimuru.twobytwo.presentation.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // S1: Share-to-app (ACTION_SEND image/*) — prefill the picked photo
        val sharedUri = if (intent?.action == android.content.Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
        } else null

        setContent {
            AppRoot(initialSharedUri = sharedUri?.toString())
        }
    }
}
