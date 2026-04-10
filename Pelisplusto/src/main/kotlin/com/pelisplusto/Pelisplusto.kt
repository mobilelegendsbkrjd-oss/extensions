package com.pelisplusto

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Pelisplusto : MainAPI() {

    override var mainUrl = "https://pelisplus.to"
    override var name = "Pelisplus.to"
    override var lang = "es"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to mainUrl
    )

    // =============================
    // 🔥 HOME
    // =============================

    override val mainPage = mainPageOf(
        "$mainUrl/peliculas/page/" to "Películas",
        "$mainUrl/series/page/" to "Series",
        "$mainUrl/animes/page/" to "Animes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("${request.data}$page", headers = headers).document

        val items = doc.select("article.item.liste.relative a.itemA")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, items)
    }

    // =============================
    // 🔥 PARSER LIMPIO
    // =============================

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href")
        val title = selectFirst("h2")?.text()?.substringBefore(" (") ?: return null
        val poster = selectFirst("img")?.attr("data-src")

        return when {
            href.contains("/pelicula/") -> newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }

            href.contains("/serie/") -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }

            href.contains("/anime/") -> newTvSeriesSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }

            else -> null
        }
    }

    // =============================
    // 🔥 SEARCH
    // =============================

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/${URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(url, headers = headers).document

        return doc.select("article.item.liste.relative a.itemA")
            .mapNotNull { it.toSearchResult() }
    }

    // =============================
    // 🔥 LOAD
    // =============================

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document

        val title = doc.selectFirst("h1.slugh1")?.text()?.substringBefore(" (") ?: "Sin título"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = doc.selectFirst("div.description p")?.text()

        val episodes = doc.select(".divide-y li a").mapNotNull { ep ->
            val epTitle = ep.text()
            val number = Regex("""\d+""").find(epTitle)?.value?.toIntOrNull()

            newEpisode(ep.attr("href")) {
                this.name = epTitle
                this.episode = number
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // =============================
    // 🔥 SERVERS (LO IMPORTANTE)
    // =============================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data, headers = headers).document

        val servers = doc.select(".bg-tabs ul li")

        servers.forEach { li ->
            try {
                val base64 = li.attr("data-server")
                if (base64.isBlank()) return@forEach

                val decoded = String(Base64.decode(base64, Base64.DEFAULT))

                if (decoded.contains("http")) {
                    loadExtractor(decoded, mainUrl, subtitleCallback, callback)
                } else {
                    // 🔥 fallback player
                    val playerUrl = "$mainUrl/player/${Base64.encodeToString(base64.toByteArray(), Base64.DEFAULT).trim()}"
                    val playerDoc = app.get(playerUrl, headers = headers).document

                    val video = playerDoc.selectFirst("script")
                        ?.data()
                        ?.let { Regex("""https?://[^\s'"]+""").find(it)?.value }

                    if (!video.isNullOrEmpty()) {
                        loadExtractor(video, mainUrl, subtitleCallback, callback)
                    }
                }

            } catch (_: Throwable) {}
        }

        return true
    }
}