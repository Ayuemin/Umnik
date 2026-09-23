package com.ayuemin.ymnik.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeTextExtractorTest {
    @Test
    fun removesFb2BinaryBlocksBeforeTextExtraction() {
        val xml = """
            <FictionBook>
              <body>
                <section>
                  <title><p>Психология</p></title>
                  <p>Психология изучает психику человека.</p>
                </section>
              </body>
              <binary id="cover.jpg" content-type="image/jpeg">
                R0lGODlhAQABAIAAAAAAAAAAACH5BAEAAAAALAAAAAABAAEAAAICRAEAOw==
              </binary>
            </FictionBook>
        """.trimIndent()

        val sanitized = KnowledgeTextExtractor.sanitizeFb2Xml(xml)

        assertTrue(sanitized.contains("Психология изучает психику человека."))
        assertFalse(sanitized.contains("R0lGODlhAQABAIA"))
        assertFalse(sanitized.contains("<binary"))
    }
}
