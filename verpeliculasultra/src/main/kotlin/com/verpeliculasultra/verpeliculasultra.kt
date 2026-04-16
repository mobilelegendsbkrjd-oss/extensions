package com.verpeliculasultra

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class VerPeliculasUltra : MainAPI() {

    override var mainUrl = "https://verpeliculasultra.com"
    override var name = "VerPeliculasUltra"
    override val hasMainPage = true
    override var lang = "es"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.AnimeMovie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/lastnews/" to "Últimas Películas",
        "$mainUrl/xfsearch/iframe-latino/" to "Latino",
        "$mainUrl/xfsearch/iframe-espanol/" to "Español",
        "$mainUrl/xfsearch/iframe-subtitulada/" to "Subtitulado",
        "$mainUrl/xfsearch/mas-vistas/" to "Más vistas"
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val href = fixUrl(a.attr("href"))

        var title = a.attr("title")
        if (title.isBlank()) {
            title = selectFirst("h2,h3,.shortf-link")?.text()?.trim().orEmpty()
        }
        if (title.isBlank()) return null

        val poster = selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = poster?.let { fixUrl(it) }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else request.data + "page/$page/"
        val doc = app.get(url).document

        val list = doc.select("div.shortf").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.post(
            "$mainUrl/index.php",
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to query
            )
        ).document

        return doc.select("div.shortf").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Sin título"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst(".full-text,.story,.description")?.text()?.trim()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.plot = plot
        }
    }

    private suspend fun extractHg(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("(m3u8|master\\.txt)"),
                additionalUrls = listOf(
                    Regex("(m3u8|master\\.txt)")
                ),
                useOkhttp = false,
                timeout = 15000L
            )

            val finalUrl = app.get(
                url,
                referer = referer,
                interceptor = resolver
            ).url

            M3u8Helper.generateM3u8(
                source = "HGCloud",
                streamUrl = finalUrl,
                referer = "$url/"
            ).forEach(callback)

            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun processLink(
        raw: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        var link = fixUrl(raw)

        if (
            link.contains("hgcloud", true) ||
            link.contains("hanerix", true) ||
            link.contains("vibuxer", true) ||
            link.contains("audinifer", true) ||
            link.contains("masukestin", true)
        ) {
            if (extractHg(link, referer, callback)) return true
        }

        if (link.contains("vpge.link", true)) {
            link = link.replace("vpge.link", "waaw.to")
        }

        loadExtractor(link, referer, subtitleCallback, callback)
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document
        var found = false

        // prioridad tabs latino
        doc.select("div.tabs-sidebar-block").forEach { block ->
            val iframe = block.selectFirst("iframe[src]")
            val src = iframe?.attr("src").orEmpty()

            if (src.isNotBlank()) {
                if (processLink(src, data, subtitleCallback, callback)) {
                    found = true
                }
            }
        }

        // fallback iframes normales
        if (!found) {
            doc.select("iframe[src]").forEach { frame ->
                val src = frame.attr("src")
                if (src.isNotBlank()) {
                    if (processLink(src, data, subtitleCallback, callback)) {
                        found = true
                    }
                }
            }
        }

        return found
    }
}