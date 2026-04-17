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
    override var lang = "mx"
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
            ".flw-item, .swiper-slide .flw-item, .film_list-wrap .flw-item"
        ).mapNotNull { card ->

            val a = card.selectFirst("a[href]") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href"))

            if (
                !href.contains("/anime/") &&
                !href.contains("/pelicula/")
            ) return@mapNotNull null

            val title = a.attr("title")
                .ifBlank {
                    card.selectFirst("h3,.film-name")
                        ?.text()
                        ?: ""
                }
                .trim()

            if (title.isBlank()) return@mapNotNull null

            val poster = imgAttr(card.selectFirst("img"))

            val fullText = (
                    title + " " +
                            card.text()
                    ).lowercase()

            val type =
                if (
                    href.contains("pelicula", true) ||
                    fullText.contains("pelicula")
                ) TvType.AnimeMovie
                else TvType.Anime

            newAnimeSearchResponse(
                title,
                href,
                type
            ) {
                this.posterUrl = poster

                // =====================================
                // ETIQUETAS PROFESIONALES
                // =====================================
                when {
                    fullText.contains("dual") -> {
                        addQuality("DUAL")
                    }

                    fullText.contains("latino") ||
                            fullText.contains(" lat ") ||
                            fullText.contains("lat)") ||
                            fullText.contains("lat]") -> {
                        addQuality("LAT")
                    }

                    fullText.contains("castellano") ||
                            fullText.contains("cast") -> {
                        addQuality("CAS")
                    }

                    else -> {
                        addQuality("SUB")
                    }
                }
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
                doc.selectFirst(
                    ".film-poster img, .anime-poster img, .poster img, .thumb img, .item img, .film-thumb img"
                )
            ) ?: imgAttr(
                doc.select("img").firstOrNull {
                    val s = it.outerHtml().lowercase()
                    !s.contains("logo") &&
                            !s.contains("header") &&
                            !s.contains("icon")
                }
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        var found = false
        val used = mutableSetOf<String>()

        suspend fun addUrl(
            url: String,
            referer: String?
        ) {
            val final = url.trim()
            if (final.isBlank()) return
            if (!used.add(final)) return

            try {
                val ok = UniversalExtractor.resolve(
                    final,
                    referer,
                    subtitleCallback,
                    callback
                )

                if (ok) {
                    found = true
                    return
                }
            } catch (_: Exception) {
            }

            try {
                loadExtractor(
                    final,
                    referer,
                    subtitleCallback
                ) {
                    found = true
                    callback(it)
                }
            } catch (_: Exception) {
            }
        }

        suspend fun processEpisode(
            watchUrl: String,
            episodeId: String
        ) {
            try {
                val headers = mapOf(
                    "Referer" to watchUrl,
                    "X-Requested-With" to "XMLHttpRequest"
                )

                val servers = app.get(
                    "$mainUrl/ajax/v2/episode/servers?episodeId=$episodeId",
                    headers = headers
                ).text
                    .replace("\\/", "/")
                    .replace("\\\"", "\"")

                val serverIds = Regex("""data-id=["'](\d+)""")
                    .findAll(servers)
                    .map { it.groupValues[1] }
                    .distinct()
                    .toList()

                for (sid in serverIds) {

                    try {
                        val src = app.get(
                            "$mainUrl/ajax/v2/episode/sources?id=$sid",
                            headers = headers
                        ).text
                            .replace("\\/", "/")
                            .replace("\\\"", "\"")

                        val newUrl =
                            Regex(""""link"\s*:\s*"([^"]+)"""")
                                .find(src)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?: continue

                        // estilo balandro:
                        // entra al link intermedio y saca tokens go_to_player(...)
                        try {
                            val html = app.get(
                                newUrl,
                                referer = watchUrl
                            ).text

                            val encrypted = Regex(
                                """go_to_player\(['"]([^'"]+)"""
                            ).findAll(html)
                                .map { it.groupValues[1] }
                                .distinct()
                                .toList()

                            if (encrypted.isNotEmpty()) {

                                for (token in encrypted) {
                                    try {
                                        val body =
                                            """{"encrypted":"$token"}"""
                                                .toRequestBody(
                                                    "application/json"
                                                        .toMediaTypeOrNull()
                                                )

                                        val dec = app.post(
                                            "https://multiserver.icu/embed/api/decrypt-stream",
                                            requestBody = body,
                                            headers = mapOf(
                                                "Content-Type" to "application/json",
                                                "Referer" to newUrl
                                            )
                                        ).text
                                            .replace("\\/", "/")
                                            .replace("\\\"", "\"")

                                        val real =
                                            Regex(""""url"\s*:\s*"([^"]+)"""")
                                                .find(dec)
                                                ?.groupValues
                                                ?.getOrNull(1)

                                        if (!real.isNullOrBlank()) {
                                            addUrl(real, newUrl)
                                        }
                                    } catch (_: Exception) {
                                    }
                                }

                            } else {
                                addUrl(newUrl, watchUrl)
                            }

                        } catch (_: Exception) {
                            addUrl(newUrl, watchUrl)
                        }

                    } catch (_: Exception) {
                    }
                }

            } catch (_: Exception) {
            }
        }

        try {

            // ==================================================
            // CASO 1: YA ES PAGINA /ver/
            // ==================================================
            if (data.contains("/ver/")) {

                val html = app.get(
                    data,
                    referer = mainUrl
                ).text

                val episodeId =
                    Regex("""data-episode-id=["'](\d+)""")
                        .find(html)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?: Regex("""data-epid=["'](\d+)""")
                            .find(html)
                            ?.groupValues
                            ?.getOrNull(1)
                        ?: Regex("""[?&]ep=(\d+)""")
                            .find(data)
                            ?.groupValues
                            ?.getOrNull(1)

                if (!episodeId.isNullOrBlank()) {
                    processEpisode(data, episodeId)
                }

                return found
            }

            // ==================================================
            // CASO 2: FICHA ANIME / PELICULA
            // BUSCAR PRIMER WATCH PAGE (como balandro)
            // ==================================================
            val html = app.get(
                data,
                referer = mainUrl
            ).text

            val watchUrl =
                Regex("""href=["']([^"']*/ver/[^"']+)["']""")
                    .findAll(html)
                    .map { fixUrl(it.groupValues[1]) }
                    .firstOrNull()

            if (!watchUrl.isNullOrBlank()) {

                val watchHtml = app.get(
                    watchUrl,
                    referer = data
                ).text

                val episodeId =
                    Regex("""data-episode-id=["'](\d+)""")
                        .find(watchHtml)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?: Regex("""data-epid=["'](\d+)""")
                            .find(watchHtml)
                            ?.groupValues
                            ?.getOrNull(1)
                        ?: Regex("""[?&]ep=(\d+)""")
                            .find(watchUrl)
                            ?.groupValues
                            ?.getOrNull(1)

                if (!episodeId.isNullOrBlank()) {
                    processEpisode(watchUrl, episodeId)
                }
            }

        } catch (_: Exception) {
        }

        return found
    }
}