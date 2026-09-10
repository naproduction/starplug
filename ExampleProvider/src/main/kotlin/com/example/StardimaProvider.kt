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

    // 1. Home Page Sections
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

    // 3. Load Details & Episode Lists
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

    // 4. Resolve All Stardima Plyr Streams, Gateways & Video Hosts
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document
        val targetUrls = LinkedHashSet<String>()

        // 1. Direct iframes on the episode page
        document.select("iframe, .playex iframe, #dooplay_player_response iframe").forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.isNotBlank()) targetUrls.add(fixUrl(src))
        }

        // 2. Watch page buttons: "انقر هنا للإنتقال لصفحة المشاهدة", "مشاهدة اونلاين. السيرفر X"
        document.select("a").forEach { a ->
            val text = a.text()
            val href = a.attr("href").trim()
            if (href.isNotEmpty() && !href.startsWith("#") && !href.startsWith("javascript:")) {
                if (text.contains("صفحة المشاهدة") || 
                    text.contains("مشاهدة") || 
                    text.contains("السيرفر") || 
                    text.contains("سيرفر")) {
                    targetUrls.add(fixUrl(href))
                }
            }
        }

        // 3. Download & mirror links table
        document.select("table a, .links a, .download-links a, a[href*='/links/']").forEach { a ->
            val href = a.attr("href").trim()
            if (href.isNotEmpty() && !href.startsWith("#") && !href.startsWith("javascript:")) {
                targetUrls.add(fixUrl(href))
            }
        }

        // 4. DooPlay AJAX options
        val playerOptions = document.select("ul#playeroptionsul li, .dooplay_player_option, #playeroptions li")
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        for (opt in playerOptions) {
            val postId = opt.attr("data-post").ifEmpty { null }
            val nume = opt.attr("data-nume").ifEmpty { null }
            val type = opt.attr("data-type").ifEmpty { "tv" }
            if (postId != null && nume != null) {
                try {
                    val res = app.post(
                        ajaxUrl,
                        headers = headers + mapOf("X-Requested-With" to "XMLHttpRequest"),
                        data = mapOf("action" to "doo_player_ajax", "post" to postId, "nume" to nume, "type" to type)
                    ).text
                    extractEmbedUrl(res)?.let { targetUrls.add(it) }
                } catch (_: Exception) {}
            }
        }

        // Resolve every candidate URL found
        for (rawUrl in targetUrls) {
            extractAllStreams(rawUrl, data, subtitleCallback, callback, 0)
        }

        return true
    }

    private suspend fun extractAllStreams(
        targetUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int
    ) {
        if (depth > 2) return
        val fixedUrl = fixUrl(targetUrl)
        if (!fixedUrl.startsWith("http")) return

        // If it's a standard third-party video host (Ok.ru, Streamtape, Doodstream, etc.)
        if (isKnownExternalHost(fixedUrl)) {
            loadExtractor(fixedUrl, referer, subtitleCallback, callback)
            return
        }

        try {
            val response = app.get(fixedUrl, headers = headers + mapOf("Referer" to referer))
            val doc = response.document
            val html = response.text

            // A. EXTRACT DIRECT PLYR HTML5 <video> & <source> TAGS (Stardima player.php)
            val videoSources = doc.select("video source, source[src], video[src]")
            for (source in videoSources) {
                val src = source.attr("src").trim()
                val qualityLabel = source.attr("size").ifEmpty { source.attr("res") }
                val quality = qualityLabel.toIntOrNull() ?: Qualities.P720.value

                if (src.startsWith("http")) {
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "ستارديما (${if (qualityLabel.isNotEmpty()) "${qualityLabel}p" else "سيرفر رئيسي"})",
                            url = src,
                            referer = fixedUrl,
                            quality = quality,
                            type = if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        )
                    )
                }
            }

            // B. EXTRACT PLYR JS SOURCES & GOOGLE CDN PLAYBACK LINKS
            val jsSourcesRegex = Regex("""["']?(?:file|src|url)["']?\s*:\s*["'](https?://[^"']+)["']""")
            for (match in jsSourcesRegex.findAll(html)) {
                val streamUrl = match.groupValues[1].replace("\\/", "/")
                if (isValidVideoStream(streamUrl)) {
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "ستارديما (HD)",
                            url = streamUrl,
                            referer = fixedUrl,
                            quality = Qualities.P720.value,
                            type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        )
                    )
                }
            }

            // C. Crawl nested iframes (e.g. embed players, player.php)
            val iframes = doc.select("iframe").mapNotNull { 
                it.attr("src").ifEmpty { it.attr("data-src") } 
            }
            for (iframe in iframes) {
                val fixedIframe = fixUrl(iframe)
                if (fixedIframe.startsWith("http") && fixedIframe != fixedUrl) {
                    extractAllStreams(fixedIframe, fixedUrl, subtitleCallback, callback, depth + 1)
                }
            }

            // D. Crawl link/download redirect buttons (e.g. "تحميل الرابط")
            val candidateLinks = doc.select("a[href]").mapNotNull { it.attr("href") }
            for (cLink in candidateLinks) {
                val fixedLink = fixUrl(cLink)
                if (fixedLink.startsWith("http") && fixedLink != fixedUrl) {
                    if (isKnownExternalHost(fixedLink)) {
                        loadExtractor(fixedLink, fixedUrl, subtitleCallback, callback)
                    } else if (fixedLink.contains("/links/") || fixedLink.contains("player.php")) {
                        extractAllStreams(fixedLink, fixedUrl, subtitleCallback, callback, depth + 1)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isKnownExternalHost(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("ok.ru") || 
               lower.contains("streamtape") || 
               lower.contains("dood") || 
               lower.contains("vidmoly") || 
               lower.contains("dailymotion") || 
               lower.contains("youtube") || 
               lower.contains("mp4upload") || 
               lower.contains("filemoon") || 
               lower.contains("uqload")
    }

    private fun isValidVideoStream(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains(".jpg") || lower.contains(".png") || lower.contains(".webp") || 
            lower.contains(".css") || lower.contains(".js") || lower.contains(".vtt") || 
            lower.contains("google-analytics") || lower.contains("googletagmanager")) {
            return false
        }
        return lower.contains(".mp4") || 
               lower.contains(".m3u8") || 
               lower.contains("googleusercontent.com") || 
               lower.contains("videoplayback") || 
               lower.contains("video.google")
    }

    private fun extractEmbedUrl(response: String): String? {
        val doc = Jsoup.parse(response)
        val iframeSrc = doc.selectFirst("iframe")?.attr("src")
        if (!iframeSrc.isNullOrBlank()) return fixUrl(iframeSrc)

        val jsonRegex = Regex("""["']embed_url["']\s*:\s*["']([^"']+)["']""")
        val match = jsonRegex.find(response)
        if (match != null) return fixUrl(match.groupValues[1].replace("\\/", "/"))

        val srcRegex = Regex("""src=["'](https?://[^"']+)["']""")
        val srcMatch = srcRegex.find(response)
        if (srcMatch != null) return fixUrl(srcMatch.groupValues[1].replace("\\/", "/"))

        return null
    }

    private fun encodeUrl(str: String): String {
        return java.net.URLEncoder.encode(str, "UTF-8")
    }
}