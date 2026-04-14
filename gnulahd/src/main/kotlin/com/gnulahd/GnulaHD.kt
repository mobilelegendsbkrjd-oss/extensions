// ===============================
// GnulaHD.kt COMPLETO CORREGIDO
// SOLO cambia loadLinks para pasar idioma al UniversalExtractor
// ===============================

package com.gnulahd

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class GnulaHD : MainAPI() {

    override var mainUrl = "https://ww3.gnulahd.nu"
    override var name = "GnulaHD"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )
    override var lang = "es"
    override val hasMainPage = true

    private fun fixUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> mainUrl + url
            else -> url
        }
    }

    private fun fixUrlNull(url: String?): String? {
        return if (url.isNullOrBlank()) null else fixUrl(url)
    }

    override val mainPage = mainPageOf(
        "$mainUrl/ver/?type=Pelicula&order=latest" to "Últimas Películas",
        "$mainUrl/ver/?type=Serie&order=latest" to "Últimas Series",
        "$mainUrl/ver/?type=Anime&order=latest" to "Últimos Animes",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url =
            if (page <= 1) request.data
            else "${request.data}&page=$page"

        val response = app.get(
            url,
            headers = mapOf("User-Agent" to USER_AGENT),
            timeout = 45
        )

        val document = response.document

        val home = document.select(
            "div.postbody div.listupd article.bs"
        ).mapNotNull { it.toSearchResult() }

        val hasNext =
            document.select("div.hpage a.r").isNotEmpty() ||
                    document.select("div.pagination a.next").isNotEmpty()

        return newHomePageResponse(
            request.name,
            home,
            hasNext
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val response = app.get(
            "$mainUrl/?s=$query",
            referer = mainUrl,
            headers = mapOf("User-Agent" to USER_AGENT)
        )

        return response.document
            .select("div.listupd article.bs")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> = search(query)

    private fun Element.toSearchResult(): SearchResponse? {

        if (this.hasClass("styleegg")) return null

        val aTag = this.selectFirst("div.bsx > a")
            ?: return null

        val href = fixUrl(aTag.attr("href"))

        if (href.contains("/blog/")) return null

        var title = aTag.attr("title").trim()

        if (title.isBlank()) {
            title = this.selectFirst("div.tt h2")
                ?.text()
                ?.trim()
                ?: return null
        }

        val typeText =
            this.selectFirst("div.typez")
                ?.text()
                ?: ""

        val type = when {
            typeText.contains("Serie", true) -> TvType.TvSeries
            typeText.contains("Anime", true) -> TvType.Anime
            else -> TvType.Movie
        }

        val img =
            this.selectFirst("img.ts-post-image")
                ?: this.selectFirst("img.wp-post-image")
                ?: this.selectFirst("div.limit img")

        val poster =
            img?.attr("src")?.ifBlank { null }
                ?: img?.attr("data-src")?.ifBlank { null }
                ?: img?.attr("data-lazy-src")?.ifBlank { null }

        val posterUrl = fixUrlNull(
            poster?.substringBefore("?")
        )

        return when (type) {

            TvType.TvSeries ->
                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                ) {
                    this.posterUrl = posterUrl
                }

            TvType.Anime ->
                newAnimeSearchResponse(
                    title,
                    href,
                    TvType.Anime
                ) {
                    this.posterUrl = posterUrl
                }

            else ->
                newMovieSearchResponse(
                    title,
                    href,
                    TvType.Movie
                ) {
                    this.posterUrl = posterUrl
                }
        }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val response = app.get(url, timeout = 60)
        val document = response.document

        val title =
            document.selectFirst("h1.gnpv-title")
                ?.text()
                ?.trim()
                ?: document.selectFirst("h1")
                    ?.text()
                    ?.trim()
                ?: return null

        val poster =
            document.selectFirst("div.gnpv-poster img")
                ?.attr("src")
                ?: document.selectFirst(
                    "meta[property=og:image]"
                )?.attr("content")

        val posterUrl = fixUrlNull(
            poster?.substringBefore("?")
        )

        val description =
            document.selectFirst("div.gnpv-syn-text")
                ?.text()
                ?.trim()
                ?: document.selectFirst(
                    "meta[property=og:description]"
                )?.attr("content")

        val badge =
            document.selectFirst("div.gnpv-badge")
                ?.text()
                ?: ""

        val year =
            Regex("\\d{4}")
                .find(badge)
                ?.value
                ?.toIntOrNull()

        val tags =
            document.select("div.gnpv-genres a")
                .map { it.text() }

        val isAnime =
            tags.any { it.contains("Anime", true) }

        val isSeries =
            badge.contains("Serie", true) ||
                    isAnime ||
                    document.selectFirst("div.eplister") != null

        val tvType =
            if (isAnime) TvType.Anime
            else if (isSeries) TvType.TvSeries
            else TvType.Movie

        if (isSeries) {

            val episodes =
                document.select("div.eplister ul li")
                    .mapNotNull { li ->

                        val a =
                            li.selectFirst("a")
                                ?: return@mapNotNull null

                        val href = fixUrl(a.attr("href"))

                        val epNum =
                            a.selectFirst("div.epl-num")
                                ?.text()
                                ?.trim()
                                ?: ""

                        val epTitle =
                            a.selectFirst("div.epl-title")
                                ?.text()
                                ?.trim()

                        val match =
                            Regex("(\\d+)x(\\d+)")
                                .find(epNum)

                        if (match != null) {

                            val season =
                                match.groupValues[1]
                                    .toIntOrNull()

                            val episode =
                                match.groupValues[2]
                                    .toIntOrNull()

                            newEpisode(href) {
                                this.name = epTitle
                                this.season = season
                                this.episode = episode
                                this.posterUrl = posterUrl
                            }

                        } else {

                            newEpisode(href) {
                                this.name = epTitle ?: epNum
                                this.season = 1
                                this.posterUrl = posterUrl
                            }
                        }
                    }.reversed()

            return newTvSeriesLoadResponse(
                title,
                url,
                tvType,
                episodes
            ) {
                this.posterUrl = posterUrl
                this.plot = description
                this.year = year
                this.tags = tags
            }
        }

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {
            this.posterUrl = posterUrl
            this.plot = description
            this.year = year
            this.tags = tags
        }
    }

    // =========================================
    // LINKS CORREGIDO
    // =========================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val html = app.get(data).text

        val regex = Regex(
            """var\s+(_gnpv_ep_langs|_gd)\s*=\s*(\[.*?]);""",
            RegexOption.DOT_MATCHES_ALL
        )

        val match = regex.find(html)

        if (match != null) {

            runCatching {

                val json = match.groupValues[2]

                val langs =
                    AppUtils.parseJson<List<GnulaLang>>(json)

                langs.forEach { lang ->

                    lang.servers.forEach { server ->

                        val src =
                            server.src
                                .replace("\\/", "/")
                                .trim()

                        if (src.isNotBlank()) {

                            UniversalExtractor.load(
                                src,
                                data,
                                lang.label,
                                subtitleCallback,
                                callback
                            )
                        }
                    }
                }

                return true
            }
        }

        Regex(
            """<iframe[^>]+src=["']([^"']+)"""
        ).findAll(html).forEach {

            val src =
                it.groupValues[1]
                    .replace("\\/", "/")
                    .trim()

            if (src.startsWith("http")) {

                UniversalExtractor.load(
                    src,
                    data,
                    "RAW",
                    subtitleCallback,
                    callback
                )
            }
        }

        return true
    }
}

data class GnulaLang(
    val label: String,
    val servers: List<GnulaServer>
)

data class GnulaServer(
    val title: String,
    val src: String
)