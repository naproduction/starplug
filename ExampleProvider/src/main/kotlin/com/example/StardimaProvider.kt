package com.stardima

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class StardimaProvider : MainAPI() {
    override var mainUrl = "https://www.stardima.com"
    override var name = "Stardima"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Cartoon, TvType.Anime)

    // 1. Home Page Sections
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Releases",
        "$mainUrl/category/cartoons/" to "Cartoons"
    )

    override suspend fun getMainPage(page: Int, request: MainPageData): HomePageResponse {
        val document = app.get(request.data).document
        val homeItems = document.select("article, .item-video, .video-card").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, homeItems)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = this.selectFirst("a") ?: return null
        val href = fixUrlNull(linkEl.attr("href")) ?: return null
        val title = linkEl.attr("title").ifEmpty { this.select(".title, h2, h3").text() }
        val poster = fixUrlNull(this.selectFirst("img")?.attr("src") ?: this.selectFirst("img")?.attr("data-src"))

        return newAnimeSearchResponse(title, href, TvType.Cartoon) {
            this.posterUrl = poster
        }
    }

    // 2. Search
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("article, .item-video, .video-card").mapNotNull {
            it.toSearchResult()
        }
    }

    // 3. Load Episodes
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1")?.text() ?: "Episode"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")

        val episodes = mutableListOf<Episode>()
        val episodeElements = document.select(".episodes-list a, .list-episodes a")

        if (episodeElements.isNotEmpty()) {
            episodeElements.forEachIndexed { index, el ->
                val epHref = fixUrl(el.attr("href"))
                val epName = el.text().ifEmpty { "Episode ${index + 1}" }
                episodes.add(newEpisode(epHref) {
                    this.name = epName
                    this.episode = index + 1
                })
            }
        } else {
            episodes.add(newEpisode(url) {
                this.name = title
                this.episode = 1
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = poster
        }
    }

    // 4. Video Stream Extractor
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframes = document.select("iframe").mapNotNull { it.attr("src") }

        for (iframeUrl in iframes) {
            loadExtractor(fixUrl(iframeUrl), data, subtitleCallback, callback)
        }

        return true
    }
}