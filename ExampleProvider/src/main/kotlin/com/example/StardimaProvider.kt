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
    override var mainUrl = "https://watch.stardima.com/watch"
    override var name = "ستارديما (Stardima)"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Cartoon, TvType.Anime, TvType.Movie)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    // 1. Browsing Sections
    override val mainPage = mainPageOf(
        "$mainUrl/tvshows/" to "المسلسلات الكرتونية (Cartoons)",
        "$mainUrl/episodes/" to "أحدث الحلقات (Latest Episodes)",
        "$mainUrl/movies/" to "أفلام كرتون (Movies)"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url, headers = headers).document

        val items = document.select(".items article, article.item, .animation-2").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = this.selectFirst(".data h3 a, h3 a, a") ?: return null
        val href = fixUrlNull(linkEl.attr("href")) ?: return null
        
        val title = linkEl.text().ifEmpty { 
            this.selectFirst(".poster img, img")?.attr("alt") ?: "كرتون" 
        }.trim()

        val img = this.selectFirst(".poster img, img")
        val poster = fixUrlNull(img?.attr("data-src")?.ifEmpty { null } ?: img?.attr("src"))

        val isMovie = href.contains("/movies/")
        val type = if (isMovie) TvType.Movie else TvType.Cartoon

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    // 2. Search
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${encodeUrl(query)}"
        val document = app.get(url, headers = headers).document

        return document.select(".result-item, .items article, article.item").mapNotNull {
            it.toSearchResult()
        }
    }

    // 3. Load Show Details & Episodes
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = (document.selectFirst(".data h1, h1.entry-title, h1")?.text() ?: "Cartoon").trim()
        val poster = fixUrlNull(
            document.selectFirst(".poster img")?.attr("data-src")
                ?: document.selectFirst(".poster img")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )
        val banner = fixUrlNull(
            document.selectFirst(".sbackdrop img, .backdrop img")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )
        val description = document.selectFirst(".wp-content p, #info .wp-content, .entry-content p")?.text()

        val episodes = ArrayList<Episode>()
        val episodeElements = document.select("#seasons .episodios li, ul.episodios li")

        if (episodeElements.isNotEmpty()) {
            for (el in episodeElements) {
                val linkEl = el.selectFirst(".episodiocss a, a") ?: continue
                val epHref = fixUrl(linkEl.attr("href"))
                val epNumText = el.selectFirst(".numerando")?.text() ?: ""
                val epTitle = el.selectFirst(".episodiocss a, a")?.text() ?: epNumText

                val seasonNum = epNumText.substringBefore("-").trim().toIntOrNull()
                val episodeNum = epNumText.substringAfter("-").trim().toIntOrNull()

                episodes.add(
                    newEpisode(epHref) {
                        this.name = if (epTitle.isNotBlank()) epTitle else "Episode $episodeNum"
                        this.season = seasonNum
                        this.episode = episodeNum
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

        return if (url.contains("/movies/")) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = banner
                this.plot = description
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = banner
                this.plot = description
            }
        }
    }

    // 4. Resolve Hyperwatching Video ID from Stardima
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var videoId: String? = null

        try {
            val document = app.get(data, headers = headers).document
            val fullHtml = document.html()

            // 1. Direct Regex match on page
            videoId = extractVideoId(fullHtml) ?: extractVideoId(data)

            // 2. Check iframes
            if (videoId == null) {
                for (iframe in document.select("iframe, .playex iframe")) {
                    val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
                    videoId = extractVideoId(src)
                    if (videoId != null) break
                }
            }

            // 3. Check DooPlay AJAX options
            if (videoId == null) {
                val options = document.select("ul#playeroptionsul li, .dooplay_player_option, #playeroptions li")
                val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
                for (opt in options) {
                    val post = opt.attr("data-post")
                    val nume = opt.attr("data-nume")
                    val type = opt.attr("data-type").ifEmpty { "tv" }
                    if (post.isNotEmpty() && nume.isNotEmpty()) {
                        try {
                            val ajaxRes = app.post(
                                ajaxUrl,
                                headers = headers + mapOf("X-Requested-With" to "XMLHttpRequest"),
                                data = mapOf("action" to "doo_player_ajax", "post" to post, "nume" to nume, "type" to type)
                            ).text
                            videoId = extractVideoId(ajaxRes)
                            if (videoId != null) break
                        } catch (_: Exception) {}
                    }
                }
            }

            // 4. Check "صفحة المشاهدة" or "السيرفر" buttons
            if (videoId == null) {
                val candidateLinks = document.select("a").filter {
                    val t = it.text()
                    val h = it.attr("href")
                    t.contains("مشاهدة") || t.contains("السيرفر") || h.contains("/links/") || h.contains("/watch/")
                }
                for (a in candidateLinks) {
                    val target = fixUrl(a.attr("href"))
                    if (target.startsWith("http") && target != data) {
                        try {
                            val subHtml = app.get(target, headers = headers).text
                            videoId = extractVideoId(subHtml) ?: extractVideoId(target)
                            if (videoId != null) break
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Execute Hyperwatching resolution if videoId is found
        if (!videoId.isNullOrBlank()) {
            return fetchHyperwatchingServers(videoId, subtitleCallback, callback)
        }

        // On-screen debug fallback: shows why the player exited
        callback(
            ExtractorLink(
                source = name,
                name = "تعذر العثور على معرف الفيديو (ID Not Found)",
                url = "https://127.0.0.1",
                referer = data,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.VIDEO
            )
        )
        return true
    }

    private fun extractVideoId(text: String): String? {
        val regex = Regex("""hyperwatching\.com/(?:watch|embed)/([a-zA-Z0-9]+)""")
        return regex.find(text)?.groupValues?.get(1)
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
            // Clean browser headers (NO X-Inertia header to prevent 409 Conflict)
            val watchHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
                "Referer" to "$mainUrl/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8"
            )

            val pageHtml = app.get(watchUrl, headers = watchHeaders).text

            // Extract data-page attribute from <div id="app" data-page="...">
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

            // Fallback Regex for server IDs if JSON layout was modified
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

        // 2. Embed URL (strema.top for Lulustream, Uqload, Mixdrop)
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