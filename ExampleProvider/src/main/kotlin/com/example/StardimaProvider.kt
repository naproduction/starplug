package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
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

    // 3. Load Show / Movie Details & Episodes
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

    // 4. Resolve Gateway Links, Watch Pages, and External Video Hosts
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document
        val targetUrls = LinkedHashSet<String>()

        // A. Extract "انقر هنا للإنتقال لصفحة المشاهدة" / "السيرفر" / "مشاهدة" buttons
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

        // B. Extract Links from Download & Mirror Tables
        document.select("table a, .links a, .download-links a, a[href*='/links/']").forEach { a ->
            val href = a.attr("href").trim()
            if (href.isNotEmpty() && !href.startsWith("#") && !href.startsWith("javascript:")) {
                targetUrls.add(fixUrl(href))
            }
        }

        // C. Check for direct iframes on the current page
        document.select("iframe, .playex iframe, #dooplay_player_response iframe").forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.isNotBlank()) targetUrls.add(fixUrl(src))
        }

        // D. Also check DooPlay AJAX options if configured
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

        // E. Crawl into each found link (resolving gateways & extractors)
        for (rawUrl in targetUrls) {
            resolveAndExtract(rawUrl, data, subtitleCallback, callback)
        }

        return true
    }

    private suspend fun resolveAndExtract(
        targetUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = fixUrl(targetUrl)
        if (!fixed.startsWith("http")) return

        // 1. If it's directly an external host (Streamtape, Doodstream, Vidmoly, Ok.ru, Mega, etc.)
        if (!fixed.contains("stardima.com") && !fixed.contains("stardima.app")) {
            loadExtractor(fixed, referer, subtitleCallback, callback)
            return
        }

        // 2. If it's an internal Stardima gateway/watch page, fetch it to find the real player
        try {
            val subDoc = app.get(fixed, headers = headers).document

            // Check for iframes inside the gateway page
            val iframes = subDoc.select("iframe").mapNotNull { 
                it.attr("src").ifEmpty { it.attr("data-src") } 
            }
            for (iframe in iframes) {
                val fixedIframe = fixUrl(iframe)
                if (fixedIframe.startsWith("http") && !fixedIframe.contains("stardima.com")) {
                    loadExtractor(fixedIframe, fixed, subtitleCallback, callback)
                }
            }

            // Check for external download/redirect buttons (e.g. "تحميل الرابط")
            subDoc.select("a[href]").forEach { a ->
                val href = fixUrl(a.attr("href"))
                if (href.startsWith("http") && !href.contains("stardima.com") && !href.contains("stardima.app")) {
                    loadExtractor(href, fixed, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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