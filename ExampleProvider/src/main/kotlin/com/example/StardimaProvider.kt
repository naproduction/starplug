package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class StardimaProvider : MainAPI() {
    // The active DooPlay streaming directory for Stardima
    override var mainUrl = "https://watch.stardima.com/watch"
    override var name = "ستارديما (Stardima)"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Cartoon, TvType.Anime, TvType.Movie)

    // Standard desktop User-Agent to ensure DooPlay serves full HTML
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    // 1. Home Page Sections (Shows, Episodes, and Cartoon Movies)
    override val mainPage = mainPageOf(
        "$mainUrl/tvshows/" to "المسلسلات الكرتونية (Cartoons)",
        "$mainUrl/episodes/" to "أحدث الحلقات (Latest Episodes)",
        "$mainUrl/movies/" to "أفلام كرتون (Movies)"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url, headers = headers).document

        // DooPlay theme card selectors
        val items = document.select(".items article, article.item, .animation-2").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = this.selectFirst(".data h3 a, h3 a, a") ?: return null
        val href = fixUrlNull(linkEl.attr("href")) ?: return null
        
        // Extract title from either text or image alt attribute
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

    // 2. Search Engine
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${encodeUrl(query)}"
        val document = app.get(url, headers = headers).document

        return document.select(".result-item, .items article, article.item").mapNotNull {
            it.toSearchResult()
        }
    }

    // 3. Load Show Details & Season/Episode Lists
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = (document.selectFirst(".data h1, h1.entry-title, h1")?.text() ?: "Cartoon").trim()
        val poster = fixUrlNull(
            document.selectFirst(".poster img")?.attr("data-src")
                ?: document.selectFirst(".poster img")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )
        val description = document.selectFirst(".wp-content p, #info .wp-content, .entry-content p")?.text()

        val episodes = ArrayList<Episode>()

        // Check if page contains DooPlay seasons/episodes list
        val episodeElements = document.select("#seasons .episodios li, ul.episodios li")

        if (episodeElements.isNotEmpty()) {
            for (el in episodeElements) {
                val linkEl = el.selectFirst(".episodiocss a, a") ?: continue
                val epHref = fixUrl(linkEl.attr("href"))
                val epNumText = el.selectFirst(".numerando")?.text() ?: ""
                val epTitle = el.selectFirst(".episodiocss a, a")?.text() ?: epNumText

                // Parse episode numbers like "1 - 5" -> season 1, episode 5
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
                this.plot = description
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    // 4. Resolve Third-party Embedded Players
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document

        // Collect all iframes (Dood, Streamtape, Ok.ru, Vidmoly, etc.)
        val iframes = document.select("iframe, .playex iframe, #dooplay_player_response iframe")
            .mapNotNull { it.attr("src").ifEmpty { it.attr("data-src") } }

        for (rawUrl in iframes) {
            val iframeUrl = fixUrl(rawUrl)
            if (iframeUrl.startsWith("http")) {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }

    private fun encodeUrl(str: String): String {
        return java.net.URLEncoder.encode(str, "UTF-8")
    }
}