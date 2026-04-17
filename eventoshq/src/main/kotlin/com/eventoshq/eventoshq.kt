package com.eventoshq

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class EventosHQProvider : MainAPI() {

    override var mainUrl = "https://eventoshq.me"
    override var name = "EventosHQ"
    override var lang = "es"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Live,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        mainUrl to "🔥 WWE",
        mainUrl to "⚡ AEW",
        mainUrl to "🥊 MMA",
        mainUrl to "🏎 Motor",
        mainUrl to "📺 Repeticiones"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val doc = app.get(
            mainUrl,
            headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to USER_AGENT
            )
        ).document

        val links = doc.select("#primary-menu a[href]")

        val filtered = when (request.name) {

            "🔥 WWE" -> links.filter {
                val t = it.text().lowercase()
                t.contains("wwe") ||
                        t.contains("raw") ||
                        t.contains("smackdown") ||
                        t.contains("nxt") ||
                        t.contains("ppv")
            }

            "⚡ AEW" -> links.filter {
                it.text().contains("aew", true)
            }

            "🥊 MMA" -> links.filter {
                val t = it.text().lowercase()
                t.contains("ufc") ||
                        t.contains("mma") ||
                        t.contains("bellator") ||
                        t.contains("boxeo")
            }

            "🏎 Motor" -> links.filter {
                val t = it.text().lowercase()
                t.contains("formula") ||
                        t.contains("f1") ||
                        t.contains("motogp") ||
                        t.contains("indycar") ||
                        t.contains("rally")
            }

            else -> links.filter {
                val h = it.absUrl("href")
                h.contains("rep.") ||
                        h.contains("descargas.") ||
                        it.text().contains("repet", true)
            }
        }

        val items = filtered
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .take(80)

        return newHomePageResponse(
            request.name,
            items,
            false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val doc = app.get(
            mainUrl,
            headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to USER_AGENT
            )
        ).document

        return doc.select("#primary-menu a[href]")
            .mapNotNull { it.toSearchResult() }
            .filter {
                it.name.contains(query, true)
            }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {

        val title = url.substringAfterLast("/")
            .substringBefore("?")
            .substringBefore("#")
            .replace("-", " ")
            .replace("/", " ")
            .ifBlank { "EventosHQ" }

        return newMovieLoadResponse(
            title,
            url,
            TvType.Live,
            url
        ) {
            plot = "Contenido EventosHQ"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // directos conocidos
        if (
            data.contains("uii.io") ||
            data.contains("rep.eventoshq.me") ||
            data.contains("descargas.eventoshq.me")
        ) {
            loadExtractor(
                data,
                mainUrl,
                subtitleCallback,
                callback
            )
            return true
        }

        // canales live
        if (data.contains("canales.eventoshq.me")) {

            val doc = app.get(
                data,
                headers = mapOf(
                    "Referer" to mainUrl
                )
            ).document

            val iframe = doc.selectFirst("iframe")
                ?.absUrl("src")

            if (iframe != null) {
                return loadLinks(
                    iframe,
                    isCasting,
                    subtitleCallback,
                    callback
                )
            }
        }

        // iframe live oficial
        if (data.contains("lives.eventoshq.me")) {

            val html = app.get(
                data,
                headers = mapOf(
                    "Referer" to data
                )
            ).text

            val m3u8 = Regex(
                """https?://[^"' ]+\.m3u8[^"' ]*"""
            ).find(html)?.value
                ?: "https://lives.eventoshq.me/hls/eventoshq.m3u8"

            callback.invoke(
                newExtractorLink(
                    source = "EventosHQ",
                    name = "EventosHQ Live",
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    headers = mapOf(
                        "Referer" to data,
                        "Origin" to "https://lives.eventoshq.me",
                        "User-Agent" to USER_AGENT
                    )
                }
            )

            return true
        }

        // fallback universal
        loadExtractor(
            data,
            mainUrl,
            subtitleCallback,
            callback
        )

        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {

        val href = absUrl("href").trim()
        val title = text().trim()

        if (href.isBlank()) return null
        if (title.isBlank()) return null
        if (href == "#") return null

        val poster = selectFirst("img")
            ?.absUrl("src")

        return newMovieSearchResponse(
            title,
            href,
            TvType.Live
        ) {
            posterUrl = poster
        }
    }
}