package com.lui.app.interceptor.actions

import android.content.Context
import com.lui.app.helper.LuiLogger
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object WebActions {

    /**
     * Search the web via DuckDuckGo HTML and return top results.
     */
    fun searchWeb(context: Context, query: String): ActionResult {
        if (query.isBlank()) return ActionResult.Failure("No search query provided.")

        return try {
            // Must run on IO thread — ActionExecutor may call from main thread
            val result = java.util.concurrent.FutureTask<ActionResult> {
                searchWebSync(query)
            }
            Thread(result).start()
            result.get(20, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            LuiLogger.e("WebActions", "Search wrapper failed: ${e.javaClass.simpleName}: ${e.message}", e)
            ActionResult.Failure("Search failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun searchWebSync(query: String): ActionResult {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://html.duckduckgo.com/html/?q=$encoded")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "LUI/1.0")
                connectTimeout = 10000
                readTimeout = 15000
                instanceFollowRedirects = true
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return ActionResult.Failure("Search failed with HTTP $code")
            }

            val html = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val results = parseSearchResults(html)
            if (results.isEmpty()) {
                ActionResult.Success("No results found for: $query")
            } else {
                val sb = StringBuilder("Search results for \"$query\":\n\n")
                for ((i, result) in results.withIndex()) {
                    sb.appendLine("${i + 1}. ${result.title}")
                    sb.appendLine("   ${result.snippet}")
                    sb.appendLine("   ${result.url}")
                    sb.appendLine()
                }
                ActionResult.Success(sb.toString().trim())
            }
        } catch (e: Exception) {
            LuiLogger.e("WebActions", "Search failed: ${e.message}", e)
            ActionResult.Failure("Search failed: ${e.message}")
        }
    }

    /**
     * Browse a URL and return the text content.
     */
    fun browseUrl(context: Context, url: String): ActionResult {
        if (url.isBlank()) return ActionResult.Failure("No URL provided.")

        val fullUrl = if (!url.startsWith("http")) "https://$url" else url

        return try {
            val result = java.util.concurrent.FutureTask<ActionResult> {
                browseUrlSync(fullUrl)
            }
            Thread(result).start()
            result.get(20, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            LuiLogger.e("WebActions", "Browse wrapper failed: ${e.javaClass.simpleName}: ${e.message}", e)
            ActionResult.Failure("Couldn't load URL: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun browseUrlSync(fullUrl: String): ActionResult {
        return try {
            val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "LUI/1.0")
                connectTimeout = 10000
                readTimeout = 15000
                instanceFollowRedirects = true
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return ActionResult.Failure("Failed to load $fullUrl (HTTP $code)")
            }

            val html = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val text = extractText(html)
            if (text.isBlank()) {
                ActionResult.Failure("Page loaded but no readable text found.")
            } else {
                // Truncate to ~3000 chars to stay within LLM context
                val truncated = if (text.length > 3000) text.take(3000) + "\n\n[Truncated — page is longer]" else text
                ActionResult.Success("Content from $fullUrl:\n\n$truncated")
            }
        } catch (e: Exception) {
            LuiLogger.e("WebActions", "Browse failed: ${e.message}", e)
            ActionResult.Failure("Couldn't load URL: ${e.message}")
        }
    }

    private data class SearchResult(val title: String, val snippet: String, val url: String)

    private fun parseSearchResults(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        // DuckDuckGo HTML results are in <a class="result__a"> with <a class="result__snippet">
        val resultPattern = Regex(
            """<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>.*?<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )

        for (match in resultPattern.findAll(html)) {
            if (results.size >= 5) break
            val rawUrl = match.groupValues[1]
            val title = stripHtml(match.groupValues[2]).trim()
            val snippet = stripHtml(match.groupValues[3]).trim()

            // DuckDuckGo wraps URLs in a redirect — extract the actual URL
            val actualUrl = extractDdgUrl(rawUrl)

            if (title.isNotBlank() && actualUrl.isNotBlank()) {
                results.add(SearchResult(title, snippet, actualUrl))
            }
        }

        return results
    }

    private fun extractDdgUrl(rawUrl: String): String {
        // DDG format: //duckduckgo.com/l/?uddg=ENCODED_URL&...
        val uddgMatch = Regex("[?&]uddg=([^&]+)").find(rawUrl)
        if (uddgMatch != null) {
            return try {
                java.net.URLDecoder.decode(uddgMatch.groupValues[1], "UTF-8")
            } catch (_: Exception) { rawUrl }
        }
        return rawUrl
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractText(html: String): String {
        // Remove script, style, nav, header, footer tags and their content
        var cleaned = html
            .replace(Regex("<(script|style|nav|header|footer|noscript)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

        // Convert block elements to newlines
        cleaned = cleaned
            .replace(Regex("<(br|hr|/p|/div|/h[1-6]|/li|/tr)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "\n- ")

        // Strip remaining tags
        cleaned = stripHtml(cleaned)

        // Collapse whitespace
        cleaned = cleaned
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        return cleaned
    }
}
