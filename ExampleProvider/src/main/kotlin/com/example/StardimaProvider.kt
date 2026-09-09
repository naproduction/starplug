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

    // 3. Load Details, Episode Lists & Backdrop Banners
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = (document.selectFirst(".data h1, h1.entry-title, h1")?.text() ?: "Cartoon").trim()
        val poster = fixUrlNull(
            document.selectFirst(".poster img")?.attr("data-src")
                ?: document.selectFirst(".poster img")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )
        // Extract wide backdrop banner for TV
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
            // Single Movie or Direct Episode Page
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

    // 4. Resolve Servers and Player Options via DooPlay AJAX
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document

        // Check for DooPlay server options (Server 1, Server 2, etc.)
        val playerOptions = document.select("ul#playeroptionsul li, .dooplay_player_option, #playeroptions li")

        val ajaxHeaders = headers + mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
        )
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"

        for (option in playerOptions) {
            val postId = option.attr("data-post").ifEmpty { null }
            val nume = option.attr("data-nume").ifEmpty { null }
            val type = option.attr("data-type").ifEmpty { "tv" }

            if (postId != null && nume != null) {
                try {
                    // Send AJAX request for this specific server option
                    var response = app.post(
                        ajaxUrl,
                        headers = ajaxHeaders,
                        data = mapOf(
                            "action" to "doo_player_ajax",
                            "post" to postId,
                            "nume" to nume,
                            "type" to type
                        )
                    ).text

                    // Fallback to dt_player_ajax if doo_player_ajax returned empty or 0
                    if (response.isBlank() || response == "0") {
                        response = app.post(
                            ajaxUrl,
                            headers = ajaxHeaders,
                            data = mapOf(
                                "action" to "dt_player_ajax",
                                "post" to postId,
                                "nume" to nume,
                                "type" to type
                            )
                        ).text
                    }

                    val embedUrl = extractEmbedUrl(response)
                    if (!embedUrl.isNullOrBlank()) {
                        loadExtractor(embedUrl, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Check for any direct/static iframes on the page
        val staticIframes = document.select("iframe, .playex iframe, #dooplay_player_response iframe")
            .mapNotNull { it.attr("src").ifEmpty { it.attr("data-src") } }

        for (rawUrl in staticIframes) {
            val iframeUrl = fixUrl(rawUrl)
            if (iframeUrl.startsWith("http")) {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }

        // Check download / external server link buttons
        val externalLinks = document.select(".links a, .download-links a, a.btn-download, #download a")
        for (a in externalLinks) {
            val href = a.attr("href")
            if (href.startsWith("http") && !href.contains(mainUrl)) {
                loadExtractor(href, data, subtitleCallback, callback)
            }
        }

        return true
    }

    private fun extractEmbedUrl(response: String): String? {
        // 1. Check if the response contains an HTML iframe
        val doc = Jsoup.parse(response)
        val iframeSrc = doc.selectFirst("iframe")?.attr("src")
        if (!iframeSrc.isNullOrBlank()) {
            return fixUrl(iframeSrc)
        }

        // 2. Check if JSON response: {"embed_url": "https://..."}
        val jsonRegex = Regex("""["']embed_url["']\s*:\s*["']([^"']+)["']""")
        val match = jsonRegex.find(response)
        if (match != null) {
            return fixUrl(match.groupValues[1].replace("\\/", "/"))
        }

        // 3. Fallback: search for any URL inside src="..."
        val srcRegex = Regex("""src=["'](https?://[^"']+)["']""")
        val srcMatch = srcRegex.find(response)
        if (srcMatch != null) {
            return fixUrl(srcMatch.groupValues[1].replace("\\/", "/"))
        }

        return null
    }

    private fun encodeUrl(str: String): String {
        return java.net.URLEncoder.encode(str, "UTF-8")
    }
}