package com.paperpdf.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.paperpdf.reader.ui.LibraryScreen
import com.paperpdf.reader.ui.ReaderRequest
import com.paperpdf.reader.ui.theme.PaperTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as PaperPdfApp).container
        setContent {
            val settings by container.settings.settings.collectAsState()
            PaperTheme(colorMode = settings.colorMode) {
                LibraryScreen(
                    onOpenDocument = { docId, uri ->
                        ReaderWindowLauncher.open(
                            this@MainActivity,
                            ReaderRequest(docId, uri, copyIfNeeded = false),
                        )
                    },
                    onOpenUri = { uri, copyIfNeeded ->
                        ReaderWindowLauncher.open(
                            this@MainActivity,
                            ReaderRequest(null, uri, copyIfNeeded),
                        )
                    },
                )
            }
        }
    }
}
