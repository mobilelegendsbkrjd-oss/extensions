package com.modocine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONObject

class ModoCine : MainAPI() {
    override var mainUrl = "https://modocine.com"
    override var name = "ModoCine"
    override val hasMainPage = true
    override var lang = "es-mx"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val playerUrl = "https://play.modocine.com"
    private val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"

    // =========================
    // MAIN PAGE - MÚLTIPLES CATEGORÍAS
    // =========================
    override val mainPage = mainPageOf(
        "explore/?sort=popular&type=movie" to "Películas Populares",
        "explore/?sort=now-playing&type=movie" to "Películas Estreno",
        "explore/?sort=trending&time=day&type=movie" to "Trending Películas",
        "explore/?sort=popular&type=tv" to "Series Populares",
        "explore/?sort=top-rated&type=tv" to "Series Top",
        "explore/?sort=trending&time=day&type=tv" to "Trending Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val params = mapOf(
            "action" to "load_explore_data",
            "page" to page.toString(),
            "type" to if (request.data.contains("tv")) "tv" else "movie",
            "sort" to request.data.substringAfter("sort=").substringBefore("&").ifBlank { "popular" },
            "genre" to "",
            "network" to "",
            "language" to "",
            "q" to ""
        )

        val response = app.post(ajaxUrl, data = params)
        val json = response.parsedSafe<LoadExploreResponse>()

        val items = if (json?.success == true && !json.data.html.isNullOrBlank()) {
            val ajaxDoc = Jsoup.parse(json.data.html)
            parseCards(ajaxDoc)
        } else {
            // Fallback estático
            val staticUrl = "$mainUrl/${request.data}&page=$page"
            val doc = app.get(staticUrl).document
            parseCards(doc)
        }

        return newHomePageResponse(HomePageList(request.name, items), hasNext = true)
    }

    // =========================
    // PARSER DE CARDS (FIX TÍTULOS)
    // =========================
    private fun parseCards(doc: Element): List<SearchResponse> {
        return doc.select("a[href*='/watch/?type=']")
            .mapNotNull { card ->
                val link = fixUrl(card.attr("href"))
                if (link.isBlank() || !link.contains("/watch/?")) return@mapNotNull null

                // Título exacto según tu HTML
                val title = card.selectFirst("h3.text-white.font-medium.text-sm.md\\:text-base.truncate")
                    ?.text()?.trim()
                    ?: card.selectFirst("h3, h2")?.text()?.trim()
                    ?: "Sin título"

                val poster = card.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

                val isTv = link.contains("type=tv")

                if (isTv) {
                    newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                } else {
                    newMovieSearchResponse(title, link, TvType.Movie) {
                        this.posterUrl = poster
                    }
                }
            }
    }

    // =========================
    // SEARCH
    // =========================
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val doc = app.get("$mainUrl/?s=$query").document
        results.addAll(parseCards(doc))
        return results.distinctBy { it.url }
    }

    // =========================
    // LOAD (FIX SERIES - más episodios)
    // =========================
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBefore("|")?.trim()
            ?: doc.selectFirst("h1, h2")?.text()?.trim()
            ?: "Sin título"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[name=description]")?.attr("content") ?: ""

        val isSeries = url.contains("type=tv")

        return if (!isSeries) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            val episodes = mutableListOf<Episode>()

            // Selector mejorado para episodios
            doc.select("a[href*='season='][href*='episode=']").forEach { ep ->
                val epUrl = fixUrl(ep.attr("href"))
                val season = Regex("season=(\\d+)").find(epUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episode = Regex("episode=(\\d+)").find(epUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                val epTitle = ep.selectFirst("span, p, .ep-title, .title")?.text()?.trim()
                    ?: "T$season E$episode"

                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epTitle
                        this.season = season
                        this.episode = episode
                    }
                )
            }

            // Fallback fuerte (30 episodios)
            if (episodes.isEmpty()) {
                for (ep in 1..30) {
                    episodes.add(
                        newEpisode("$url&season=1&episode=$ep") {
                            this.name = "Episodio $ep"
                            this.season = 1
                            this.episode = ep
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // LoadLinks (mantengo el tuyo)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val tmdbId =
            Regex("id=(\\d+)").find(data)
                ?.groupValues?.get(1)
                ?: return false

        val isTv = data.contains("type=tv")

        val season = Regex("season=(\\d+)")
            .find(data)?.groupValues?.get(1) ?: "1"

        val episode = Regex("episode=(\\d+)")
            .find(data)?.groupValues?.get(1) ?: "1"

        val apiUrl =
            if (isTv)
                "$playerUrl/play.php/embed/tv/$tmdbId/$season/$episode?api=1"
            else
                "$playerUrl/play.php/embed/movie/$tmdbId?api=1"

        val apiText = app.get(
            apiUrl,
            headers = mapOf("Referer" to playerUrl)
        ).text

        val json = JSONObject(apiText)

        if (!json.optBoolean("success")) return false

        val arr = json.getJSONArray("data")

        var found = false

        for (i in 0 until arr.length()) {

            val item = arr.getJSONObject(i)

            val lang = item.optString("language")
            val embedUrl = item.optString("embed_url")

            if (embedUrl.isBlank()) continue

            val page = app.get(
                embedUrl,
                headers = mapOf(
                    "Referer" to playerUrl,
                    "User-Agent" to USER_AGENT
                )
            ).text

            val links = mutableSetOf<String>()

            Regex("""https?:\/\/play\.modocine\.com\/hls\/[^"'\\ ]+\.m3u8[^"'\\ ]*""")
                .findAll(page)
                .forEach { links.add(it.value) }

            Regex("""https?:\/\/[^"'\\ ]+\.m3u8[^"'\\ ]*""")
                .findAll(page)
                .forEach { links.add(it.value) }

            Regex(""""file"\s*:\s*"([^"]+\.m3u8[^"]*)"""")
                .findAll(page)
                .forEach { links.add(it.groupValues[1]) }

            Regex(""""source"\s*:\s*"([^"]+\.m3u8[^"]*)"""")
                .findAll(page)
                .forEach { links.add(it.groupValues[1]) }

            for (link in links) {

                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name $lang",
                        url = link,
                        referer = playerUrl,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8
                    )
                )

                found = true
            }

            if (!found) {
                try {
                    loadExtractor(
                        embedUrl,
                        playerUrl,
                        subtitleCallback,
                        callback
                    )
                    found = true
                } catch (_: Exception) {
                }
            }
        }

        return found
    }

    data class LoadExploreResponse(
        val success: Boolean,
        val data: Data
    )

    data class Data(
        val html: String?
    )
}