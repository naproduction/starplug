package com.example

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

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Releases",
        "$mainUrl/category/cartoons/" to "Cartoons"
    )

    // Updated signature: MainPageRequest instead of MainPageData, and HomePageResponse? return type
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
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
        val img = this.selectFirst("img")
        val poster = fixUrlNull(img?.attr("src")?.ifEmpty { null } ?: img?.attr("data-src"))

        return newAnimeSearchResponse(title, href, TvType.Cartoon) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("article, .item-video, .video-card").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1")?.text() ?: "Episode"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")

        val episodes = ArrayList<Episode>()
        val episodeElements = document.select(".episodes-list a, .list-episodes a")

        if (episodeElements.isNotEmpty()) {
            for ((index, el: Element) in episodeElements.withIndex()) {
                val epHref = fixUrl(el.attr("href"))
                val epName = el.text().ifEmpty { "Episode ${index + 1}" }
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

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframes = document.select("iframe")

        for (iframe in iframes) {
            val src = iframe.attr("src")
            if (src.isNotEmpty()) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }

        return true
    }
}