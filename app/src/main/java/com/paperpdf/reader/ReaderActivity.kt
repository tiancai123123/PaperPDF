package com.paperpdf.reader

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.paperpdf.reader.ui.ReaderRequest
import com.paperpdf.reader.ui.ReaderScreen
import com.paperpdf.reader.ui.theme.PaperTheme

class ReaderActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = ReaderWindowLauncher.requestFrom(this, intent)
        if (request == null) {
            finishAndRemoveTask()
            return
        }

        enableEdgeToEdge()
        val container = (application as PaperPdfApp).container
        setContent {
            val settings by container.settings.settings.collectAsState()
            val anotherPdf = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    launchReader(
                        this,
                        ReaderRequest(null, uri.toString(), copyIfNeeded = true),
                        launchAdjacent = true,
                    )
                }
            }
            PaperTheme(colorMode = settings.colorMode) {
                ReaderScreen(
                    request = request,
                    onBack = { finishAndRemoveTask() },
                    onOpenAnotherWindow = {
                        anotherPdf.launch(arrayOf("application/pdf", "application/octet-stream"))
                    },
                    onDocumentTitleResolved = ::updateWindowTitle,
                )
            }
        }
    }

    private fun updateWindowTitle(title: String) {
        val cleanTitle = title.ifBlank { getString(R.string.app_name) }
        setTitle(cleanTitle)
        setTaskDescription(ActivityManager.TaskDescription(cleanTitle))
    }

    companion object {
        fun launchReader(
            context: Context,
            request: ReaderRequest,
            launchAdjacent: Boolean = false,
        ) {
            ReaderWindowLauncher.open(
                context = context,
                request = request,
                launchAdjacent = launchAdjacent,
            )
        }
    }
}
