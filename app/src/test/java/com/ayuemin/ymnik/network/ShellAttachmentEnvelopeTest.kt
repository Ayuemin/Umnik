package com.ayuemin.ymnik.network

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellAttachmentEnvelopeTest {
    @Test
    fun envelopePreservesOriginalNameAndBytes() {
        val original = byteArrayOf(0, 1, 2, 3, 127, -1)
        val encoded = ShellAttachmentEnvelope.encode(
            originalName = "project.zip",
            mimeType = "application/zip",
            bytes = original,
            index = 2
        )

        assertEquals("umnik_attachment_3.b64.txt", encoded.transportName)
        val text = encoded.bytes.toString(Charsets.UTF_8)
        assertTrue(text.startsWith("UMNIK_BASE64_ATTACHMENT_V1\n"))

        val nameLine = text.lineSequence().first { it.startsWith("original_name_base64=") }
        val decodedName = String(
            Base64.getDecoder().decode(nameLine.substringAfter('=')),
            Charsets.UTF_8
        )
        assertEquals("project.zip", decodedName)

        val payload = text.substringAfter("data_base64_begin\n")
            .substringBefore("\ndata_base64_end")
            .replace("\n", "")
        assertTrue(original.contentEquals(Base64.getDecoder().decode(payload)))
    }
}
