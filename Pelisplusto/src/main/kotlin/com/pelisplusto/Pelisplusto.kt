package com.pelisplusto

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder


class Pelisplusto : MainAPI() {

    override var mainUrl = "https://tioplus.app"
    override var name = "Pelisplus"
    override var lang = "es"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to mainUrl,
        "Origin" to mainUrl
    )

    // 🔥 CATEGORÍAS PRO
    override val mainPage = mainPageOf(
        "$mainUrl/peliculas/page/" to "Películas",
        "$mainUrl/series/page/" to "Series",
        "$mainUrl/animes/page/" to "Animes",
        "$mainUrl/doramas/page/" to "Doramas"
    )

    // 🔥 FIX IMAGEN (CLAVE)
    private fun Element.getImage(): String? {
        val img = selectFirst("img") ?: return null

        return img.attr("data-src")
            .ifEmpty { img.attr("data-lazy-src") }
            .ifEmpty { img.attr("src") }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href")
        val title = selectFirst("span, h2, h3")?.text() ?: return null
        val poster = getImage()

        return if (href.contains("/pelicula/")) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("${request.data}$page", headers = headers).document

        val items = doc.select("article.item a.itemA")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/search/${URLEncoder.encode(query, "UTF-8")}",
            headers = headers
        ).document

        return doc.select("article.item a.itemA")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document

        val title = doc.selectFirst("h1")?.text() ?: "Sin título"
        val plot = doc.selectFirst("p")?.text()

        // 🔥 BACKDROP PRO (como cuevana)
        val backdropStyle = doc.selectFirst(".bg")?.attr("style")
        val backdrop = Regex("""url\("(.*?)"\)""")
            .find(backdropStyle ?: "")?.groupValues?.get(1)

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")

        val episodes = doc.select("ul li a").mapNotNull { ep ->
            val href = ep.attr("href")

            if (href.contains("/episode/")) {
                newEpisode(href) {
                    this.name = ep.text()
                }
            } else null
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
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



        val servers = doc.select("ul.bg-tabs li, .bg-tabs li")

        var found = false

        servers.forEach { li ->
            val base64 = li.attr("data-server")
            if (base64.isBlank()) return@forEach

            val resolved = PelisplusResolver.resolve(base64, data)

            if (!resolved.isNullOrEmpty()) {
                if (loadExtractor(resolved, data, subtitleCallback, callback)) {
                    found = true
                }
            }
        }

        return found
    }
}