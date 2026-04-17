package com.locopelis

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class LocoPelisProvider : MainAPI() {

    override var mainUrl = "https://www.locopelis.com"
    override var name = "LocoPelis"
    override val hasMainPage = true
    override var lang = "es"

    override val supportedTypes = setOf(
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/pelicula/ultimas-peliculas" to "Últimas",
        "$mainUrl/pelicula/peliculas-mas-vistas" to "Más vistas",
        "$mainUrl/pelicula/peliculas-mas-votadas" to "Más votadas",
        "$mainUrl/pelicula/ultimas-peliculas/cartelera" to "Cartelera",
        "$mainUrl/pelicula/ultimas-peliculas/ultimas/actualizadas" to "Actualizadas"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (page == 1) {
            request.data
        } else {
            "${request.data}?page=$page"
        }

        val doc = app.get(url).document

        val items = doc.select("ul.peliculas li.peli_bx")
            .mapNotNull { li ->
                li.selectFirst("a[href]")?.toSearchResult()
            }
            .distinctBy { it.url }

        val results =
            if (items.isNotEmpty()) {
                items
            } else {
                doc.select("#sldpels a[href]")
                    .mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }
            }

        return newHomePageResponse(
            request.name,
            results,
            hasNext = results.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/buscar/?q=$query").document

        return doc.select("a[href]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val html = doc.html()

        val title = doc.selectFirst("meta[property=og:title]")
            ?.attr("content")
            ?.replace(" - Ver Pelicula Completa", "")
            ?.trim()
            ?: doc.title()

        val poster = doc.selectFirst("meta[property=og:image]")
            ?.attr("content")

        val plot = doc.selectFirst("meta[name=description]")
            ?.attr("content")

        val year = Regex("""\((\d{4})\)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        // guardar HTML completo para sacar TODOS los players
        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            html
        ) {
            posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val html = data

        val players = Regex(
            """https://www\.locopelis\.com/player\.php\?v=[^"' ]+"""
        ).findAll(html).map { it.value }.toList()

        if (players.isEmpty()) return false

        var found = false

        players.forEach { playerUrl ->
            try {
                val playerHtml = app.get(
                    playerUrl,
                    referer = mainUrl
                ).text

                val encoded = Regex("""_0xkey\s*=\s*"([^"]+)"""")
                    .find(playerHtml)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return@forEach

                val iframeUrl = String(
                    Base64.getDecoder().decode(encoded)
                )

                when {
                    iframeUrl.contains("byse") -> {
                        try {
                            ByseSX().extract(iframeUrl, callback)
                            found = true
                        } catch (_: Exception) {
                        }
                    }

                    iframeUrl.contains("myvidplay.com") ||
                            iframeUrl.contains("dood") ||
                            iframeUrl.contains("dsvplay.com") ||
                            iframeUrl.contains("playmogo.com") -> {

                        try {
                            DoodLikeExtractor().extract(
                                iframeUrl,
                                callback
                            )
                            found = true
                        } catch (_: Exception) {
                        }
                    }

                    else -> {
                        loadExtractor(
                            iframeUrl,
                            playerUrl,
                            subtitleCallback,
                            callback
                        )
                        found = true
                    }
                }

            } catch (_: Exception) {
            }
        }

        return found
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = absUrl("href").trim()

        if (!Regex(""".*/pelicula/\d+/.+\.html$""").matches(href)) {
            return null
        }

        val title = attr("title")
            .ifBlank { selectFirst("img")?.attr("alt") ?: "" }
            .ifBlank { text() }
            .trim()

        if (title.isBlank()) return null

        val poster = selectFirst("img")?.absUrl("src")

        return newMovieSearchResponse(
            title,
            href,
            TvType.Movie
        ) {
            posterUrl = poster
        }
    }
}

/* ===========================
   BYSE EXTRACTOR
=========================== */

class ByseSX {

    data class DetailsResponse(
        @JsonProperty("embed_frame_url")
        val embedFrameUrl: String
    )

    data class PlaybackResponse(
        @JsonProperty("playback")
        val playback: PlaybackData?
    )

    data class PlaybackData(
        @JsonProperty("key_parts")
        val keyParts: List<String>,

        @JsonProperty("iv")
        val iv: String,

        @JsonProperty("payload")
        val payload: String
    )

    data class SourceItem(
        @JsonProperty("url")
        val url: String,

        @JsonProperty("bitrate_kbps")
        val bitrate: Int?
    )

    data class FinalData(
        @JsonProperty("sources")
        val sources: List<SourceItem>?
    )

    private val mirrors = listOf(
        "byse.sx",
        "bysesukior.com",
        "bysejikuar.com",
        "bysezoxexe.com",
        "bysezejataos.com",
        "bysebuho.com",
        "bysevepoin.com",
        "byseqekaho.com"
    )

    suspend fun extract(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val uri = URI(url)
        val host = uri.host.removePrefix("www.")
        val code = uri.path.trim('/').split("/").last()

        val details = getDetails(host, code) ?: return

        val frame = details.embedFrameUrl
        val frameUri = URI(frame)

        val frameBase = "${frameUri.scheme}://${frameUri.host}"
        val frameCode = frameUri.path.trim('/').split("/").last()

        val playbackUrl =
            "$frameBase/api/videos/$frameCode/embed/playback"

        val playback = app.get(
            playbackUrl,
            headers = mapOf(
                "referer" to frame,
                "x-embed-parent" to url
            )
        ).parsedSafe<PlaybackResponse>() ?: return

        val pb = playback.playback ?: return

        val key = b64(pb.keyParts[0]) + b64(pb.keyParts[1])
        val iv = b64(pb.iv)
        val raw = b64(pb.payload)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, iv)
        )

        val json = String(
            cipher.doFinal(raw)
        ).trimStart('\uFEFF')

        val parsed = AppUtils.parseJson<FinalData>(json)

        parsed.sources
            ?.sortedByDescending { it.bitrate ?: 0 }
            ?.forEachIndexed { i, item ->

                callback.invoke(
                    newExtractorLink(
                        source = "ByseSX",
                        name = "ByseSX ${item.bitrate ?: i}",
                        url = item.url,
                        type = if (item.url.contains(".m3u8"))
                            ExtractorLinkType.M3U8
                        else
                            ExtractorLinkType.VIDEO
                    ) {
                        headers = mapOf(
                            "Referer" to "$frameBase/"
                        )
                    }
                )
            }
    }

    private suspend fun getDetails(
        host: String,
        code: String
    ): DetailsResponse? {

        val hosts = listOf(host) + mirrors.filter { it != host }

        for (h in hosts) {
            try {
                val res = app.get(
                    "https://$h/api/videos/$code/embed/details",
                    headers = mapOf(
                        "Origin" to "https://$h",
                        "Referer" to "https://$h/e/$code"
                    )
                )

                if (res.code == 200) {
                    return res.parsedSafe()
                }

            } catch (_: Exception) {
            }
        }

        return null
    }

    private fun b64(input: String): ByteArray {
        var text = input.replace("-", "+").replace("_", "/")
        while (text.length % 4 != 0) text += "="
        return Base64.getDecoder().decode(text)
    }
}

