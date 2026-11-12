package com.terabox.reader.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.terabox.reader.data.SecurityStorage

class MainActivity : ComponentActivity() {

    private lateinit var storage: SecurityStorage
    val sharedTeraBoxUrl = mutableStateOf<String?>(null)
    val volumeKeyEvent = mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = SecurityStorage(this)

        applySettings()
        handleIntent(intent)

        setContent {
            TeraBoxApp(
                activity = this,
                storage = storage,
                incomingUrl = sharedTeraBoxUrl.value,
                volumeKeyEvent = volumeKeyEvent
            )
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        if (data != null) {
            sharedTeraBoxUrl.value = data.toString()
        }
    }

    fun applySettings() {
        val s = storage.getSettings()
        if (s.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        requestedOrientation = if (s.lockOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Bắt phím âm lượng để lật trang/chương
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val s = storage.getSettings()
        if (s.volumeKeyFlip && event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                volumeKeyEvent.value = 1 // Tiếp theo
                return true
            } else if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                volumeKeyEvent.value = -1 // Trước đó
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
