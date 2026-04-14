package com.gnulahd

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object UniversalExtractor {

    private const val G9R6_DOMAIN = "https://g9r6.com"
    private const val BYSE_DOMAIN = "https://bysevepoin.com"

    suspend fun load(
        url: String,
        referer: String,
        languageTag: String,  // <-- NUEVO PARÁMETRO
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val clean = normalize(url)

        if (resolveGroupedServers(clean, referer, languageTag, subtitleCallback, callback)) {
            return
        }

        when {
            isVoe(clean) -> {
                resolveVoe(clean, referer, callback)
            }

            isVidsonic(clean) -> {
                if (!resolveVidsonic(clean, referer, languageTag, callback)) {
                    resolveGeneric(clean, referer, subtitleCallback, callback)
                }
            }

            isByse(clean) -> {
                if (!resolveByse(clean, referer, languageTag, callback)) {
                    resolveGeneric(clean, referer, subtitleCallback, callback)
                }
            }

            isWaaw(clean) -> {
                if (!resolveWaaw(clean, referer, languageTag, callback)) {
                    resolveGeneric(clean, referer, subtitleCallback, callback)
                }
            }

            else -> {
                resolveGeneric(clean, referer, subtitleCallback, callback)
            }
        }
    }

    // =====================================================
    // GNULA _gd
    // =====================================================

    private suspend fun resolveGroupedServers(
        url: String,
        referer: String,
        defaultLanguageTag: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return runCatching {

            val html = app.get(url, referer = referer).text

            val raw = Regex("""_gd\s*=\s*(\[[\s\S]*?]);""")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?: return false

            val arr = JSONArray(raw)

            for (i in 0 until arr.length()) {

                val langObj = arr.getJSONObject(i)
                val langLabel = langObj.optString("label")
                val tag = mapLang(langLabel)

                val servers =
                    langObj.optJSONArray("servers")
                        ?: continue

                for (x in 0 until servers.length()) {

                    val src = servers
                        .getJSONObject(x)
                        .optString("src")
                        .replace("\\/", "/")

                    if (src.isBlank()) continue

                    when {

                        // VOE solo LAT
                        isVoe(src) -> {
                            if (tag == "LAT") {
                                resolveVoe(
                                    src,
                                    url,
                                    callback
                                )
                            }
                        }

                        isVidsonic(src) -> {
                            resolveVidsonic(
                                src,
                                url,
                                tag,
                                callback
                            )
                        }

                        isByse(src) -> {
                            resolveByse(
                                src,
                                url,
                                tag,
                                callback
                            )
                        }

                        isWaaw(src) -> {
                            resolveWaaw(
                                src,
                                url,
                                tag,
                                callback
                            )
                        }

                        else -> {
                            resolveGeneric(
                                src,
                                url,
                                subtitleCallback,
                                callback
                            )
                        }
                    }
                }
            }

            true
        }.getOrDefault(false)
    }

    // =====================================================
    // LANG
    // =====================================================

    private fun mapLang(label: String): String {
        return when {
            label.contains("lat", true) -> "LAT"
            label.contains("sub", true) -> "SUB"
            label.contains("cast", true) -> "CAS"
            label.contains("esp", true) -> "ESP"
            label.contains("ing", true) -> "ING"
            label.contains("jap", true) -> "JAP"
            else -> label.uppercase().take(3)
        }
    }

    // =====================================================
    // DETECTORS
    // =====================================================

    private fun normalize(url: String): String {
        return url.trim()
            .replace("\\/", "/")
    }

    private fun isVoe(url: String) =
        url.contains("voe", true)

    private fun isVidsonic(url: String) =
        url.contains("vidsonic", true)

    private fun isByse(url: String) =
        url.contains("byse", true) ||
                url.contains("vepoin", true) ||
                url.contains("g9r6", true)

    private fun isWaaw(url: String) =
        url.contains("waaw", true)

    // =====================================================
    // VOE = CLOUDSTREAM
    // =====================================================

    private suspend fun resolveVoe(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return runCatching {
            loadExtractor(
                url,
                referer,
                {},
                callback
            )
            true
        }.getOrDefault(false)
    }

    // =====================================================
    // VIDSONIC
    // =====================================================

    private suspend fun resolveVidsonic(
        url: String,
        referer: String,
        tag: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return runCatching {

            val html =
                app.get(url, referer = referer).text

            val packed = Regex(
                """const\s+_0x1\s*=\s*['"]([^'"]+)"""
            ).find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?: return false

            val final =
                decodeVid(packed)

            emit(
                "Vidsonic ($tag)",
                final,
                url,
                callback
            )

            true
        }.getOrDefault(false)
    }

    private fun decodeVid(raw: String): String {

        val clean = raw.replace("|", "")
        val sb = StringBuilder()

        var i = 0

        while (i < clean.length - 1) {
            sb.append(
                clean.substring(i, i + 2)
                    .toInt(16)
                    .toChar()
            )
            i += 2
        }

        return sb.toString().reversed()
    }

    // =====================================================
    // BYSE
    // =====================================================

    private suspend fun resolveByse(
        url: String,
        referer: String,
        tag: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val id = extractId(url)

        return tryPlayback(
            G9R6_DOMAIN,
            id,
            referer,
            tag,
            callback
        ) || tryPlayback(
            BYSE_DOMAIN,
            id,
            referer,
            tag,
            callback
        )
    }

    private suspend fun tryPlayback(
        domain: String,
        id: String,
        referer: String,
        tag: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return runCatching {

            val txt = app.get(
                "$domain/api/videos/$id/embed/playback",
                headers = mapOf(
                    "referer" to "$domain/e/$id",
                    "origin" to domain,
                    "x-embed-parent" to referer
                )
            ).text

            val playback =
                JSONObject(txt)
                    .getJSONObject("playback")

            val iv =
                decode64(
                    playback.getString("iv")
                )

            val payload =
                decode64(
                    playback.getString("payload")
                )

            val parts =
                playback.getJSONArray("key_parts")

            val key =
                decode64(parts.getString(0)) +
                        decode64(parts.getString(1))

            val cipher =
                Cipher.getInstance(
                    "AES/GCM/NoPadding"
                )

            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(
                    128,
                    iv.copyOf(12)
                )
            )

            val plain = String(
                cipher.doFinal(payload),
                StandardCharsets.UTF_8
            )

            val stream =
                JSONObject(plain)
                    .getJSONArray("sources")
                    .getJSONObject(0)
                    .optString("url")

            M3u8Helper.generateM3u8(
                "Byse ($tag)",
                stream,
                domain
            ).forEach(callback)

            true
        }.getOrDefault(false)
    }

    private fun decode64(str: String): ByteArray {
        return try {
            Base64.decode(
                str,
                Base64.URL_SAFE or
                        Base64.NO_PADDING
            )
        } catch (_: Exception) {
            Base64.decode(
                str,
                Base64.DEFAULT
            )
        }
    }

    // =====================================================
    // WAAW
    // =====================================================

    private suspend fun resolveWaaw(
        url: String,
        referer: String,
        tag: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return runCatching {

            val links =
                Waaw().getUrl(
                    url,
                    referer
                )

            links?.forEach {

                callback(
                    newExtractorLink(
                        "Waaw ($tag)",
                        "Waaw ($tag)",
                        it.url,
                        it.type
                    ) {
                        this.referer =
                            it.referer
                        this.quality =
                            it.quality
                    }
                )
            }

            links != null
        }.getOrDefault(false)
    }

    class Waaw : StreamSB() {
        override var mainUrl =
            "https://waaw.to"
    }

    // =====================================================
    // GENERIC
    // =====================================================

    private suspend fun resolveGeneric(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            loadExtractor(
                url,
                referer,
                subtitleCallback,
                callback
            )
        }
    }

    // =====================================================
    // HELPERS
    // =====================================================

    private fun extractId(
        url: String
    ): String {
        return url
            .substringAfterLast("/")
            .trim()
    }

    private suspend fun emit(
        source: String,
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {

        callback(
            newExtractorLink(
                source,
                source,
                URLDecoder.decode(
                    url,
                    "UTF-8"
                ),
                if (
                    url.contains(".m3u8")
                )
                    ExtractorLinkType.M3U8
                else
                    INFER_TYPE
            ) {
                this.referer =
                    referer

                this.quality =
                    Qualities.Unknown.value
            }
        )
    }
}