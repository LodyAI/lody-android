package app.lody.android.chat

import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lody.android.runtime.RuntimeScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SessionVideoAttachmentDeviceTest {
    @Test
    fun fileProviderServesSyntheticVideoAndResolvesSystemViewIntent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = SessionFiles.directory(context)
        dir.mkdirs()
        try {
            // Minimal valid MP4 ftyp+moov header bytes for FileProvider & MIME/Intent verification.
            val syntheticBytes = byteArrayOf(
                0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70, // ftyp box
                0x6D, 0x70, 0x34, 0x32, 0x00, 0x00, 0x00, 0x00,
                0x6D, 0x70, 0x34, 0x32, 0x69, 0x73, 0x6F, 0x6D,
            )
            val file = File(dir, "walkthrough.mp4").apply { writeBytes(syntheticBytes) }
            val ref = SessionFileRef(
                id = "video-verify-1",
                fileName = "walkthrough.mp4",
                transport = "r2",
                sizeBytes = syntheticBytes.size.toLong(),
                mimeType = "application/octet-stream",
            )

            assertTrue("SessionFileRef.video must recognize .mp4", ref.video)
            val resolvedMime = SessionFiles.mimeType(ref)
            assertEquals("video/mp4", resolvedMime)

            val uri = SessionFiles.uri(context, file)
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.images", uri.authority)

            val readBack = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            assertNotNull("FileProvider content URI must be readable", readBack)
            assertArrayEquals(syntheticBytes, readBack)

            val viewIntent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, resolvedMime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            viewIntent.clipData = ClipData.newRawUri(file.name, uri)
            val chooser = Intent.createChooser(viewIntent, null)
            assertNotNull(
                "System chooser must resolve for video/mp4 ACTION_VIEW",
                context.packageManager.resolveActivity(chooser, PackageManager.MATCH_DEFAULT_ONLY),
            )
            // Verify the production SessionFileOpener downloads via reader and launches the system chooser without error.
            val scope = RuntimeScope("verify-account", "verify-workspace")
            runBlocking {
                val opener = SessionFileOpener(
                    context = context,
                    coroutines = this,
                    reader = SessionFileReader { _, _, _, targetDir ->
                        targetDir.mkdirs()
                        File(targetDir, "walkthrough.mp4").apply { writeBytes(syntheticBytes) }
                    },
                    scope = scope,
                    sessionId = "verify-session",
                )
                opener.open(ref)
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
