package com.sololatino

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlin.text.Regex

object Embed69Extractor {

    suspend fun load(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        try {

            val html = app.get(
                url,
                referer = referer
            ).text

            // =========================
            // dataLink JSON
            // =========================
            Regex(
                """dataLink\s*=\s*(\[.*?\]);""",
                RegexOption.DOT_MATCHES_ALL
            )
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { json ->

                    val parsed =
                        AppUtils.tryParseJson<
                                List<Map<String, Any>>
                                >(json)
                            ?: return@let

                    parsed.forEach { lang ->

                        val embeds =
                            lang["sortedEmbeds"]
                                    as? List<Map<String, Any>>
                                ?: return@forEach

                        embeds.forEach { embed ->

                            val enc =
                                embed["link"] as? String
                                    ?: return@forEach

                            val real =
                                decode(enc)
                                    ?: return@forEach

                            val fixed =
                                fixHosts(real)

                            // =========================
                            // DIRECTO
                            // =========================
                            if (
                                fixed.contains(".m3u8") ||
                                fixed.contains(".mp4")
                            ) {

                                callback.invoke(
                                    newExtractorLink(
                                        "Embed69",
                                        "Embed69",
                                        fixed
                                    ) {
                                        this.referer = url

                                        if (
                                            fixed.contains(".m3u8")
                                        ) {
                                            this.type =
                                                ExtractorLinkType.M3U8
                                        }
                                    }
                                )

                                return@forEach
                            }

                            // =========================
                            // EXTRACTOR NORMAL
                            // =========================
                            try {

                                loadExtractor(
                                    fixed,
                                    url,
                                    subtitleCallback,
                                    callback
                                )

                            } catch (_: Exception) {}

                            // =========================
                            // EXTRA PROFUNDO
                            // =========================
                            try {

                                val htmlX =
                                    app.get(
                                        fixed,
                                        referer = url
                                    ).text

                                Regex(
                                    """https?:\/\/[^\s"'<>]+"""
                                )
                                    .findAll(htmlX)
                                    .map { it.value }
                                    .distinct()
                                    .forEach deep@{ deep ->

                                        val finalLink =
                                            fixHosts(deep)

                                        if (
                                            finalLink.contains("m3u8") ||
                                            finalLink.contains("mp4")
                                        ) {

                                            callback.invoke(
                                                newExtractorLink(
                                                    "Embed69",
                                                    "Embed69",
                                                    finalLink
                                                ) {
                                                    this.referer =
                                                        fixed

                                                    if (
                                                        finalLink.contains(
                                                            ".m3u8"
                                                        )
                                                    ) {
                                                        this.type =
                                                            ExtractorLinkType.M3U8
                                                    }
                                                }
                                            )

                                            return@deep
                                        }

                                        try {

                                            loadExtractor(
                                                finalLink,
                                                fixed,
                                                subtitleCallback,
                                                callback
                                            )

                                        } catch (_: Exception) {}
                                    }

                            } catch (_: Exception) {}
                        }
                    }
                }

            // =========================
            // FALLBACK M3U8
            // =========================
            Regex(
                """https?:\/\/[^\s"'<>]+\.m3u8"""
            )
                .findAll(html)
                .map { it.value }
                .distinct()
                .forEach {

                    callback.invoke(
                        newExtractorLink(
                            "Embed69",
                            "Embed69",
                            it
                        ) {
                            this.type =
                                ExtractorLinkType.M3U8

                            this.referer = url
                        }
                    )
                }

            // =========================
            // FALLBACK HOSTS
            // =========================
            Regex(
                """https?:\/\/[^\s"'<>]+"""
            )
                .findAll(html)
                .map { it.value }
                .distinct()
                .forEach {

                    val fixed =
                        fixHosts(it)

                    try {

                        loadExtractor(
                            fixed,
                            url,
                            subtitleCallback,
                            callback
                        )

                    } catch (_: Exception) {}
                }

        } catch (_: Exception) {}
    }

    // =========================
    // JWT BASE64
    // =========================
    private fun decode(
        enc: String
    ): String? {

        return try {

            val parts =
                enc.split(".")

            if (parts.size != 3) {
                return null
            }

            var payload =
                parts[1]

            val pad =
                payload.length % 4

            if (pad != 0) {
                payload += "=".repeat(
                    4 - pad
                )
            }

            val json = String(
                Base64.decode(
                    payload,
                    Base64.DEFAULT
                )
            )

            Regex(
                "\"link\":\"(.*?)\""
            )
                .find(json)
                ?.groupValues
                ?.getOrNull(1)

        } catch (_: Exception) {
            null
        }
    }

    // =========================
    // FIX HOSTS
    // =========================
    private fun fixHosts(
        url: String
    ): String {

        return url
            .replace(
                "hglink.to",
                "streamwish.to"
            )
            .replace(
                "swdyu.com",
                "streamwish.to"
            )
            .replace(
                "cybervynx.com",
                "streamwish.to"
            )
            .replace(
                "dumbalag.com",
                "streamwish.to"
            )
            .replace(
                "wishembed.com",
                "streamwish.to"
            )
            .replace(
                "stwishe.com",
                "streamwish.to"
            )

            .replace(
                "mivalyo.com",
                "vidhidepro.com"
            )
            .replace(
                "dinisglows.com",
                "vidhidepro.com"
            )
            .replace(
                "dhtpre.com",
                "vidhidepro.com"
            )
            .replace(
                "vidhide.com",
                "vidhidepro.com"
            )
            .replace(
                "voidboost.net",
                "vidhidepro.com"
            )

            .replace(
                "filemoon.link",
                "filemoon.sx"
            )
            .replace(
                "filemoon.lat",
                "filemoon.sx"
            )

            .replace(
                "uqload.io",
                "uqload.com"
            )

            .replace(
                "voe.sx",
                "voe.unblockit.cat"
            )
    }
}