/* ===========================
   DOOD / MYVIDPLAY
=========================== */

class DoodLikeExtractor {

    private val alphabet =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    suspend fun extract(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {

        val embed = url.replace("/d/", "/e/")

        val res = app.get(
            embed,
            headers = mapOf(
                "Referer" to url
            )
        )

        val html = res.text
        val finalUrl = res.url

        val base = run {
            val uri = URI(finalUrl)
            "${uri.scheme}://${uri.host}"
        }

        val md5Path = Regex("""/pass_md5/[^'" ]+""")
            .find(html)
            ?.value
            ?: return

        val md5Url = base + md5Path

        val prefix = app.get(
            md5Url,
            headers = mapOf(
                "Referer" to finalUrl
            )
        ).text.trim()

        val token = md5Url.substringAfterLast("/")

        val videoUrl =
            prefix +
                    randomString(10) +
                    "?token=$token"

        callback.invoke(
            newExtractorLink(
                source = "MyVidPlay",
                name = "MyVidPlay",
                url = videoUrl,
                type = if (videoUrl.contains(".m3u8"))
                    ExtractorLinkType.M3U8
                else
                    ExtractorLinkType.VIDEO
            ) {
                headers = mapOf(
                    "Referer" to base
                )
            }
        )
    }

    private fun randomString(length: Int): String {
        return buildString {
            repeat(length) {
                append(alphabet.random())
            }
        }
    }
}