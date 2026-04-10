package com.animefenix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Animefenix : MainAPI() {

    override var mainUrl = "https://animefenix2.tv"
    override var name = "Animefenix"
    override var lang = "mx"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime)

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to mainUrl
    )

    // ==============================
    // 🔥 HOME
    // ==============================

    override val mainPage = mainPageOf(
        "$mainUrl/directorio/anime?idioma=2&q=&p=" to "Anime Doblado",
        "$mainUrl/directorio/anime?idioma=1&q=&p=" to "Anime Subtitulado",
        "$mainUrl/directorio/anime?tipo=2&idioma=2&q=&p=" to "Películas Doblado",
        "$mainUrl/directorio/anime?tipo=2&idioma=1&q=&p=" to "Películas Subtitulado",

        "$mainUrl/directorio/anime?estreno=2023&p=" to "Estrenos 2023",
        "$mainUrl/directorio/anime?estreno=2024&p=" to "Estrenos 2024",
        "$mainUrl/directorio/anime?estreno=2025&p=" to "Estrenos 2025"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("${request.data}$page", headers = headers).document

        val items = doc.select(".grid-animes li article")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, items)
    }

    // ==============================
    // 🔥 POSTER FIX (BIEN HECHO)
    // ==============================

    private fun Element.getImage(): String? {
        val img = selectFirst("img") ?: return null

        return img.attr("data-src")
            .ifEmpty { img.attr("src") }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val title = selectFirst("h3, p:not(.gray)")?.text() ?: return null
        val href = a.attr("href")

        val poster = getImage()

        return newTvSeriesSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    // ==============================
    // 🔥 SEARCH
    // ==============================

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/directorio/anime?q=${URLEncoder.encode(query, "UTF-8")}",
            headers = headers
        ).document

        return doc.select(".grid-animes li article")
            .mapNotNull { it.toSearchResult() }
    }

    // ==============================
    // 🔥 LOAD (DETALLE + EPISODIOS)
    // ==============================

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document

        val title = doc.selectFirst("h1")?.text() ?: "Sin título"

        val poster = doc.selectFirst("#anime_image")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }

        val plot = doc.selectFirst(".mb-6 p")?.text()

        val episodes = doc.select(".divide-y li > a").mapNotNull { ep ->
            val epTitle = ep.selectFirst(".font-semibold")?.text() ?: return@mapNotNull null

            val number = epTitle.substringAfter("Episodio ").toIntOrNull()

            newEpisode(ep.attr("href")) {
                this.name = epTitle
                this.episode = number
            }
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ==============================
    // 🔥 SERVERS
    // ==============================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data, headers = headers).document

        val script = doc.selectFirst("script:containsData(var tabsArray)") ?: return false

        val urls = script.data()
            .substringAfter("<iframe")
            .split("src='")
            .drop(1)
            .map { it.substringBefore("'").substringAfter("redirect.php?id=") }

        urls.forEach { url ->
            loadExtractor(url, mainUrl, subtitleCallback, callback)
        }

        return true
    }
}