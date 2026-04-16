package com.latanime

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Latanime : MainAPI() {

    override var mainUrl = "https://latanime.org"
    override var name = "Latanime"
    override val hasMainPage = true
    override var lang = "es-mx"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val instantLinkLoading = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

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
                items,
                false
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
            ?: "Desconocido"

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")
                ?.attr("content")
        )

        val description = document.selectFirst("h2 ~ p.my-2")
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

        val episodeAnchors = document.select("div.row a[href*='/ver/']")

        val isMovie = episodeAnchors.size <= 1 ||
                title.contains("pelicula", true) ||
                title.contains("movie", true)

        val recommendations = document.select("div.row a")
            .mapNotNull { it.toSearchResult() }
            .filter { it.url != url }
            .take(12)

        return if (!isMovie) {

            val episodes = episodeAnchors.mapIndexed { index, element ->

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
                backgroundPosterUrl = poster
                plot = description
                this.tags = tags
                this.year = year
                this.recommendations = recommendations

                val malId = getMalIdFromTitle(title)
                if (malId != null) {
                    addMalId(malId)
                }

                if (
                    title.contains("latino", true) ||
                    title.contains("castellano", true)
                ) {
                    addEpisodes(DubStatus.Dubbed, episodes)
                } else {
                    addEpisodes(DubStatus.Subbed, episodes)
                }
            }

        } else {

            val movieUrl = episodeAnchors.firstOrNull()
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
                backgroundPosterUrl = poster
                plot = description
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
    ): Boolean {

        val document = app.get(data).document
        var found = false

        val servers = document.select("#play-video a")
            .distinctBy { it.attr("data-player") }

        for (element in servers) {

            val rawLabel = element.text().trim()

            val label = when {
                rawLabel.contains("lat", true) -> "LAT"
                rawLabel.contains("cas", true) -> "CAS"
                rawLabel.contains("sub", true) -> "SUB"
                else -> "SERVER"
            }

            val raw = element.attr("data-player").trim()

            val decoded = try {
                base64Decode(raw)
            } catch (_: Exception) {
                raw
            }

            val link = decoded
                .substringAfter("=")
                .trim()
                .ifBlank { decoded }

            if (!link.startsWith("http")) continue

            found = true

            try {
                loadExtractor(
                    link,
                    data,
                    subtitleCallback,
                    callback
                )
            } catch (_: Exception) {

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name ($label)",
                        url = link,
                        type = INFER_TYPE
                    ) {
                        this.referer = data
                    }
                )
            }
        }

        return found
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

    private suspend fun getMalIdFromTitle(title: String): Int? {
        return try {

            val clean = title
                .substringBefore(" T")
                .substringBefore(" Temporada")
                .substringBefore(" Season")
                .substringBefore(":")
                .replace("Latino", "", true)
                .replace("Castellano", "", true)
                .replace("Sub Español", "", true)
                .replace("Subtitulado", "", true)
                .replace("(Latino)", "", true)
                .replace("(Castellano)", "", true)
                .replace(Regex("""\bS\d+\b""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\bT\d+\b""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\b\d{3,4}p\b""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""[^\w\s:.-]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()

            val query = URLEncoder.encode(clean, "UTF-8")

            val res = app.get(
                "https://api.jikan.moe/v4/anime?q=$query&limit=5"
            ).parsedSafe<JikanResponse>()

            val items = res?.data ?: return null

            val exact = items.firstOrNull {
                val t = it.title.orEmpty()
                t.equals(clean, true) ||
                        t.contains(clean, true) ||
                        clean.contains(t, true)
            }

            (exact ?: items.firstOrNull())?.malId

        } catch (_: Exception) {
            null
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

    data class JikanResponse(
        @JsonProperty("data")
        val data: List<JikanAnime>? = null
    )

    data class JikanAnime(
        @JsonProperty("mal_id")
        val malId: Int? = null,

        @JsonProperty("title")
        val title: String? = null
    )
}