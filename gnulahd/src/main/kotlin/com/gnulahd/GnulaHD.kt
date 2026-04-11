package com.gnulahd

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class GnulaHD : MainAPI() {

    override var mainUrl = "https://ww3.gnulahd.nu"
    override var name = "GnulaHD"
    override var lang = "es"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/122.0.0.0 Safari/537.36",
        "Referer" to mainUrl
    )

    override val mainPage = mainPageOf(
        "$mainUrl/ver/?type=Pelicula&order=latest" to "Películas",
        "$mainUrl/ver/?type=Serie&order=latest" to "Series",
        "$mainUrl/ver/?type=Anime&order=latest" to "Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("${request.data}&page=$page", headers = headers).document

        val items = doc.select(".items .bsx, .bsx, article")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.encodeUrl()}", headers = headers).document

        return doc.select(".items .bsx, .bsx, article")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = headers).document

        val title = doc.selectFirst("h1")?.text() ?: return null
        val poster = doc.selectFirst("img")?.attr("src")
        val description = doc.selectFirst(".sinopsis, .infox p")?.text()

        val episodes = doc.select(".eplister ul li, .episodelist li").mapIndexed { index, ep ->
            val epUrl = ep.selectFirst("a")?.attr("href") ?: return@mapIndexed null

            newEpisode(epUrl) {
                this.episode = index + 1
            }
        }.filterNotNull()

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data, headers = headers).document
        val html = doc.html()
        val mapper = jacksonObjectMapper()

        try {
            val regex = Regex("""_gnpv_ep_langs\s*=\s*(\[[\s\S]*?]);""")
            val match = regex.find(html)

            if (match != null) {
                val json = match.groupValues[1]
                val langs = mapper.readTree(json)

                langs.forEach { lang ->
                    val servers = lang.get("servers")

                    servers?.forEach { srv ->
                        val link = srv.get("src")?.asText() ?: return@forEach

                        if (!link.contains("youtube") && !link.contains("youtu.be")) {
                            loadExtractor(link, data, subtitleCallback, callback)
                        }
                    }
                }

                return true
            }

        } catch (_: Exception) {}

        // fallback
        doc.select("iframe[src]").forEach {
            val link = it.attr("src")
            if (!link.contains("youtube")) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".tt, h2, h3")?.text() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null
        val poster = selectFirst("img")?.attr("src")

        val fixedUrl = fixUrl(href)

        return newMovieSearchResponse(title, fixedUrl) {
            this.posterUrl = poster
        }
    }

    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
