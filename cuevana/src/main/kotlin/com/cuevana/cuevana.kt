package com.cuevana

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import java.net.URLEncoder

// ================= MODELOS =================

data class ApiResponse(
    @JsonProperty("props") var props: Props? = null
)

data class Props(
    @JsonProperty("pageProps") var pageProps: PageProps? = null
)

data class PageProps(
    @JsonProperty("thisMovie") var thisMovie: MediaItem? = null,
    @JsonProperty("thisSerie") var thisSerie: MediaItem? = null,
    @JsonProperty("episode") var episode: EpisodeInfo? = null
)

data class MediaItem(
    @JsonProperty("videos") var videos: Videos? = null,
    @JsonProperty("seasons") var seasons: List<SeasonInfo>? = null
)

data class SeasonInfo(
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("episodes") val episodes: List<JsonEpisode>? = null
)

data class JsonEpisode(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("url") val url: JsonUrl? = null,
    @JsonProperty("image") val image: String? = null
)

data class JsonUrl(
    @JsonProperty("slug") val slug: String? = null
)

data class EpisodeInfo(
    @JsonProperty("videos") var videos: Videos? = null
)

data class Videos(
    @JsonProperty("latino") var latino: List<VideoInfo>? = null,
    @JsonProperty("spanish") var spanish: List<VideoInfo>? = null,
    @JsonProperty("english") var english: List<VideoInfo>? = null
)

data class VideoInfo(
    @JsonProperty("result") var result: String? = null
)

// ================= MAIN =================

class Cuevana : MainAPI() {

    override var mainUrl = "https://cuevana3.eu/"
    override var name = "Cuevana"
    override var lang = "es"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to mainUrl
    )

    // 🔥 HOME PRO
    override val mainPage = mainPageOf(
        "$mainUrl/peliculas/tendencias/dia/page/" to "🔥 Tendencias Películas",
        "$mainUrl/series/tendencias/dia/page/" to "🔥 Tendencias Series",
        "$mainUrl/peliculas/estrenos/page/" to "🆕 Estrenos Películas",
        "$mainUrl/series/estrenos/page/" to "🆕 Estrenos Series",
        "$mainUrl/peliculas/page/" to "🎬 Películas",
        "$mainUrl/series/page/" to "📺 Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("${request.data}$page", headers = headers).document
        val items = doc.select("main .MovieList.Rows .TPostMv, main .MovieList li.TPostMv")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    // ================= IMÁGENES PRO =================

    private fun fixImageUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        if (url.startsWith("data:image")) return null
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return "$mainUrl/${url.trimStart('/')}"
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".Title, h2, h3")?.text() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null

        val poster = fixImageUrl(
            selectFirst("img")?.let {
                it.attr("data-src")
                    .ifEmpty { it.attr("src") }
                    .ifEmpty { it.attr("data-lazy-src") }
                    .ifEmpty { it.attr("srcset").substringBefore(" ") }
            }
        )

        val isSeries = href.contains("serie")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?q=${URLEncoder.encode(query, "UTF-8")}").document
        return doc.select(".MovieList .TPost").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text() ?: "Sin título"

        val poster = fixImageUrl(
            doc.selectFirst("img")?.attr("data-src")
                ?: doc.selectFirst("img")?.attr("src")
        )

        val banner = fixImageUrl(
            doc.selectFirst(".backdrop img")?.attr("data-src")
                ?: doc.selectFirst(".backdrop img")?.attr("src")
        )

        val plot = doc.selectFirst(".Description")?.text()

        val json = doc.selectFirst("script#__NEXT_DATA__")?.data()
        val parsed = runCatching { parseJson<ApiResponse>(json ?: "") }.getOrNull()

        val episodes = mutableListOf<Episode>()

        parsed?.props?.pageProps?.thisSerie?.seasons?.forEach { season ->
            season.episodes?.forEach { ep ->
                val epUrl = "$mainUrl/episodio/${ep.url?.slug}"

                episodes.add(newEpisode(epUrl) {
                    this.name = ep.title
                    this.season = season.number
                    this.episode = ep.number
                    this.posterUrl = fixImageUrl(ep.image)
                })
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = banner
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = banner
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
        var found = false
        val doc = app.get(data, headers = requestHeaders).document

        fun fixESP(url: String): String = url.replace("\\/", "/")
            .replace("mivalyo.com", "vidhidepro.com")
            .replace("dinisglows.com", "vidhidepro.com")
            .replace("dhtpre.com", "vidhidepro.com")
            .replace("swdyu.com", "streamwish.to")
            .replace("hglink.to", "streamwish.to")
            .replace("callistanise.com", "streamwish.to")
            .replace("filemoon.sx", "filemoon.to")
            .replace("embtaku.pro", "embtaku.com")

        try {
            val jsonData = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return false
            val res = parseJson<ApiResponse>(jsonData)

            val videos = res.props?.pageProps?.let {
                it.thisMovie?.videos ?: it.thisSerie?.videos ?: it.episode?.videos
            } ?: return false

            suspend fun process(v: VideoInfo) {
                val embedUrl = v.result ?: return

                // 🔥 PASO CRÍTICO (ESTO ES LO QUE TE FALTA)
                val resolved = StreamflixResolver.resolve(embedUrl, data)

                if (!resolved.isNullOrBlank()) {
                    val clean = fixESP(resolved)

                    // 🔥 fallback por si viene embed69
                    if (clean.contains("embed69")) {
                        Embed69Extractor.load(clean, data, subtitleCallback, callback)
                        found = true
                    } else {
                        if (loadExtractor(clean, data, subtitleCallback, callback)) {
                            found = true
                        }
                    }
                }
            }

            videos.latino?.forEach { process(it) }
            videos.spanish?.forEach { process(it) }
            videos.english?.forEach { process(it) }

        } catch (_: Throwable) {}

        return found
    }
}