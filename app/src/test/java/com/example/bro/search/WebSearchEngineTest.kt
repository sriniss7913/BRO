package com.example.bro.search

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebSearchEngineTest {

    @Before
    fun setUp() {
        WebSearchEngine.enabled = true
        WebSearchEngine.tavilyApiKey = ""
    }

    @Test
    fun testParseHtmlResultsExtractsSnippetsAndCleansTags() {
        val sampleHtml = """
            <!DOCTYPE html>
            <html>
            <body>
            <div class="result">
                <a class="result__a" href="https://example.com/one"><b>Chennai</b> Weather Forecast</a>
                <a class="result__snippet" href="https://example.com/one">Today in Chennai: 32&deg;C with partly cloudy skies &amp; light breeze.</a>
                <a class="result__url" href="https://example.com/one">https://example.com/one</a>
            </div>
            <div class="result">
                <a class="result__a" href="https://example.com/two">Weekly Weather Updates</a>
                <a class="result__snippet" href="https://example.com/two">Expect warm temperatures &quot;throughout&quot; the week.</a>
                <a class="result__url" href="https://example.com/two">https://example.com/two</a>
            </div>
            </body>
            </html>
        """.trimIndent()

        val results = WebSearchEngine.parseHtmlResults(sampleHtml, maxResults = 2)

        assertEquals(2, results.size)
        assertEquals("Chennai Weather Forecast", results[0].title)
        assertTrue(results[0].snippet.contains("32°C with partly cloudy skies & light breeze"))
        assertFalse("Tags should be removed", results[0].snippet.contains("<"))
        assertEquals("Weekly Weather Updates", results[1].title)
        assertTrue(results[1].snippet.contains("\"throughout\""))
    }

    @Test
    fun testBlankOrDisabledSearchReturnsEmpty() = runBlocking {
        val emptyResult = WebSearchEngine.search("   ")
        assertTrue(emptyResult.isEmpty())

        WebSearchEngine.enabled = false
        val disabledResult = WebSearchEngine.search("weather today")
        assertTrue(disabledResult.isEmpty())
    }

    @Test
    fun testParseGoogleRssXmlExtractsItems() {
        val sampleXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
            <channel>
                <title>Holiday search - Google News</title>
                <item>
                    <title>Bengaluru Holiday Alert: Govt Declares Holiday - Goodreturns</title>
                    <link>https://news.google.com/articles/123</link>
                    <description>&lt;a href="https://example.com"&gt;Bengaluru schools closed tomorrow due to heavy rains&lt;/a&gt;</description>
                </item>
                <item>
                    <title>Schools closed tomorrow in Karnataka - News18</title>
                    <link>https://news.google.com/articles/456</link>
                    <description>State announces holiday for schools and colleges</description>
                </item>
            </channel>
            </rss>
        """.trimIndent()

        val results = WebSearchEngine.parseGoogleRssXml(sampleXml, maxResults = 2)
        assertEquals(2, results.size)
        assertTrue(results[0].title.contains("Bengaluru Holiday Alert"))
        assertTrue(results[0].snippet.contains("Bengaluru schools closed tomorrow"))
        assertEquals("https://news.google.com/articles/123", results[0].url)
    }
}
