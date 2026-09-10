package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONArray
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

    // 1. Home Page Sections
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

    // 2. Search
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

    // 3. Load Show Details & Parse All Seasons/Episodes from JSON
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
        val showId = url.substringAfter("/tvshow/").substringBefore("/")

        // Parse nested seasons -> episodes from Inertia data-page
        val dataPage = document.selectFirst("#app, [data-page]")?.attr("data-page")
        if (!dataPage.isNullOrBlank()) {
            try {
                val json = JSONObject(dataPage)
                val props = json.optJSONObject("props")
                val tvshowObj = props?.optJSONObject("tvshow") ?: props?.optJSONObject("show")

                // Check seasons array
                val seasonsArray = tvshowObj?.optJSONArray("seasons") ?: props?.optJSONArray("seasons")
                if (seasonsArray != null) {
                    for (s in 0 until seasonsArray.length()) {
                        val seasonObj = seasonsArray.optJSONObject(s) ?: continue
                        val seasonNum = seasonObj.optInt("season_number", s + 1)
                        val epArray = seasonObj.optJSONArray("episodes") ?: continue

                        for (e in 0 until epArray.length()) {
                            val epObj = epArray.optJSONObject(e) ?: continue
                            val epId = epObj.optString("id").ifEmpty { epObj.optInt("id").toString() }
                            val epName = epObj.optString("title").ifEmpty { epObj.optString("name").ifEmpty { "الحلقة ${e + 1}" } }
                            val epNum = epObj.optInt("episode_number", e + 1)
                            val playUrl = "$mainUrl/tvshow/$showId/play/$epId"

                            episodes.add(
                                newEpisode(playUrl) {
                                    this.name = epName
                                    this.episode = epNum
                                    this.season = seasonNum
                                }
                            )
                        }
                    }
                }

                // Fallback: direct episodes array
                if (episodes.isEmpty()) {
                    val directEps = tvshowObj?.optJSONArray("episodes") ?: props?.optJSONArray("episodes")
                    if (directEps != null) {
                        for (e in 0 until directEps.length()) {
                            val epObj = directEps.optJSONObject(e) ?: continue
                            val epId = epObj.optString("id").ifEmpty { epObj.optInt("id").toString() }
                            val epName = epObj.optString("title").ifEmpty { epObj.optString("name").ifEmpty { "الحلقة ${e + 1}" } }
                            val epNum = epObj.optInt("episode_number", e + 1)
                            val playUrl = "$mainUrl/tvshow/$showId/play/$epId"

                            episodes.add(
                                newEpisode(playUrl) {
                                    this.name = epName
                                    this.episode = epNum
                                    this.season = 1
                                }
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // HTML play links fallback
        if (episodes.isEmpty()) {
            val epElements = document.select("a[href*='/play/']")
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

    // 4. Resolve Video Streams (with Auto-Redirect Safety Net)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Safety net: if overview URL was passed, resolve its first play page
        val targetUrl = if (!data.contains("/play/") && !data.contains("/movie/")) {
            try {
                val showDoc = app.get(data, headers = headers).document
                val playLink = showDoc.select("a[href*='/play/']").firstOrNull()?.attr("href")
                if (playLink != null) fixUrl(playLink) else data
            } catch (_: Exception) {
                data
            }
        } else {
            data
        }

        val document = app.get(targetUrl, headers = headers).document
        val rawHtml = document.html()
        val cleanHtml = rawHtml.replace("\\/", "/")

        var foundAny = false

        // Extract Hyperwatching video ID (handles escaped slashes)
        val videoPattern = Regex("""hyperwatching\.com(?:\\?/|/)(?:watch|embed)(?:\\?/|/)([a-zA-Z0-9]+)""")
        var videoId = videoPattern.find(rawHtml)?.groupValues?.get(1)
            ?: videoPattern.find(cleanHtml)?.groupValues?.get(1)
            ?: videoPattern.find(targetUrl)?.groupValues?.get(1)

        if (videoId == null) {
            for (iframe in document.select("iframe")) {
                val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
                videoId = videoPattern.find(src)?.groupValues?.get(1)
                if (videoId != null) break
            }
        }

        // Query all 6 servers directly
        if (!videoId.isNullOrBlank()) {
            val serverIds = LinkedHashSet<String>()

            // Read server IDs from HTML
            val idPattern = Regex("""(?:&quot;|")id(?:&quot;|")\s*:\s*(\d{5,8})""")
            for (m in idPattern.findAll(cleanHtml)) {
                serverIds.add(m.groupValues[1])
            }

            // Probe adjacent server IDs around the confirmed ID
            if (serverIds.isEmpty()) {
                val baseId = 861929
                for (offset in -4..5) {
                    serverIds.add((baseId + offset).toString())
                }
            }

            val apiHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
                "Referer" to "https://v2.hyperwatching.com/watch/$videoId",
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json, text/plain, */*"
            )

            for (sId in serverIds) {
                try {
                    val apiUrl = "https://v2.hyperwatching.com/embed/$videoId/server/$sId/url"
                    val res = app.get(apiUrl, headers = apiHeaders).text

                    if (res.contains("\"status\"") && res.contains("\"ok\"")) {
                        processServerJson(res, targetUrl, subtitleCallback, callback)
                        foundAny = true
                    }
                } catch (_: Exception) {}
            }
        }

        // Direct host regex fallback
        val hostRegex = Regex("""https?:\\?/\\?/[^"'\s<>]*(?:uqload|mixdrop|streamhg|goodstream|savefiles|earnvids|strema\.top)[^"'\s<>]*""")
        for (match in hostRegex.findAll(rawHtml)) {
            val hostUrl = match.value.replace("\\/", "/")
            if (hostUrl.startsWith("http")) {
                if (!loadExtractor(hostUrl, targetUrl, subtitleCallback, callback)) {
                    if (hostUrl.contains("strema.top")) {
                        resolveStremaJWPlayer(hostUrl, "Lulustream", callback)
                        foundAny = true
                    }
                } else {
                    foundAny = true
                }
            }
        }

        return foundAny
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

        // 1. Direct sources array (raw mp4 or m3u8)
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

        // 2. Embed URL (strema.top, Uqload, Mixdrop, Goodstream, etc.)
        val embedUrl = json.optString("embed_url")
        if (embedUrl.isNotEmpty()) {
            if (!loadExtractor(embedUrl, referer, subtitleCallback, callback)) {
                resolveStremaJWPlayer(embedUrl, hostName, callback)
            }
        }

        // 3. Fallback direct download link
        val downloadUrl = json.optString("download_url")
        if (downloadUrl.startsWith("http") && !downloadUrl.contains("hyperwatching")) {
            callback(
                ExtractorLink(
                    source = name,
                    name = "ستارديما - $hostName (سيرفر احتياطي)",
                    url = downloadUrl,
                    referer = referer,
                    quality = Qualities.P720.value,
                    type = if (downloadUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
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

