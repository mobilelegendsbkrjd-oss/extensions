package com.estrenosanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class EstrenosAnime : MainAPI() {

    override var mainUrl = "https://estrenosanime.net"
    override var name = "EstrenosAnime"
    override var lang = "es"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    // ==================================================
    // HELPERS
    // ==================================================
    private fun imgAttr(el: Element?): String? {
        if (el == null) return null

        return el.attr("data-src")
            .ifBlank { el.attr("data-original") }
            .ifBlank { el.attr("data-lazy-src") }
            .ifBlank { el.attr("src") }
            .takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }
    }

    private fun detectLang(txt: String): String {
        val t = txt.lowercase()

        return when {
            t.contains("lat") -> "LAT"
            t.contains("latino") -> "LAT"
            t.contains("cast") -> "CAS"
            t.contains("español") -> "CAS"
            t.contains("espanol") -> "CAS"
            t.contains("dual") -> "DUAL"
            else -> "SUB"
        }
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        return doc.select(
            ".tick.ltr, .flw-item, .swiper-slide .flw-item"
        ).mapNotNull { card ->

            val a = card.selectFirst("a[href]") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href"))

            if (
                !href.contains("/anime/") &&
                !href.contains("/pelicula/")
            ) return@mapNotNull null

            val title =
                a.attr("title")
                    .ifBlank {
                        card.selectFirst("h3,.film-name")
                            ?.text()
                            ?: ""
                    }.trim()

            if (title.isBlank()) return@mapNotNull null

            val poster = imgAttr(card.selectFirst("img"))

            val type =
                if (
                    href.contains("pelicula") ||
                    card.text().contains("pelicula", true)
                ) TvType.AnimeMovie
                else TvType.Anime

            newAnimeSearchResponse(
                title,
                href,
                type
            ) {
                this.posterUrl = poster
            }

        }.distinctBy { it.url }
    }

    // ==================================================
    // HOME
    // ==================================================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val lists = mutableListOf<HomePageList>()

        val sections = listOf(
            "Últimos animes" to "$mainUrl/ultimo-anime?page=$page",
            "Últimos episodios" to "$mainUrl/ultimo-episodios?page=$page",
            "En emisión" to "$mainUrl/estado/En+Emision?page=$page",
            "Películas" to "$mainUrl/tipo/Pelicula?page=$page",
            "Populares" to "$mainUrl/popular?page=$page"
        )

        sections.forEach { (title, url) ->
            try {
                val doc = app.get(
                    url,
                    referer = mainUrl
                ).document

                val items = parseCards(doc)

                if (items.isNotEmpty()) {
                    lists.add(
                        HomePageList(title, items)
                    )
                }

            } catch (_: Exception) {
            }
        }

        return newHomePageResponse(lists)
    }

    // ==================================================
    // SEARCH
    // ==================================================
    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        return try {

            val doc = app.get(
                "$mainUrl/search?keyword=${query.replace(" ", "+")}",
                referer = mainUrl
            ).document

            parseCards(doc)

        } catch (_: Exception) {
            emptyList()
        }
    }

    // ==================================================
    // LOAD
    // ==================================================
    override suspend fun load(
        url: String
    ): LoadResponse {

        val doc = app.get(
            url,
            referer = mainUrl
        ).document

        val html = doc.html()

        val title =
            doc.selectFirst(
                "h1,h2,.film-name,title"
            )?.text()
                ?.substringBefore("-")
                ?.trim()
                ?: "Sin título"

        val poster =
            imgAttr(
                doc.selectFirst("img")
            )

        val plot =
            doc.selectFirst(
                ".film-description,.description,.text"
            )?.text()?.trim() ?: ""

        val episodes = mutableListOf<Episode>()

        // ==========================================
        // BALANDRO PORT LITERAL
        // ==========================================
        val animeId =
            Regex("""data-anime-id="(\d+)"""")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)

        if (animeId != null) {

            try {

                val epRes = app.get(
                    "$mainUrl/ajax/v2/episode/list/$animeId?order=asc",
                    referer = url,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                ).text
                    .replace("\\/", "/")
                    .replace("\\\"", "\"")

                Regex(
                    """data-number="(\d+)".*?href="([^"]+)"""",
                    RegexOption.DOT_MATCHES_ALL
                ).findAll(epRes).forEach { m ->

                    val num =
                        m.groupValues[1]
                            .toIntOrNull()
                            ?: 1

                    val epUrl =
                        fixUrl(
                            m.groupValues[2]
                        )

                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = "Episodio $num"
                            this.episode = num
                        }
                    )
                }

            } catch (_: Exception) {
            }
        }

        // fallback html
        if (episodes.isEmpty()) {

            doc.select("a[href*='/ver/']")
                .forEachIndexed { i, ep ->

                    val epUrl =
                        fixUrl(ep.attr("href"))

                    episodes.add(
                        newEpisode(epUrl) {
                            this.name =
                                "Episodio ${i + 1}"
                            this.episode =
                                i + 1
                        }
                    )
                }
        }

        val cleanEpisodes =
            episodes
                .distinctBy { it.data }
                .sortedBy { it.episode }

        val isMovie =
            cleanEpisodes.size <= 1 ||
                    url.contains("pelicula", true)

        return if (isMovie) {

            newMovieLoadResponse(
                title,
                url,
                TvType.AnimeMovie,
                url
            ) {
                this.posterUrl = poster
                this.plot = plot
            }

        } else {

            newAnimeLoadResponse(
                title,
                url,
                TvType.Anime
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.episodes =
                    mutableMapOf(
                        DubStatus.Subbed to cleanEpisodes
                    )
            }
        }
    }

    // ==================================================
    // LOAD LINKS
    // ==================================================

    // REEMPLAZA SOLO TU MÉTODO loadLinks() POR ESTE COMPLETO
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val episodeUrl = data
        val extracted = mutableSetOf<String>()
        var found = false

        try {
            val pageHtml = app.get(
                episodeUrl,
                referer = mainUrl
            ).text

            val episodeId =
                Regex("""data-episode-id="(\d+)"""")
                    .find(pageHtml)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: Regex("""ep=(\d+)""")
                        .find(episodeUrl)
                        ?.groupValues
                        ?.getOrNull(1)
                    ?: return false

            val ajaxHeaders = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to episodeUrl
            )

            // ======================================
            // SERVERS
            // ======================================
            val serversRes = app.get(
                "$mainUrl/ajax/v2/episode/servers?episodeId=$episodeId",
                headers = ajaxHeaders
            ).text
                .replace("\\/", "/")
                .replace("\\\"", "\"")

            val serverIds = Regex("""data-id="(\d+)"""")
                .findAll(serversRes)
                .map { it.groupValues[1] }
                .distinct()
                .toList()

            for (sid in serverIds) {

                try {
                    val sourceRes = app.get(
                        "$mainUrl/ajax/v2/episode/sources?id=$sid",
                        headers = ajaxHeaders
                    ).text
                        .replace("\\/", "/")
                        .replace("\\\"", "\"")

                    val multiUrl =
                        Regex(
                            """"link"\s*:\s*"([^"]+)""""
                        ).find(sourceRes)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?: Regex("""https?://[^\s"'<>]+""")
                                .find(sourceRes)
                                ?.value
                            ?: continue

                    if (!extracted.add(multiUrl))
                        continue

                    val multiHtml = app.get(
                        multiUrl,
                        referer = episodeUrl
                    ).text

                    val tokens = Regex(
                        """go_to_player\(['"]([^'"]+)"""
                    ).findAll(multiHtml)
                        .map { it.groupValues[1] }
                        .distinct()
                        .toList()

                    // fallback directo
                    if (tokens.isEmpty()) {
                        loadExtractor(
                            multiUrl,
                            episodeUrl,
                            subtitleCallback,
                            callback
                        )
                        found = true
                    }

                    for (token in tokens) {

                        try {

                            val jsonBody =
                                """{"encrypted":"$token"}"""
                                    .toRequestBody(
                                        "application/json".toMediaTypeOrNull()
                                    )

                            val dec = app.post(
                                "https://multiserver.icu/embed/api/decrypt-stream",
                                requestBody = jsonBody,
                                headers = mapOf(
                                    "Content-Type" to "application/json",
                                    "Referer" to multiUrl
                                )
                            ).text
                                .replace("\\/", "/")
                                .replace("\\\"", "\"")

                            val realUrl =
                                Regex(
                                    """"url"\s*:\s*"([^"]+)""""
                                ).find(dec)
                                    ?.groupValues
                                    ?.getOrNull(1)
                                    ?: Regex("""https?://[^\s"'<>]+""")
                                        .find(dec)
                                        ?.value
                                    ?: continue

                            if (!extracted.add(realUrl))
                                continue

                            // ==================================
                            // PRIORIDAD BIGWARP
                            // ==================================
                            if (
                                realUrl.contains("bigwarp", true) ||
                                realUrl.contains("bgwp.cc", true)
                            ) {
                                BigwarpIO().getUrl(
                                    realUrl,
                                    episodeUrl,
                                    subtitleCallback,
                                    callback
                                )
                                found = true
                                continue
                            }

                            // ==================================
                            // NORMAL EXTRACTOR
                            // ==================================
                            loadExtractor(
                                realUrl,
                                episodeUrl,
                                subtitleCallback,
                                callback
                            )

                            found = true

                        } catch (_: Exception) {
                        }
                    }

                } catch (_: Exception) {
                }
            }

        } catch (_: Exception) {
        }

        return found
    }
}