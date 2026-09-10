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

    // 1. Home Page Sections
    override val mainPage = mainPageOf(
        "$mainUrl/newrelases" to "المضاف حديثا (Latest)",
        "$mainUrl/mosalsalat" to "مسلسلات (Series)",
        "$mainUrl/aflam" to "أفلام (Movies)"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(request.data, headers = headers).document

        val items = document.select("a[href*='/tvshow/'], a[href*='/movie/']").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (!href.contains("/tvshow/") && !href.contains("/movie/")) return null
        if (href.contains("/play/")) return null // Filter out single episode buttons

        val img = this.selectFirst("img") ?: return null
        val rawAlt = img.attr("alt").ifEmpty { this.attr("title").ifEmpty { this.text() } }
        if (rawAlt.isBlank()) return null

        // Clean titles: "Poster for همتارو" -> "همتارو"
        val title = rawAlt.replace("Poster for ", "").replace("Poster for", "").trim()

        val poster = fixUrlNull(
            img.attr("src").ifEmpty { img.attr("data-src") }
        )

        val isMovie = href.contains("/movie/")
        val type = if (isMovie) TvType.Movie else TvType.Cartoon

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    // 2. Search
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${encodeUrl(query)}"
        val document = app.get(url, headers = headers).document

        return document.select("a[href*='/tvshow/'], a[href*='/movie/']").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    // 3. Load Show / Cartoon Details & All Episode Links
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1")?.text()?.trim() 
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("-")?.trim()
            ?: "Cartoon"

        val poster = fixUrlNull(
            document.selectFirst("img[src*='/posters/'], img[src*='image.tmdb.org']")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )
        val description = document.select("p").map { it.text().trim() }.firstOrNull { it.length > 30 }

        val episodes = ArrayList<Episode>()

        // Extract all episode links: /tvshow/.../play/...
        val episodeElements = document.select("a[href*='/play/']")
        if (episodeElements.isNotEmpty()) {
            for ((index, el) in episodeElements.distinctBy { it.attr("href") }.withIndex()) {
                val epHref = fixUrl(el.attr("href"))
                val epName = el.text().trim().ifEmpty { "الحلقة ${index + 1}" }
                episodes.add(
                    newEpisode(epHref) {
                        this.name = epName
                        this.episode = index + 1
                    }
                )
            }
        } else {
            episodes.add(
                newEpisode(url) {
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

    // 4. Resolve Hyperwatching Video ID & Playback
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document
        val html = document.html()

        // Extract the Hyperwatching video ID (e.g. 6YQBcQR8DT1z) from the play page iframe
        val videoId = Regex("""hyperwatching\.com/(?:watch|embed)/([a-zA-Z0-9]+)""").find(html)?.groupValues?.get(1)
            ?: Regex("""hyperwatching\.com/(?:watch|embed)/([a-zA-Z0-9]+)""").find(data)?.groupValues?.get(1)

        if (!videoId.isNullOrBlank()) {
            return fetchHyperwatchingServers(videoId, subtitleCallback, callback)
        }

        // Direct iframes fallback
        document.select("iframe").forEach { iframe ->
            val src = fixUrl(iframe.attr("src").ifEmpty { iframe.attr("data-src") })
            if (src.startsWith("http") && !src.contains("hyperwatching.com")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return true
    }

    // 5. Query /embed/{id}/server/{serverId}/url for all servers
    private suspend fun fetchHyperwatchingServers(
        videoId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = "https://v2.hyperwatching.com/watch/$videoId"
        val serverList = ArrayList<Pair<String, String>>() // Pair(serverId, serverName)

        try {
            // Standard browser headers (no X-Inertia header to prevent 409 Conflict)
            val watchHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
                "Referer" to "$mainUrl/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
            )

            val pageHtml = app.get(watchUrl, headers = watchHeaders).text

            // Read server IDs from Inertia <div id="app" data-page="...">
            val doc = Jsoup.parse(pageHtml)
            val dataPage = doc.selectFirst("#app, [data-page]")?.attr("data-page")

            if (!dataPage.isNullOrBlank()) {
                val json = JSONObject(dataPage)
                val props = json.optJSONObject("props")
                val serversArr = props?.optJSONArray("servers")
                    ?: props?.optJSONObject("video")?.optJSONArray("servers")
                    ?: props?.optJSONObject("episode")?.optJSONArray("servers")

                if (serversArr != null) {
                    for (i in 0 until serversArr.length()) {
                        val obj = serversArr.optJSONObject(i) ?: continue
                        val id = obj.optString("id").ifEmpty { obj.optInt("id").toString() }
                        val name = obj.optString("name").ifEmpty { obj.optString("slug", "Server") }
                        if (id.isNotEmpty()) {
                            serverList.add(Pair(id, name))
                        }
                    }
                }
            }

            // Fallback regex for server IDs if JSON is structured differently
            if (serverList.isEmpty()) {
                val regex = Regex("""["']id["']\s*:\s*(\d+)\s*,\s*["']name["']\s*:\s*["']([^"']+)["']""")
                for (match in regex.findAll(pageHtml)) {
                    serverList.add(Pair(match.groupValues[1], match.groupValues[2]))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Query the confirmed endpoint for each server: /embed/{videoId}/server/{serverId}/url
        val apiHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Referer" to watchUrl,
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/plain, */*"
        )

        var linkFound = false

        for ((serverId, serverName) in serverList.distinctBy { it.first }) {
            try {
                val serverUrlEndpoint = "https://v2.hyperwatching.com/embed/$videoId/server/$serverId/url"
                val res = app.get(serverUrlEndpoint, headers = apiHeaders).text

                if (res.contains("\"status\"") && res.contains("\"ok\"")) {
                    processServerJson(res, serverName, watchUrl, subtitleCallback, callback)
                    linkFound = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return linkFound
    }

    private suspend fun processServerJson(
        jsonString: String,
        serverName: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val json = try { JSONObject(jsonString) } catch (e: Exception) { return }
        if (json.optString("status") != "ok") return

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
                            name = "ستارديما - $serverName ${if (label.isNotEmpty()) "($label)" else ""}".trim(),
                            url = fileUrl,
                            referer = referer,
                            quality = Qualities.P720.value,
                            type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        )
                    )
                }
            }
        }

        // 2. Embed URL (e.g. strema.top for Lulustream, Uqload, Mixdrop)
        val embedUrl = json.optString("embed_url")
        if (embedUrl.isNotEmpty()) {
            if (!loadExtractor(embedUrl, referer, subtitleCallback, callback)) {
                resolveStremaJWPlayer(embedUrl, serverName, callback)
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