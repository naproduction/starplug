package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class StardimaProvider : MainAPI() {
    override var mainUrl = "https://www.stardima.com"
    override var name = "ستارديما (Stardima)"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Cartoon, TvType.Anime, TvType.Movie)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl" to "الرئيسية (Home)",
        "$mainUrl/newrelases" to "المضاف حديثا (Latest)",
        "$mainUrl/mosalsalat" to "مسلسلات (Series)",
        "$mainUrl/aflam" to "أفلام (Movies)"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(request.data, headers = headers).document

        val items = document.select("img[alt*='Poster'], img[alt*='بوستر']").mapNotNull { img ->
            val parentLink = img.parents().select("a[href]").firstOrNull() ?: img.closest("a")
            val href = fixUrlNull(parentLink?.attr("href")) ?: return@mapNotNull null
            if (!href.contains("/tvshow/") && !href.contains("/movie/")) return@mapNotNull null
            if (href.contains("/play/")) return@mapNotNull null

            val rawTitle = img.attr("alt")
            val title = rawTitle.replace("Poster for ", "").replace("Poster for", "").trim()
            if (title.isBlank() || title.contains("تسجيل الدخول")) return@mapNotNull null

            val poster = fixUrlNull(img.attr("src").ifEmpty { img.attr("data-src") })
            val isMovie = href.contains("/movie/")
            val type = if (isMovie) TvType.Movie else TvType.Cartoon

            newAnimeSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${encodeUrl(query)}"
        val document = app.get(url, headers = headers).document

        return document.select("img[alt*='Poster'], img[alt*='بوستر']").mapNotNull { img ->
            val parentLink = img.parents().select("a[href]").firstOrNull() ?: img.closest("a")
            val href = fixUrlNull(parentLink?.attr("href")) ?: return@mapNotNull null
            if (!href.contains("/tvshow/") && !href.contains("/movie/")) return@mapNotNull null
            if (href.contains("/play/")) return@mapNotNull null

            val rawTitle = img.attr("alt")
            val title = rawTitle.replace("Poster for ", "").replace("Poster for", "").trim()
            if (title.isBlank() || title.contains("تسجيل الدخول")) return@mapNotNull null

            val poster = fixUrlNull(img.attr("src").ifEmpty { img.attr("data-src") })
            val isMovie = href.contains("/movie/")
            val type = if (isMovie) TvType.Movie else TvType.Cartoon

            newAnimeSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("meta[property='og:title']")?.attr("content")
            ?.substringBefore("-")?.replace("مشاهدة وتحميل", "")?.replace("مسلسل", "")?.replace("كرتون", "")?.trim()
            ?: document.select("h1").map { it.text().trim() }.firstOrNull { !it.contains("تسجيل") && it.isNotBlank() }
            ?: "Cartoon"

        val poster = fixUrlNull(
            document.selectFirst("img[src*='/posters/'], img[src*='image.tmdb.org']")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?.takeIf { !it.contains("تسجيل الدخول") && !it.contains("One2Auth") && it.isNotBlank() }
            ?: document.select("p").map { it.text().trim() }.firstOrNull { 
                it.length > 30 && !it.contains("تسجيل الدخول") && !it.contains("One2Auth") && !it.contains("حساب جوجل")
            }

        val episodes = ArrayList<Episode>()

        val playBtn = document.select("a[href*='/play/']").firstOrNull()?.attr("href")
        val targetDoc = if (playBtn != null && !url.contains("/play/")) {
            try { app.get(fixUrl(playBtn), headers = headers).document } catch (_: Exception) { document }
        } else {
            document
        }

        val epElements = targetDoc.select("a[href*='/play/']")
        if (epElements.isNotEmpty()) {
            for ((index, el) in epElements.distinctBy { it.attr("href") }.withIndex()) {
                val epHref = fixUrl(el.attr("href"))
                val rawName = el.text().trim()
                val epName = if (rawName.isNotBlank() && !rawName.contains("تسجيل الدخول") && !rawName.contains("تشغيل")) {
                    rawName
                } else {
                    "الحلقة ${index + 1}"
                }

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epName
                        this.episode = index + 1
                    }
                )
            }
        }

        if (episodes.isEmpty()) {
            val fallbackPlay = playBtn?.let { fixUrl(it) } ?: url
            episodes.add(
                newEpisode(fallbackPlay) {
                    this.name = title
                    this.episode = 1
                }
            )
        }

        val isMovie = url.contains("/movie/")
        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    // 4. Diagnostic Link Resolver with Real-Time On-Screen Reporting
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        fun sendDebug(msg: String) {
            callback(
                ExtractorLink(
                    source = "[فحص المشغل]",
                    name = msg,
                    url = "https://127.0.0.1",
                    referer = data,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.VIDEO
                )
            )
        }

        val res = try {
            app.get(data, headers = headers)
        } catch (e: Exception) {
            sendDebug("خطأ في فتح صفحة الحلقة: ${e.message}")
            return true
        }

        val rawHtml = res.text
        val cleanHtml = rawHtml.replace("\\/", "/")

        // Extract video ID
        val videoPattern = Regex("""hyperwatching\.com(?:\\?/|/)(?:watch|embed)(?:\\?/|/)([a-zA-Z0-9]+)""")
        val videoId = videoPattern.find(rawHtml)?.groupValues?.get(1)
            ?: videoPattern.find(cleanHtml)?.groupValues?.get(1)
            ?: videoPattern.find(data)?.groupValues?.get(1)

        if (videoId.isNullOrBlank()) {
            // Report on screen that video ID was not found in HTML
            val snippet = if (rawHtml.length > 80) rawHtml.take(80) else rawHtml
            sendDebug("لم يتم العثور على معرف الفيديو. مقتطف من الصفحة: $snippet")
            return true
        } else {
            sendDebug("تم العثور على معرف الفيديو: $videoId")
        }

        // Query the confirmed server endpoint
        val serverIds = listOf("861928", "861929", "861930", "861931", "861932", "861933")
        val apiHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Referer" to "https://v2.hyperwatching.com/watch/$videoId",
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/plain, */*"
        )

        var successfulCalls = 0

        for (sId in serverIds) {
            val apiUrl = "https://v2.hyperwatching.com/embed/$videoId/server/$sId/url"
            try {
                val apiResponse = app.get(apiUrl, headers = apiHeaders)
                val jsonText = apiResponse.text

                if (jsonText.contains("\"status\"") && jsonText.contains("\"ok\"")) {
                    successfulCalls++
                    processServerJson(jsonText, data, subtitleCallback, callback)
                } else {
                    val preview = if (jsonText.length > 50) jsonText.take(50) else jsonText
                    sendDebug("رد السيرفر ($sId): $preview")
                }
            } catch (e: Exception) {
                sendDebug("فشل الاتصال بالسيرفر ($sId): ${e.message}")
            }
        }

        if (successfulCalls == 0) {
            sendDebug("تم فحص السيرفرات ولكن لم يتم إرجاع رابط تشغيل مباشر.")
        }

        return true
    }

    private suspend fun processServerJson(
        jsonString: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val json = try { JSONObject(jsonString) } catch (e: Exception) { return }
        if (json.optString("status") != "ok") return

        val queryObj = json.optJSONObject("query")
        val hostName = queryObj?.optString("host")?.replaceFirstChar { it.uppercase() } ?: "سيرفر"

        // 1. Direct sources array
        val sources = json.optJSONArray("sources")
        if (sources != null && sources.length() > 0) {
            for (i in 0 until sources.length()) {
                val srcObj = sources.optJSONObject(i) ?: continue
                val fileUrl = srcObj.optString("file").ifEmpty { 
                    srcObj.optString("src").ifEmpty { srcObj.optString("url") } 
                }
                if (fileUrl.startsWith("http")) {
                    val label = srcObj.optString("label").ifEmpty { srcObj.optString("quality") }
                    val isHls = fileUrl.contains(".m3u8")
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "ستارديما - $hostName ${if (label.isNotEmpty()) "($label)" else ""}".trim(),
                            url = fileUrl,
                            referer = referer,
                            quality = Qualities.P720.value,
                            type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        )
                    )
                }
            }
        }

        // 2. Embed URL (strema.top for Lulustream, Uqload, Mixdrop, etc.)
        val embedUrl = json.optString("embed_url")
        if (embedUrl.isNotEmpty()) {
            if (!loadExtractor(embedUrl, referer, subtitleCallback, callback)) {
                resolveStremaJWPlayer(embedUrl, hostName, callback)
            }
        }
    }

    private suspend fun resolveStremaJWPlayer(
        embedUrl: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(embedUrl, headers = headers + mapOf("Referer" to "https://v2.hyperwatching.com/")).text
            val unpacked = unpackJs(res)
            val fullContent = "$res\n$unpacked"

            val m3u8Regex = Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""")
            val match = m3u8Regex.find(fullContent)
            if (match != null) {
                val streamUrl = match.groupValues[1].replace("\\/", "/")
                callback(
                    ExtractorLink(
                        source = name,
                        name = "ستارديما - $serverName (HLS)",
                        url = streamUrl,
                        referer = embedUrl,
                        quality = Qualities.P720.value,
                        type = ExtractorLinkType.M3U8
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun unpackJs(script: String): String {
        return try {
            val packerPattern = Regex("""eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""")
            val match = packerPattern.find(script) ?: return ""

            val payload = match.groupValues[1]
            val radix = match.groupValues[2].toInt()
            val count = match.groupValues[3].toInt()
            val symTab = match.groupValues[4].split("|")

            fun lookup(word: String): String {
                val idx = if (radix <= 36) word.toIntOrNull(radix) ?: -1 else -1
                return if (idx in symTab.indices && symTab[idx].isNotEmpty()) symTab[idx] else word
            }

            Regex("""\b\w+\b""").replace(payload) { m -> lookup(m.value) }
        } catch (e: Exception) {
            ""
        }
    }

    private fun encodeUrl(str: String): String {
        return java.net.URLEncoder.encode(str, "UTF-8")
    }
}





