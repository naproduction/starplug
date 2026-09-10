package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Element

class StardimaProvider : MainAPI() {
    // CONFIRMED: real domain is www.stardima.com, not watch.stardima.com/watch
    override var mainUrl = "https://www.stardima.com"
    override var name = "ستارديما (Stardima)"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Cartoon, TvType.Anime, TvType.Movie)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    // CONFIRMED real listing routes (there is no /tvshows/, /episodes/, /movies/)
    override val mainPage = mainPageOf(
        "$mainUrl/newrelases" to "المضاف حديثا (Latest)",
        "$mainUrl/mosalsalat" to "مسلسلات (Series)",
        "$mainUrl/aflam" to "أفلام (Movies)"
    )

    // NOTE (unverified beyond page 1): these listing pages load additional items via a
    // "load more" AJAX call ("جاري تحميل المزيد..." was visible in the raw page), not a
    // /page/N/ URL like dooplay sites. I don't have visibility into that XHR call (no
    // JS execution available on my end). Page 1 works correctly; see the bottom note
    // for how to get pagination fully working.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = headers).document
        val items = document.select("a[href*=/tvshow/], a[href*=/movie/]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    // Matches by href pattern instead of guessed CSS classes, since I could only see
    // text-extracted content, not the real DOM/class names. This is more resilient to
    // markup changes than copying class names I never actually verified.
    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (!href.contains("/tvshow/") && !href.contains("/movie/")) return null
        // skip nav/footer links like "/tvshow/1125" appearing inside unrelated widgets
        if (href.trimEnd('/') == mainUrl) return null

        val img = this.selectFirst("img")
        val title = img?.attr("alt")?.ifBlank { null }
            ?: this.attr("title").ifBlank { null }
            ?: this.text().ifBlank { null }
            ?: return null

        val poster = fixUrlNull(
            img?.attr("data-src")?.ifEmpty { null } ?: img?.attr("src")
        )

        val isMovie = href.contains("/movie/")
        val type = if (isMovie) TvType.Movie else TvType.Cartoon

        return newAnimeSearchResponse(title.trim(), href, type) {
            this.posterUrl = poster
        }
    }

    // UNVERIFIED: I could not find the true text-search endpoint. The only search-like
    // route I actually saw on the site was /search/{tag-slug} used for category/tag
    // browsing (e.g. /search/asdarat-kaml-bdon-hthf), which is not a free-text search.
    // This is a best guess - please confirm/replace with the real endpoint (see notes).
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${encodeUrl(query)}"
        val document = app.get(url, headers = headers).document
        return document.select("a[href*=/tvshow/], a[href*=/movie/]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: name
        val poster = fixUrlNull(
            document.selectFirst("img[src*=/storage/posters/], img[src*=image.tmdb.org]")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )
        val description = document.select("p").map { it.text() }.firstOrNull { it.length > 30 }

        val playHref = fixUrlNull(
            document.select("a").firstOrNull { it.text().contains("تشغيل") }?.attr("href")
        )

        val isMovie = url.contains("/movie/")

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, playHref ?: url) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        // NOTE (important limitation): season/episode selection on the tvshow page is
        // rendered client-side after load (the season tabs in the raw HTML point to "#",
        // meaning Vue swaps content in-browser via an API call I can't see). All I can
        // reliably get here is the single "تشغيل" link for the show's current/latest
        // episode - not the full episode list. See bottom note for how to fix this.
        val episodes = listOf(
            newEpisode(playHref ?: url) {
                this.name = title
                this.episode = 1
            }
        )

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document

        // CONFIRMED: the play page embeds exactly one iframe pointing at a *separate*
        // platform - e.g. https://v2.hyperwatching.com/watch/xxxxxxxx. That page is what
        // renders the server-selection dialog in your screenshot (Uqload, Lulustream,
        // Goodstream, Savefiles, Mixdrop, Streamhg...).
        val embedUrl = fixUrlNull(
            document.selectFirst("iframe")?.let { it.attr("src").ifEmpty { it.attr("data-src") } }
        ) ?: return false

        return resolveGateway(embedUrl, data, subtitleCallback, callback)
    }

    private suspend fun resolveGateway(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        // Case 1: the iframe itself is already a known host's own embed page.
        if (loadExtractor(embedUrl, referer, subtitleCallback, callback)) {
            found = true
        }

        // Case 2: CONFIRMED - the resolver behind each server on hyperwatching.com
        // returns JSON like:
        //   {status:"ok", query:{id, host, download}, embed_url, download_url, sources:[...], title}
        // e.g. for host="lulustream": embed_url = "https://strema.top/embed/jxBTNUkQ"
        // We don't yet know the resolver's own request URL (only saw the response body),
        // so this is wired as a pluggable function - see resolveKnownServer() below and
        // the TODO there. Once you send me the actual request URL from DevTools, this
        // becomes a real per-server loop instead of guesswork.

        // Case 3: GENERAL "catch the real video request" fallback - this is the
        // mechanism you asked about. It doesn't need to know hyperwatching's API at all:
        // it opens the page in an actual WebView (so all of hyperwatching's JS really
        // runs, servers get auto/queued, players initialize) and watches real network
        // traffic for the request that actually is the video (.m3u8 or .mp4), then
        // builds a playable link from whatever URL that turns out to be. This is the
        // exact pattern CloudStream's own GenericM3U8 / Filesim extractors use for
        // JS-walled gateways like this one.
        if (!found) {
            try {
                val resolver = WebViewResolver(
                    interceptUrl = Regex("""\.m3u8|\.mp4"""),
                    additionalUrls = listOf(Regex("""\.m3u8|\.mp4""")),
                    useOkhttp = false,
                    timeout = 20_000L
                )
                val response = app.get(embedUrl, referer = referer, interceptor = resolver)
                val hitUrl = response.url

                if (hitUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        name,
                        hitUrl,
                        embedUrl,
                        headers = response.headers.toMap()
                    ).forEach { callback(it); found = true }
                } else if (hitUrl.contains(".mp4")) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = hitUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = embedUrl
                            this.quality = Qualities.Unknown.value
                            this.headers = response.headers.toMap()
                        }
                    )
                    found = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return found
    }

    /**
     * TODO (needs your DevTools capture to finish): call hyperwatching's per-server
     * resolver directly instead of relying on the WebView fallback above. From the
     * response you already captured we know the JSON shape - we just need the actual
     * request URL/method. Once you send it, replace RESOLVER_URL_TEMPLATE below, e.g.:
     *   "https://v2.hyperwatching.com/api/source?id=%s&host=%s&download="
     * and this function will work for every server on the page (Uqload, Goodstream,
     * Savefiles, Mixdrop, Streamhg...), not just Lulustream, since "host" is just a
     * query param on (most likely) one shared endpoint.
     */
    private suspend fun resolveKnownServer(
        id: String,
        host: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resolverUrlTemplate = "" // <-- paste the real request URL pattern here
        if (resolverUrlTemplate.isEmpty()) return false

        val resolverUrl = resolverUrlTemplate.format(id, host)
        val res = app.get(resolverUrl, headers = headers, referer = referer)
        val json = try {
            JSONObject(res.text)
        } catch (e: Exception) {
            return false
        }

        if (json.optString("status") != "ok") return false

        var found = false

        // "sources" is very likely where the direct file/m3u8 URL(s) live - please send
        // me its expanded content so I can parse it precisely instead of guessing keys.
        json.optJSONArray("sources")?.let { sources ->
            for (i in 0 until sources.length()) {
                val src = sources.optJSONObject(i) ?: continue
                val fileUrl = src.optString("file").ifEmpty { src.optString("url") }
                if (fileUrl.isEmpty()) continue
                val label = src.optString("label")
                val isHls = fileUrl.contains(".m3u8")
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name - $host${if (label.isNotEmpty()) " $label" else ""}",
                        url = fileUrl,
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = resolverUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }

        // Fall back to embed_url (e.g. strema.top/embed/xxx) if no direct sources given
        if (!found) {
            val embedUrl = json.optString("embed_url")
            if (embedUrl.isNotEmpty()) {
                if (loadExtractor(embedUrl, resolverUrl, subtitleCallback, callback)) {
                    found = true
                } else {
                    // strema.top isn't a registered CloudStream extractor - fall back to
                    // the generic WebView video-catcher on that page too
                    found = resolveGateway(embedUrl, resolverUrl, subtitleCallback, callback)
                }
            }
        }

        return found
    }

    // Widened vs. the original list - the original's isKnownHost() gate was actually
    // blocking Goodstream/Savefiles/StreamHG (visible in your screenshot) from ever
    // reaching loadExtractor at all. loadExtractor() already safely no-ops on unknown
    // hosts, so there's no need for a restrictive allowlist before calling it - this
    // check is now only used to decide which stray URLs found in a JSON/JS blob are
    // worth trying, not to gate the servers that came from the server list itself.
    private fun isLikelyHostEmbed(url: String): Boolean {
        val lower = url.lowercase()
        val knownHosts = listOf(
            "uqload", "mixdrop", "streamtape", "dood", "vidmoly", "lulustream",
            "luluvdo", "ok.ru", "streamwish", "streamhg", "goodstream", "savefiles",
            "vidhide", "filemoon", "voe.sx", "vtube", "streambg"
        )
        return knownHosts.any { lower.contains(it) }
    }

    // De-obfuscator for Dean Edwards eval(function(p,a,c,k,e,d)...) JS packers
    private fun unpackJs(script: String): String {
        return try {
            val packerPattern = Regex("""eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""")
            val match = packerPattern.find(script) ?: return ""

            val payload = match.groupValues[1]
            val radix = match.groupValues[2].toInt()
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

