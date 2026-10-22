package com.paperpdf.reader

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paperpdf.reader.ui.ReaderRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderWindowContractTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun readerActivityUsesIndependentResizeableDocumentTasks() {
        val component = ComponentName(context, "com.paperpdf.reader.ReaderActivity")
        val info = context.packageManager.getActivityInfo(component, PackageManager.GET_META_DATA)

        assertEquals(ActivityInfo.DOCUMENT_LAUNCH_ALWAYS, info.documentLaunchMode)
        assertTrue(info.exported)
    }

    @Test
    fun launcherRequestsANewTaskForEveryPdfWindow() {
        val launcher = Class.forName("com.paperpdf.reader.ReaderWindowLauncher")
        val method = launcher.getMethod(
            "intent",
            Context::class.java,
            ReaderRequest::class.java,
            Boolean::class.javaPrimitiveType,
        )
        val intent = method.invoke(
            null,
            context,
            ReaderRequest(docId = 7L, uri = "file:///sdcard/Download/a.pdf", copyIfNeeded = false),
            false,
        ) as Intent

        assertNotNull(intent.component)
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0)
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK) != 0)
        assertEquals(7L, intent.getLongExtra("doc_id", -1L))
        assertEquals("file:///sdcard/Download/a.pdf", intent.getStringExtra("uri"))
    }

    @Test
    fun externalViewIntentBecomesAReaderRequest() {
        val launcher = Class.forName("com.paperpdf.reader.ReaderWindowLauncher")
        val method = launcher.getMethod("requestFrom", Context::class.java, Intent::class.java)
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/Download/a.pdf")).apply {
            type = "application/pdf"
        }
        val request = method.invoke(null, context, viewIntent) as ReaderRequest

        assertEquals("file:///sdcard/Download/a.pdf", request.uri)
    }
}
