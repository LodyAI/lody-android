package app.lody.android.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAttachmentsTest {
    @Test fun textPreviewAcceptsUtf8EvenWhenTheProbeCutsACharacter() {
        val text = "日志 log ✓".toByteArray()
        assertTrue(SessionAttachmentUploads.textPreview(text))
        assertTrue("a cut multi-byte tail still counts", SessionAttachmentUploads.textPreview(text.copyOf(text.size - 1)))
    }

    @Test fun binaryPrefixesAreNotPreviewable() {
        assertFalse(SessionAttachmentUploads.textPreview(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x00)))
        assertFalse(SessionAttachmentUploads.textPreview(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0xc3.toByte(), 0x28)))
    }

    @Test fun headerSegmentsArePercentEncoded() {
        assertEquals("my%20notes%E6%97%A5.md", SessionAttachmentUploads.segment("my notes日.md"))
    }

    @Test fun videoAttachmentsResolveVideoMimeTypesEvenWhenDeclaredGeneric() {
        val mp4 = SessionFileRef("f1", "demo.mp4", mimeType = "application/octet-stream")
        val mov = SessionFileRef("f2", "capture.MOV", mimeType = null)
        val typed = SessionFileRef("f3", "clip.bin", mimeType = "video/webm; codecs=vp9")
        val doc = SessionFileRef("f4", "notes.pdf", mimeType = "application/pdf")

        assertTrue(mp4.video)
        assertTrue(mov.video)
        assertTrue(typed.video)
        assertFalse(doc.video)

        assertEquals("video/mp4", SessionFiles.mimeType(mp4))
        assertEquals("video/quicktime", SessionFiles.mimeType(mov))
        assertEquals("video/webm", SessionFiles.mimeType(typed))
        assertEquals("application/pdf", SessionFiles.mimeType(doc))
    }
}
