package com.latanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Latanime : MainAPI() {
    override var mainUrl = "https://latanime.org"
    override var name = "Latanime"
    override val hasMainPage = true
    override var lang = "es-mx"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "animes?fecha=false&genero=false&letra=false&categoria=latino" to "Anime Latino",
        "animes?fecha=false&genero=false&letra=false&categoria=anime" to "Anime",
        "animes?fecha=false&genero=false&letra=false&categoria=Película%20Latino" to "Película Latino",
        "animes?fecha=false&genero=false&letra=false&categoria=Película" to "Película Subtitulado",
        "animes?fecha=false&genero=false&letra=false&categoria=ova-latino" to "OVA Latino",
        "animes?fecha=false&genero=false&letra=false&categoria=ova" to "OVA",
        "animes?fecha=false&genero=false&letra=false&categoria=especial" to "Especial"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}&p=$page").document
        val home = document.select("div.row a").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("h3").text()
        val href = this.attr("href")
        val posterUrl = fixUrlNull(this.selectFirst("img")?.getImageAttr())
        val isDub = title.contains("Latino") || title.contains("Castellano")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(isDub)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/buscar?q=$query").document
        return document.select("div.row a").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h2")?.text() ?: "Desconocido"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
        val description = document.selectFirst("h2 ~ p.my-2")?.text()
        val tags = document.select("a div.btn").map { it.text() }
        val year = document.select(".span-tiempo").text().substringAfterLast(" de ").toIntOrNull()

        val epsAnchor = document.select("div.row a[href*='/ver/']")

        return if (epsAnchor.size > 1) {

            val episodes = epsAnchor.mapIndexed { index, it ->

                val epPoster = it.select("img").attr("data-src")
                val epHref = it.attr("href")

                newEpisode(epHref) {

                    this.posterUrl = epPoster

                    // 🔥 METADATA COMPLETA (ACTIVA UI MODERNA)
                    this.name = "Episodio ${index + 1}"
                    this.episode = index + 1
                    this.season = 1
                    this.description = "Episodio ${index + 1}"

                    // 🔥 SI TU CORE YA LO SOPORTA → botón flotante
                    try {
                    } catch (_: Exception) {
                        // ignora si el core no lo tiene
                    }
                }
            }

            newAnimeLoadResponse(title, url, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, episodes)
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
            }

        } else {

            newMovieLoadResponse(title, url, TvType.AnimeMovie, epsAnchor.attr("href")) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
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

        document.select("#play-video a").forEach {
            val href = base64Decode(it.attr("data-player")).substringAfter("=")

            loadExtractor(
                href,
                data, // 🔥 FIX IMPORTANTE (referer correcto)
                subtitleCallback,
                callback
            )
        }

        return true
    }

    private fun Element.getImageAttr(): String? {
        return this.attr("data-src")
            .takeIf { it.isNotBlank() && it.startsWith("http") }
            ?: this.attr("src").takeIf { it.isNotBlank() && it.startsWith("http") }
    }
}