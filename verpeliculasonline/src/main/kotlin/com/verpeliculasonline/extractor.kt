package com.verpeliculasonline

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

object UniversalHostResolver {

    suspend fun resolve(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val lower = url.lowercase()

        // =========================
        // PRIORIDAD DOOD
        // =========================
        if (
            lower.contains("dood") ||
            lower.contains("myvidplay") ||
            lower.contains("vide0.net") ||
            lower.contains("ds2play") ||
            lower.contains("ds2video") ||
            lower.contains("playmogo")
        ) {
            try {
                DoodLaExtractor().getUrl(
                    url,
                    referer,
                    subtitleCallback,
                    callback
                )
                return true
            } catch (_: Exception) {
            }
        }

        // =========================
        // PRIORIDAD OPUXA / HQQ
        // =========================
        if (
            lower.contains("opuxa") ||
            lower.contains("waaw") ||
            lower.contains("netu") ||
            lower.contains("hqq")
        ) {
            try {
                if (
                    extractOpuxa(
                        url,
                        referer,
                        subtitleCallback,
                        callback
                    )
                ) return true
            } catch (_: Exception) {
            }
        }

        // =========================
        // EXTRACTOR NATIVO
        // =========================
        try {
            if (
                loadExtractor(
                    url,
                    referer,
                    subtitleCallback,
                    callback
                )
            ) return true
        } catch (_: Exception) {
        }

        // =========================
        // GENERIC HUNTER
        // =========================
        return tryResolveGeneric(
            url,
            referer,
            callback
        )
    }

    private suspend fun extractOpuxa(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return try {

            val doc = app.get(
                url,
                referer = referer
            ).document

            // iframes primero
            val frames = doc.select("iframe[src]")

            for (frame in frames) {

                var src = frame.attr("src").trim()

                if (src.isBlank()) continue

                if (!src.startsWith("http")) {
                    src = "https://opuxa.lat$src"
                }

                try {
                    if (
                        loadExtractor(
                            src,
                            url,
                            subtitleCallback,
                            callback
                        )
                    ) return true
                } catch (_: Exception) {
                }
            }

            // links directos escondidos
            val html = doc.html()

            val links = Regex(
                """https?:\/\/[^\s"'<>]+"""
            ).findAll(html)

            for (match in links) {

                val link = match.value

                if (
                    link.contains("youtube", true) ||
                    link.contains("trailer", true) ||
                    link.contains("googleads", true)
                ) continue

                try {
                    if (
                        loadExtractor(
                            link,
                            url,
                            subtitleCallback,
                            callback
                        )
                    ) return true
                } catch (_: Exception) {
                }
            }

            false

        } catch (_: Exception) {
            false
        }
    }

    private suspend fun tryResolveGeneric(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return try {

            var text = app.get(
                url,
                referer = referer
            ).text

            try {
                if (
                    text.contains(
                        "eval(function(p,a,c,k,e"
                    )
                ) {
                    val unpack = JsUnpacker(text)

                    if (unpack.detect()) {
                        unpack.unpack()?.let {
                            text = it
                        }
                    }
                }
            } catch (_: Exception) {
            }

            val patterns = listOf(
                Regex("""https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*"""),
                Regex("""https?:\/\/[^\s"'<>]+\.mp4[^\s"'<>]*"""),
                Regex("""file["']?\s*:\s*["']([^"']+)""")
            )

            for (rg in patterns) {

                val m = rg.find(text) ?: continue

                val video =
                    if (
                        m.groupValues.size > 1 &&
                        m.groupValues[1].isNotBlank()
                    ) {
                        m.groupValues[1]
                    } else {
                        m.value
                    }

                callback.invoke(
                    newExtractorLink(
                        "Universal",
                        "Universal",
                        video
                    ) {
                        this.referer = referer
                        this.quality = 0
                        this.type =
                            if (video.contains(".m3u8"))
                                ExtractorLinkType.M3U8
                            else
                                ExtractorLinkType.VIDEO
                    }
                )

                return true
            }

            false

        } catch (_: Exception) {
            false
        }
    }
}