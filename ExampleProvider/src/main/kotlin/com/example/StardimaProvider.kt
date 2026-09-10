package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
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

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${encodeUrl(query)}"
        val document = app.get(url, headers = headers).document

        return document.select(".result-item, .items article, article.item").mapNotNull {
            it.toSearchResult()
        }
    }

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

    // 4. Extract Server Selection Dialog & Video Hosts
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document

        // Collect all links pointing to servers or watch pages
        val serverCandidates = LinkedHashMap<String, String>() // URL -> Server Name

        // A. Capture the Server List from Screenshot 2 (Uqload, Lulustream, Mixdrop, Goodstream, etc.)
        document.select(".server-item, .server, li[data-url], li[data-src], .player-servers a, a[data-url], a[data-src], table a, .links a").forEach { el ->
            val name = el.selectFirst(".title, h4, span, p")?.text()?.ifEmpty { el.text() } ?: "سيرفر"
            val rawUrl = el.attr("data-url").ifEmpty {
                el.attr("data-src").ifEmpty {
                    el.attr("href")
                }
            }.trim()

            if (rawUrl.isNotEmpty() && !rawUrl.startsWith("#") && !rawUrl.startsWith("javascript:")) {
                serverCandidates[fixUrl(rawUrl)] = name
            }
        }

        // B. Capture Watch Page Buttons
        document.select("a").forEach { a ->
            val text = a.text()
            val href = a.attr("href").trim()
            if (href.isNotEmpty() && !href.startsWith("#") && !href.startsWith("javascript:")) {
                if (text.contains("صفحة المشاهدة") || text.contains("مشاهدة") || text.contains("السيرفر")) {
                    serverCandidates[fixUrl(href)] = text.trim()
                }
            }
        }

        // C. Capture direct iframes
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                serverCandidates[fixUrl(src)] = "سيرفر رئيسي"
            }
        }

        // Process each discovered server
        for ((targetUrl, serverName) in serverCandidates) {
            resolveServerLink(targetUrl, serverName, data, subtitleCallback, callback)
        }

        return true
    }

    private suspend fun resolveServerLink(
        url: String,
        serverName: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = fixUrl(url)
        if (!fixedUrl.startsWith("http")) return

        // 1. Try standard CloudStream extractor first
        if (isKnownHost(fixedUrl)) {
            loadExtractor(fixedUrl, referer, subtitleCallback, callback)
        }

        // 2. Resolve JWPlayer / Packed JS / Custom Hosts (e.g. strema.top, lulustream, uqload)
        try {
            val response = app.get(fixedUrl, headers = headers + mapOf("Referer" to referer))
            val html = response.text
            val doc = response.document

            // Check if page contains packed JS (Dean Edwards eval(function(p,a,c,k,e,d)...))
            val unpacked = unpackJs(html)
            val fullContent = "$html\n$unpacked"

            // Look for master.m3u8, direct .mp4, or JWPlayer sources
            val streamRegex = Regex("""["']?(?:file|src|url)["']?\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
            for (match in streamRegex.findAll(fullContent)) {
                val streamUrl = match.groupValues[1].replace("\\/", "/")
                val isHls = streamUrl.contains(".m3u8")

                callback(
                    ExtractorLink(
                        source = this.name,
                        name = serverName.ifEmpty { "ستارديما" },
                        url = streamUrl,
                        referer = fixedUrl,
                        quality = Qualities.P720.value,
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
            }

            // Look for nested iframes on gateway pages
            doc.select("iframe").forEach { iframe ->
                val nestedSrc = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
                if (nestedSrc.isNotBlank() && nestedSrc != fixedUrl) {
                    loadExtractor(fixUrl(nestedSrc), fixedUrl, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isKnownHost(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("uqload") || 
               lower.contains("mixdrop") || 
               lower.contains("streamtape") || 
               lower.contains("dood") || 
               lower.contains("vidmoly") || 
               lower.contains("lulustream") || 
               lower.contains("luluvdo") || 
               lower.contains("ok.ru")
    }

    // De-obfuscator for Dean Edwards eval(function(p,a,c,k,e,d)...) JS packers
    private fun unpackJs(script: String): String {
        return try {
            val packerPattern = Regex("""eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""")
            val match = packerPattern.find(script) ?: return ""

            val payload = match.groupValues[1]
            val radix = match.groupValues[2].toInt()
            val count = match.groupValues[3].toInt()
            val symTab = match.groupValues[4].split("|")

            fun lookup(word: String): String {
                val idx = if (radix <= 36) {
                    word.toIntOrNull(radix) ?: -1
                } else {
                    -1
                }
                return if (idx in 0 until symTab.size && symTab[idx].isNotEmpty()) symTab[idx] else word
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