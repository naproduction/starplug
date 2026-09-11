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
        val items = parseContentCards(document).take(if (request.data == mainUrl) 12 else 30)

        return newHomePageResponse(request.name, items, hasNext = false)
    }

    // 2. Search
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${encodeUrl(query)}"
        val document = app.get(url, headers = headers).document

        return parseContentCards(document)
    }

    private fun parseContentCards(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select("img[alt^='Poster for ']").mapNotNull { image ->
            val card = image.parents().firstOrNull { parent ->
                parent.select("a[href]").any { link ->
                    val href = fixUrlNull(link.attr("href")) ?: return@any false
                    isContentUrl(href)
                }
            } ?: return@mapNotNull null

            val href = card.select("a[href]")
                .mapNotNull { fixUrlNull(it.attr("href")) }
                .firstOrNull(::isContentUrl)
                ?: return@mapNotNull null
            val title = image.attr("alt").removePrefix("Poster for").trim()
            if (title.isBlank() || title.contains("تسجيل الدخول")) return@mapNotNull null

            newAnimeSearchResponse(title, href, if (href.contains("/movie/")) TvType.Movie else TvType.Cartoon) {
                posterUrl = fixUrlNull(image.attr("src").ifEmpty { image.attr("data-src") })
            }
        }.distinctBy { it.url }
    }

    private fun isContentUrl(url: String): Boolean {
        return url.startsWith("$mainUrl/tvshow/") || url.startsWith("$mainUrl/movie/")
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

        // The series page only exposes the current "watch now" episode. Its
        // player page contains the complete season list.
        if (!url.contains("/movie/") && episodes.size <= 1) {
            val firstPlayUrl = document.select("a[href*='/play/']")
                .mapNotNull { fixUrlNull(it.attr("href")) }
                .firstOrNull()

            if (firstPlayUrl != null) {
                try {
                    val playDocument = app.get(firstPlayUrl, headers = headers).document
                    val allEpisodes = playDocument.select("a[href*='/play/']")
                        .mapNotNull { link ->
                            val episodeId = Regex("""/play/([^/?#]+)""")
                                .find(link.attr("href"))?.groupValues?.get(1)
                                ?: return@mapNotNull null
                            val titleText = link.text().trim()
                            val numberMatch = Regex("""S(\d+)E(\d+)""", RegexOption.IGNORE_CASE)
                                .find(titleText)
                            val season = numberMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                            val episode = numberMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                                ?: (episodes.size + 1)

                            newEpisode("$mainUrl/tvshow/$showId/play/$episodeId") {
                                name = titleText.ifBlank { "الحلقة $episode" }
                                this.season = season
                                this.episode = episode
                            }
                        }
                        .distinctBy { it.data }

                    if (allEpisodes.isNotEmpty()) {
                        episodes.clear()
                        episodes.addAll(allEpisodes)
                    }
                } catch (_: Exception) {}
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

            try {
                val playerDocument = app.get(
                    "https://v2.hyperwatching.com/watch/$videoId",
                    headers = headers + mapOf("Referer" to targetUrl)
                ).document
                playerDocument.selectFirst("#app")?.attr("data-page")?.takeIf { it.isNotBlank() }?.let { playerData ->
                    val video = JSONObject(playerData).optJSONObject("props")?.optJSONObject("video")
                    video?.optString("hashid")?.takeIf { it.isNotBlank() }?.let { videoId = it }
                    val servers = video?.optJSONArray("servers")
                    for (i in 0 until (servers?.length() ?: 0)) {
                        val server = servers?.optJSONObject(i) ?: continue
                        if (server.optString("status") == "completed") {
                            val serverId = server.optLong("id", 0L)
                            if (serverId > 0) serverIds.add(serverId.toString())
                        }
                    }
                }
            } catch (_: Exception) {}

            // Read server IDs from HTML
            val idPattern = Regex("""(?:&quot;|")id(?:&quot;|")\s*:\s*(\d{5,8})""")
            for (m in idPattern.findAll(cleanHtml)) {
                serverIds.add(m.groupValues[1])
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

                    foundAny = processServerJson(res, targetUrl, subtitleCallback, callback) || foundAny
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
    ): Boolean {
        val json = try { JSONObject(jsonString) } catch (e: Exception) { return false }
        if (json.optString("status") != "ok" && json.optString("watch_url").isEmpty()) return false

        var foundAny = false

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
                    foundAny = true
                }
            }
        }

        // 2. Current Hyperwatching responses expose the player as watch_url.
        val playerUrl = json.optString("watch_url").ifEmpty { json.optString("embed_url") }
        if (playerUrl.isNotEmpty()) {
            if (loadExtractor(playerUrl, referer, subtitleCallback, callback)) {
                foundAny = true
            } else {
                foundAny = resolveStremaJWPlayer(playerUrl, hostName, callback) || foundAny
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
            foundAny = true
        }

        return foundAny
    }

    private suspend fun resolveStremaJWPlayer(
        embedUrl: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
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
                return true
            }
        } catch (_: Exception) {}
        return false
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

