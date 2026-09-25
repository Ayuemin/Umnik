package com.ayuemin.ymnik.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class LocalWebFetcherTest {
    @Test
    fun rejectsLocalAndPrivateAddresses() {
        assertFalse(LocalWebFetchPolicy.isAddressAllowed(InetAddress.getByName("127.0.0.1")))
        assertFalse(LocalWebFetchPolicy.isAddressAllowed(InetAddress.getByName("10.0.0.1")))
        assertFalse(LocalWebFetchPolicy.isAddressAllowed(InetAddress.getByName("172.16.2.3")))
        assertFalse(LocalWebFetchPolicy.isAddressAllowed(InetAddress.getByName("192.168.1.10")))
        assertFalse(LocalWebFetchPolicy.isAddressAllowed(InetAddress.getByName("169.254.169.254")))
        assertFalse(LocalWebFetchPolicy.isAddressAllowed(InetAddress.getByName("100.64.0.1")))
        assertTrue(LocalWebFetchPolicy.isAddressAllowed(InetAddress.getByName("8.8.8.8")))
    }

    @Test
    fun extractsMainContentAndAbsoluteLinks() {
        val page = LocalWebTextExtractor.extract(
            """
                <html>
                  <head><title>Example title</title><script>ignore me</script></head>
                  <body>
                    <nav>Navigation noise</nav>
                    <main>
                      <h1>Release 2.4</h1>
                      <p>Main text here.</p>
                      <a href="/download">Download</a>
                    </main>
                    <footer>Footer noise</footer>
                  </body>
                </html>
            """.trimIndent(),
            "https://example.com/releases"
        )

        assertEquals("Example title", page.title)
        assertTrue(page.content.contains("Release 2.4"))
        assertTrue(page.content.contains("Main text here."))
        assertFalse(page.content.contains("Navigation noise"))
        assertFalse(page.content.contains("ignore me"))
        assertEquals("https://example.com/download", page.links.single().url)
        assertFalse(page.requiresBrowser)
    }

    @Test
    fun detectsJavaScriptAppShell() {
        val page = LocalWebTextExtractor.extract(
            """
                <html>
                  <head>
                    <title>App</title>
                    <script src="/a.js"></script>
                    <script src="/b.js"></script>
                    <script type="module" src="/c.js"></script>
                  </head>
                  <body><div id="root"></div></body>
                </html>
            """.trimIndent(),
            "https://example.com/app"
        )

        assertTrue(page.requiresBrowser)
        assertEquals("js_required", page.browserReason)
    }

    @Test
    fun acceptsOnlyHttpSchemes() {
        assertEquals("https", LocalWebFetchPolicy.parseUrl("https://example.com/a").scheme)
        val failure = runCatching { LocalWebFetchPolicy.parseUrl("file:///tmp/a") }.exceptionOrNull()
        assertTrue(failure != null)
    }
}
