package com.terabox.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.terabox.reader.data.SecurityStorage
import com.terabox.reader.data.TeraBoxFile

@Composable
fun TeraBoxApp(
    activity: MainActivity,
    storage: SecurityStorage,
    incomingUrl: String?,
    volumeKeyEvent: MutableState<Int?>
) {
    var currentScreen by remember { mutableStateOf("browser") }
    var selectedFile by remember { mutableStateOf<TeraBoxFile?>(null) }
    var activeShareUrl by remember { mutableStateOf(incomingUrl ?: "") }

    LaunchedEffect(incomingUrl) {
        if (!incomingUrl.isNullOrEmpty()) {
            activeShareUrl = incomingUrl
            currentScreen = "browser"
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF18191C)
    ) {
        when (currentScreen) {
            "browser" -> {
                BrowserScreen(
                    storage = storage,
                    initialUrl = activeShareUrl,
                    onOpenFile = { file ->
                        selectedFile = file
                        val ext = file.getExtension()
                        if (ext in listOf("cbz", "cbr") || (ext == "pdf")) {
                            currentScreen = "manga_reader"
                        } else {
                            currentScreen = "text_reader"
                        }
                    },
                    onOpenSettings = { currentScreen = "settings" }
                )
            }
            "text_reader" -> {
                TextReaderScreen(
                    file = selectedFile,
                    storage = storage,
                    volumeKeyEvent = volumeKeyEvent,
                    onBack = { currentScreen = "browser" }
                )
            }
            "manga_reader" -> {
                MangaReaderScreen(
                    file = selectedFile,
                    storage = storage,
                    volumeKeyEvent = volumeKeyEvent,
                    onBack = { currentScreen = "browser" }
                )
            }
            "settings" -> {
                SettingsScreen(
                    storage = storage,
                    onBack = {
                        activity.applySettings()
                        currentScreen = "browser"
                    }
                )
            }
        }
    }
}
