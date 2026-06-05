package com.latanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import android.util.Log


class Latanime : MainAPI() {

    override var mainUrl = "https://latanime.org"
    override var name = "Latanime"
    override val hasMainPage = true
    override var lang = "es"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    override val instantLinkLoading = true
    override val mainPage = mainPageOf(
        "animes?fecha=false&genero=false&letra=false&categoria=latino" to "Anime Latino",
        "animes?fecha=false&genero=false&letra=false&categoria=anime" to "Anime",
        "animes?fecha=false&genero=false&letra=false&categoria=Película%20Latino" to "Película Latino",
        "animes?fecha=false&genero=false&letra=false&categoria=Película" to "Película Subtitulado",
        "animes?fecha=false&genero=false&letra=false&categoria=ova-latino" to "OVA Latino",
        "animes?fecha=false&genero=false&letra=false&categoria=ova" to "OVA",
        "animes?fecha=false&genero=false&letra=false&categoria=especial" to "Especial"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = app.get(
            "$mainUrl/${request.data}&p=$page"
        ).document

        val items = document.select("div.row a")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(
                request.name,
                items
            ),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/buscar?q=${query.trim()}"
        ).document

        return document.select("div.row a")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h2")?.text()?.trim()
            ?: "Sin título"

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")
                ?.attr("content")
        )

        val plot = document.selectFirst("h2 ~ p.my-2")
            ?.text()
            ?.trim()

        val tags = document.select("a div.btn")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val year = Regex("""(19|20)\d{2}""")
            .find(document.text())
            ?.value
            ?.toIntOrNull()

        val episodesRaw = document.select("div.row a[href*='/ver/']")
        val isMovie = episodesRaw.size <= 1 ||
                title.contains("pelicula", true) ||
                title.contains("movie", true)

        val background = poster

        val recommendations = document.select("div.row a")
            .mapNotNull { it.toSearchResult() }
            .filter { it.url != url }
            .take(12)

        return if (!isMovie) {

            val episodes = episodesRaw.mapIndexed { index, element ->
                val epUrl = fixUrl(element.attr("href"))

                newEpisode(epUrl) {
                    this.name = "Episodio ${index + 1}"
                    this.episode = index + 1
                    this.season = 1
                    this.posterUrl = fixUrlNull(
                        element.selectFirst("img")?.getImageAttr()
                    )
                }
            }

            newAnimeLoadResponse(
                title,
                url,
                TvType.Anime
            ) {
                posterUrl = poster
                backgroundPosterUrl = background
                this.plot = plot
                this.tags = tags
                this.year = year
                this.recommendations = recommendations

                if (title.contains("latino", true) ||
                    title.contains("castellano", true)
                ) {
                    addEpisodes(DubStatus.Dubbed, episodes)
                } else {
                    addEpisodes(DubStatus.Subbed, episodes)
                }
            }

        } else {

            val movieUrl = episodesRaw.firstOrNull()
                ?.attr("href")
                ?.let { fixUrl(it) }
                ?: url

            newMovieLoadResponse(
                title,
                url,
                TvType.AnimeMovie,
                movieUrl
            ) {
                posterUrl = poster
                backgroundPosterUrl = background
                this.plot = plot
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean = coroutineScope {

    val document = app.get(data).document

    val servers = document.select(
        "#play-video a, ul.cap_repro li a"
    ).distinctBy {
        it.attr("data-player")
    }

    val downloadLinks = document.select(
        "div.descarga2 div a[href]"
    )

    if (servers.isEmpty() && downloadLinks.isEmpty()) {
        return@coroutineScope false
    }

    servers.map { element ->
        async {

            val rawLabel = element.text().trim()

            val label = when {
                rawLabel.contains("lat", true) -> "LAT"
                rawLabel.contains("cas", true) -> "CAS"
                rawLabel.contains("sub", true) -> "SUB"
                else -> "SERVER"
            }

            val raw = element.attr("data-player").trim()

            if (raw.isBlank()) return@async

            try {

                val iframeUrl = runCatching {
                    app.get(
                        "$mainUrl/reproductor?url=$raw"
                    ).document
                        .selectFirst("iframe, embed")
                        ?.attr("src")
                }.getOrNull()

                val resolvedUrl = when {

                    !iframeUrl.isNullOrBlank() -> {
                        fixUrl(iframeUrl)
                    }

                    else -> {
                        try {
                            fixUrl(base64Decode(raw))
                        } catch (_: Exception) {
                            raw
                        }
                    }
                }

                if (!resolvedUrl.startsWith("http")) {
                    return@async
                }

                if (resolvedUrl.contains("pixeldrain.com")) {

                    val id = resolvedUrl
                        .substringAfterLast("/")
                        .substringBefore("?")

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name ($label)",
                            url = "https://pixeldrain.com/api/file/$id?download",
                            type = INFER_TYPE
                        ) {
                            referer = data
                        }
                    )

                    return@async
                }

                loadExtractor(
                    resolvedUrl,
                    data,
                    subtitleCallback,
                    callback
                )

            } catch (e: Exception) {
                Log.e("LATANIME", "Server error", e)
            }
        }
    }.awaitAll()

    downloadLinks.map { element ->
        async {

            try {

                val href = fixUrl(
                    element.attr("href")
                )

                if (href.isBlank()) {
                    return@async
                }

                if (href.contains("pixeldrain.com")) {

                    val id = href
                        .substringAfterLast("/")
                        .substringBefore("?")

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "Pixeldrain Download",
                            url = "https://pixeldrain.com/api/file/$id?download",
                            type = INFER_TYPE
                        ) {
                            referer = data
                        }
                    )

                    return@async
                }

                loadExtractor(
                    href,
                    data,
                    subtitleCallback,
                    callback
                )

            } catch (e: Exception) {
                Log.e("LATANIME", "Download error", e)
            }
        }
    }.awaitAll()

    true
}

    private fun Element.toSearchResult(): SearchResponse? {

        val title = select("h3").text().trim()
        val href = attr("href").trim()

        if (title.isBlank() || href.isBlank()) return null

        val poster = fixUrlNull(
            selectFirst("img")?.getImageAttr()
        )

        val isMovie = title.contains("pelicula", true)

        val type = if (isMovie) {
            TvType.AnimeMovie
        } else {
            TvType.Anime
        }

        val isDub = title.contains("latino", true) ||
                title.contains("castellano", true)

        return newAnimeSearchResponse(
            title,
            fixUrl(href),
            type
        ) {
            posterUrl = poster
            addDubStatus(isDub)
        }
    }

    private fun Element.getImageAttr(): String? {

        val dataSrc = attr("data-src").trim()
        if (dataSrc.startsWith("http")) return dataSrc

        val src = attr("src").trim()
        if (src.startsWith("http")) return src

        val srcset = attr("srcset").trim()
        if (srcset.isNotBlank()) {
            return srcset.substringBefore(" ").trim()
        }

        return null
    }
}