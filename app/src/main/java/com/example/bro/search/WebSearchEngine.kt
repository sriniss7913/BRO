package com.example.bro.search

import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object WebSearchEngine {

    private const val TAG = "WebSearchEngine"

    @Volatile
    var enabled: Boolean = true

    @Volatile
    var tavilyApiKey: String = ""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Executes a web search query and returns the top snippets.
     * Order of execution:
     * 1. Tavily (if API key set)
     * 2. Google News RSS (Free, no key, no bot blocks, real-time facts/holidays/news)
     * 3. Wikipedia OpenSearch (Free, encyclopedic definitions)
     * 4. DuckDuckGo Instant Answer / HTML
     */
    suspend fun search(query: String, maxResults: Int = 3): List<WebSearchResult> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank() || !enabled) return@withContext emptyList()

        // 1. Tavily Search (if user configured a key)
        if (tavilyApiKey.isNotBlank()) {
            val tavilyResults = searchTavily(cleanQuery, maxResults)
            if (tavilyResults.isNotEmpty()) return@withContext tavilyResults
        }

        // 2. Google News RSS Search (Free, zero API key, bot-proof, real-time facts)
        val googleNewsResults = searchGoogleNewsRss(cleanQuery, maxResults)
        if (googleNewsResults.isNotEmpty()) {
            Log.d(TAG, "Search succeeded via Google News RSS: ${googleNewsResults.size} items")
            return@withContext googleNewsResults
        }

        // 3. Wikipedia Search (Free, fast, factual definitions)
        val wikiResults = searchWikipedia(cleanQuery, maxResults)
        if (wikiResults.isNotEmpty()) {
            Log.d(TAG, "Search succeeded via Wikipedia: ${wikiResults.size} items")
            return@withContext wikiResults
        }

        // 4. DuckDuckGo Instant Answer API
        val instantResults = searchDuckDuckGoInstant(cleanQuery)
        if (instantResults.isNotEmpty()) {
            return@withContext instantResults.take(maxResults)
        }

        // 5. Fallback to DuckDuckGo HTML Search
        val htmlResults = searchDuckDuckGoHtml(cleanQuery, maxResults)
        return@withContext htmlResults
    }

    /**
     * Returns a concise 2-3 line string summary of the search results for prompt injection.
     */
    suspend fun searchSummary(query: String): String {
        val results = search(query, maxResults = 3)
        if (results.isEmpty()) return ""

        val sb = StringBuilder()
        for ((idx, res) in results.withIndex()) {
            sb.append("${idx + 1}. ${res.title}: ${res.snippet}\n")
        }
        return sb.toString().trim()
    }

    fun searchGoogleNewsRss(query: String, maxResults: Int = 3): List<WebSearchResult> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://news.google.com/rss/search?q=$encoded&hl=en-IN&gl=IN&ceid=IN:en"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "application/rss+xml, application/xml, text/xml")
                .build()

            val response = httpClient.newCall(request).execute()
            val xml = response.body?.string().orEmpty()
            if (!response.isSuccessful || xml.isBlank()) return emptyList()

            parseGoogleRssXml(xml, maxResults)
        } catch (e: Exception) {
            Log.d(TAG, "Google News RSS error: ${e.message}")
            emptyList()
        }
    }

    fun parseGoogleRssXml(xml: String, maxResults: Int = 3): List<WebSearchResult> {
        val results = mutableListOf<WebSearchResult>()
        try {
            val itemRegex = Regex("""<item\b[^>]*>(.*?)</item>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            val titleRegex = Regex("""<title\b[^>]*>(.*?)</title>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            val linkRegex = Regex("""<link\b[^>]*>(.*?)</link>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            val descRegex = Regex("""<description\b[^>]*>(.*?)</description>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

            for (match in itemRegex.findAll(xml)) {
                val itemContent = match.groupValues[1]
                var rawTitle = titleRegex.find(itemContent)?.groupValues?.get(1).orEmpty()
                val rawLink = linkRegex.find(itemContent)?.groupValues?.get(1).orEmpty()
                var rawDesc = descRegex.find(itemContent)?.groupValues?.get(1).orEmpty()

                rawTitle = rawTitle.replace("<![CDATA[", "").replace("]]>", "")
                rawDesc = rawDesc.replace("<![CDATA[", "").replace("]]>", "")

                val cleanTitle = cleanHtml(rawTitle)
                val cleanDesc = cleanHtml(rawDesc)
                val snippet = if (cleanDesc.isNotBlank() && cleanDesc != cleanTitle) cleanDesc else cleanTitle
                if (cleanTitle.isNotBlank()) {
                    results.add(
                        WebSearchResult(
                            title = cleanTitle,
                            snippet = snippet,
                            url = rawLink.trim()
                        )
                    )
                    if (results.size >= maxResults) break
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "XML parse error: ${e.message}")
        }
        return results
    }

    fun searchWikipedia(query: String, maxResults: Int = 2): List<WebSearchResult> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://en.wikipedia.org/w/api.php?action=opensearch&search=$encoded&limit=$maxResults&namespace=0&format=json"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) return emptyList()

            val jsonArray = JsonParser.parseString(body).asJsonArray
            if (jsonArray.size() >= 4) {
                val titles = jsonArray.get(1).asJsonArray
                val descriptions = jsonArray.get(2).asJsonArray
                val links = jsonArray.get(3).asJsonArray
                val results = mutableListOf<WebSearchResult>()
                for (i in 0 until minOf(titles.size(), maxResults)) {
                    val t = titles.get(i)?.asString?.trim().orEmpty()
                    val d = descriptions.get(i)?.asString?.trim().orEmpty()
                    val l = links.get(i)?.asString?.trim().orEmpty()
                    if (t.isNotBlank() && (d.isNotBlank() || l.isNotBlank())) {
                        results.add(WebSearchResult(title = t, snippet = d.ifBlank { t }, url = l))
                    }
                }
                results
            } else emptyList()
        } catch (e: Exception) {
            Log.d(TAG, "Wikipedia search error: ${e.message}")
            emptyList()
        }
    }

    private fun searchDuckDuckGoInstant(query: String): List<WebSearchResult> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) return emptyList()

            val json = JsonParser.parseString(body).asJsonObject
            val results = mutableListOf<WebSearchResult>()

            val abstractText = json.get("AbstractText")?.asString?.trim().orEmpty()
            val heading = json.get("Heading")?.asString?.trim().orEmpty()
            val abstractUrl = json.get("AbstractURL")?.asString?.trim().orEmpty()

            if (abstractText.isNotBlank()) {
                results.add(WebSearchResult(title = heading.ifBlank { query }, snippet = abstractText, url = abstractUrl))
            }

            val relatedTopics = json.getAsJsonArray("RelatedTopics")
            if (relatedTopics != null) {
                for (elem in relatedTopics) {
                    if (elem.isJsonObject) {
                        val obj = elem.asJsonObject
                        val text = obj.get("Text")?.asString?.trim().orEmpty()
                        val firstUrl = obj.get("FirstURL")?.asString?.trim().orEmpty()
                        if (text.isNotBlank() && text != abstractText) {
                            results.add(WebSearchResult(title = heading.ifBlank { "Result" }, snippet = text, url = firstUrl))
                        }
                    }
                }
            }
            results
        } catch (e: Exception) {
            Log.d(TAG, "DuckDuckGo instant search error: ${e.message}")
            emptyList()
        }
    }

    private fun searchDuckDuckGoHtml(query: String, maxResults: Int): List<WebSearchResult> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://html.duckduckgo.com/html/?q=$encoded"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html")
                .build()

            val response = httpClient.newCall(request).execute()
            val html = response.body?.string().orEmpty()
            if (!response.isSuccessful || html.isBlank()) return emptyList()

            parseHtmlResults(html, maxResults)
        } catch (e: Exception) {
            Log.d(TAG, "DuckDuckGo HTML search error: ${e.message}")
            emptyList()
        }
    }

    fun parseHtmlResults(html: String, maxResults: Int): List<WebSearchResult> {
        val results = mutableListOf<WebSearchResult>()
        val snippetRegex = Regex("""<a\s+class="result__snippet"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        val titleRegex = Regex("""<a\s+class="result__a"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        val urlRegex = Regex("""<a\s+class="result__url"[^>]*href="([^"]+)"""", RegexOption.IGNORE_CASE)

        val titles = titleRegex.findAll(html).map { cleanHtml(it.groupValues[1]) }.toList()
        val snippets = snippetRegex.findAll(html).map { cleanHtml(it.groupValues[1]) }.toList()
        val urls = urlRegex.findAll(html).map { it.groupValues[1].trim() }.toList()

        for (i in 0 until minOf(titles.size, snippets.size, maxResults)) {
            val title = titles.getOrNull(i).orEmpty()
            val snippet = snippets.getOrNull(i).orEmpty()
            val link = urls.getOrNull(i).orEmpty()
            if (snippet.isNotBlank()) {
                results.add(WebSearchResult(title = title.ifBlank { "Search Result" }, snippet = snippet, url = link))
            }
        }
        return results
    }

    private fun searchTavily(query: String, maxResults: Int): List<WebSearchResult> {
        return try {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val jsonPayload = """{"api_key":"$tavilyApiKey","query":${com.google.gson.Gson().toJson(query)},"max_results":$maxResults}"""
            val request = Request.Builder()
                .url("https://api.tavily.com/search")
                .post(jsonPayload.toRequestBody(mediaType))
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) return emptyList()

            val json = JsonParser.parseString(body).asJsonObject
            val resultsArr = json.getAsJsonArray("results") ?: return emptyList()
            val list = mutableListOf<WebSearchResult>()
            for (elem in resultsArr) {
                val obj = elem.asJsonObject
                val title = obj.get("title")?.asString.orEmpty()
                val content = obj.get("content")?.asString.orEmpty()
                val url = obj.get("url")?.asString.orEmpty()
                if (content.isNotBlank()) {
                    list.add(WebSearchResult(title = title, snippet = content, url = url))
                }
            }
            list
        } catch (e: Exception) {
            Log.d(TAG, "Tavily search error: ${e.message}")
            emptyList()
        }
    }

    private fun cleanHtml(raw: String): String {
        return raw.replace(Regex("<[^>]*>"), "")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&deg;", "°")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
