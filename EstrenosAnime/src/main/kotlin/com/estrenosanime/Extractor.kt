package com.estrenosanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// ======================================================
// VOE MEJORADO
// ======================================================
open class VoeSx : ExtractorApi() {
    override var name = "Voe"
    override var mainUrl = "https://voe.sx"
    override val requiresReferer = false

    private fun grab(script: String, key: String): String? {
        return Regex("""['"]$key['"]\s*:\s*['"]([^'"]+)""")
            .find(script)
            ?.groupValues
            ?.getOrNull(1)
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer).document

        val script = doc.select("script")
            .map { it.data() }
            .firstOrNull {
                it.contains("hls", true) ||
                        it.contains("mp4", true) ||
                        it.contains("sources", true)
            } ?: return

        val hls = grab(script, "hls")
        val mp4 = grab(script, "mp4")

        hls?.let {
            callback.invoke(
                newExtractorLink(name, "$name HLS", it) {
                    type = ExtractorLinkType.M3U8
                    quality = Qualities.P1080.value
                }
            )
        }

        mp4?.let {
            callback.invoke(
                newExtractorLink(name, "$name MP4", it) {
                    type = ExtractorLinkType.VIDEO
                    quality = Qualities.P720.value
                }
            )
        }
    }
}

// ======================================================
// VIDNEST MEJORADO
// ======================================================
open class VidNest : ExtractorApi() {
    override var name = "VidNest"
    override var mainUrl = "https://vidnest.io"
    override val requiresReferer = false

    private fun extractSubs(script: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()

        val block = Regex("""tracks\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(script)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()

        Regex("""\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)
            .findAll(block)
            .forEach { item ->
                val txt = item.groupValues[1]

                val kind = Regex("""kind\s*:\s*["']([^"']+)""")
                    .find(txt)?.groupValues?.getOrNull(1)

                if (kind != "captions") return@forEach

                val file = Regex("""file\s*:\s*["']([^"']+)""")
                    .find(txt)?.groupValues?.getOrNull(1)

                val label = Regex("""label\s*:\s*["']([^"']+)""")
                    .find(txt)?.groupValues?.getOrNull(1)

                if (!file.isNullOrBlank() && !label.isNullOrBlank()) {
                    result.add(file to label)
                }
            }

        return result
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer).document

        val scripts = doc.select("script").map { it.data() }

        var file: String? = null
        var rawScript = ""

        for (script in scripts) {
            if (
                script.contains("jwplayer", true) &&
                script.contains("file", true)
            ) {
                file = Regex("""file\s*:\s*["']([^"']+)["']""")
                    .find(script)
                    ?.groupValues
                    ?.getOrNull(1)

                if (!file.isNullOrBlank()) {
                    rawScript = script
                    break
                }
            }
        }

        val stream = file ?: return

        extractSubs(rawScript).forEach {
            subtitleCallback.invoke(
                SubtitleFile(
                    it.second,
                    it.first
                )
            )
        }

        callback.invoke(
            newExtractorLink(name, name, stream) {
                type = if (stream.contains(".m3u8")) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
                quality = Qualities.P1080.value
            }
        )
    }
}

// ======================================================
// BYSE MODERNO
// ======================================================
open class ByseSX : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://byse.sx"
    override val requiresReferer = true

    private fun getBase(url: String): String {
        val uri = URI(url)
        return "${uri.scheme}://${uri.host}"
    }

    private fun getCode(url: String): String {
        return URI(url).path.trim('/').substringAfterLast("/")
    }

    private fun decodeUrl64(text: String): ByteArray {
        val fixed = text.replace("-", "+").replace("_", "/")
        val pad = (4 - fixed.length % 4) % 4
        return base64DecodeArray(fixed + "=".repeat(pad))
    }

    private fun buildKey(parts: List<String>): ByteArray {
        return decodeUrl64(parts[0]) + decodeUrl64(parts[1])
    }

    private fun decrypt(
        iv: String,
        payload: String,
        parts: List<String>
    ): String? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, decodeUrl64(iv))
            val key = SecretKeySpec(buildKey(parts), "AES")

            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            String(
                cipher.doFinal(decodeUrl64(payload)),
                StandardCharsets.UTF_8
            )
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val base = getBase(url)
            val code = getCode(url)

            val details =
                app.get("$base/api/videos/$code/embed/details").text

            val frame =
                Regex(""""embed_frame_url"\s*:\s*"([^"]+)"""")
                    .find(details)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return

            val frameBase = getBase(frame)
            val frameCode = getCode(frame)

            val playback = app.get(
                "$frameBase/api/videos/$frameCode/embed/playback",
                headers = mapOf(
                    "referer" to frame,
                    "x-embed-parent" to url
                )
            ).text

            val iv =
                Regex(""""iv"\s*:\s*"([^"]+)"""")
                    .find(playback)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return

            val payload =
                Regex(""""payload"\s*:\s*"([^"]+)"""")
                    .find(playback)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return

            val block =
                Regex(""""key_parts"\s*:\s*\[(.*?)]""")
                    .find(playback)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return

            val parts = Regex(""""([^"]+)"""")
                .findAll(block)
                .map { it.groupValues[1] }
                .toList()

            if (parts.size < 2) return

            val json = decrypt(iv, payload, parts) ?: return

            val stream =
                Regex(""""url"\s*:\s*"([^"]+)"""")
                    .find(json)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return

            M3u8Helper.generateM3u8(
                name,
                stream,
                mainUrl,
                headers = mapOf("Referer" to base)
            ).forEach(callback)

        } catch (_: Exception) {
        }
    }
}

class ByseQekaho : ByseSX() {
    override var name = "ByseQekaho"
    override var mainUrl = "https://byseqekaho.com"
}

class ByseVepoin : ByseSX() {
    override var name = "ByseVepoin"
    override var mainUrl = "https://bysevepoin.com"
}

class ByseBuho : ByseSX() {
    override var name = "ByseBuho"
    override var mainUrl = "https://bysebuho.com"
}

class Bysezejataos : ByseSX() {
    override var name = "Bysezejataos"
    override var mainUrl = "https://bysezejataos.com"
}

// ======================================================
// UNIVERSAL EXTRACTOR DEFINITIVO
// ======================================================
object UniversalExtractor {

    suspend fun resolve(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val realUrl = url.trim()
        if (realUrl.isBlank()) return false

        return try {

            when {

                // =====================================
                // VOE
                // =====================================
                realUrl.contains("voe", true) -> {
                    VoeSx().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                // =====================================
                // BIGWARP FAMILY (EXPLICITO)
                // =====================================
                realUrl.contains("bigwarp.io", true) -> {
                    BigwarpIO().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                realUrl.contains("bgwp.cc", true) -> {
                    BgwpCC().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                realUrl.contains("bigwarp.art", true) -> {
                    BigwarpArt().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                // =====================================
                // BYSE FAMILY
                // =====================================
                realUrl.contains("byseqekaho", true) -> {
                    ByseQekaho().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                realUrl.contains("bysevepoin", true) -> {
                    ByseVepoin().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                realUrl.contains("bysebuho", true) -> {
                    ByseBuho().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                realUrl.contains("bysezejataos", true) -> {
                    Bysezejataos().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                realUrl.contains("byse", true) -> {
                    ByseSX().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                // =====================================
                // VIDNEST
                // =====================================
                realUrl.contains("vidnest", true) -> {
                    VidNest().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                // =====================================
                // DOOD FAMILY (TODOS)
                // =====================================
                realUrl.contains("dsvplay", true) -> {
                    Dsvplay().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                realUrl.contains("doods.pro", true) -> {
                    Doodspro().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                realUrl.contains("doodstream", true) -> {
                    DoodstreamCom().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                realUrl.contains("dooood", true) -> {
                    Dooood().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                realUrl.contains("dood.wf", true) -> {
                    DoodWfExtractor().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                realUrl.contains("dood.cx", true) -> {
                    DoodCxExtractor().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                realUrl.contains("dood.sh", true) -> {
                    DoodShExtractor().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                realUrl.contains("dood.watch", true) -> {
                    DoodWatchExtractor().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                realUrl.contains("dood.pm", true) -> {
                    DoodPmExtractor().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                realUrl.contains("dood.to", true) -> {
                    DoodToExtractor().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                realUrl.contains("dood.so", true) -> {
                    DoodSoExtractor().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                realUrl.contains("dood.ws", true) -> {
                    DoodWsExtractor().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                realUrl.contains("dood.yt", true) -> {
                    DoodYtExtractor().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                realUrl.contains("dood.li", true) -> {
                    DoodLiExtractor().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                realUrl.contains("ds2play", true) -> {
                    Ds2play().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                realUrl.contains("ds2video", true) -> {
                    Ds2video().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                realUrl.contains("vide0.net", true) -> {
                    Vide0Net().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                realUrl.contains("myvidplay", true) -> {
                    MyVidPlay().getUrl(realUrl, referer, subtitleCallback, callback)
                    true
                }

                realUrl.contains("playmogo", true) -> {
                    loadExtractor(realUrl, referer, subtitleCallback, callback)
                    true
                }

                // =====================================
                // FALLBACK UNIVERSAL
                // =====================================
                else -> {
                    loadExtractor(realUrl, referer, subtitleCallback, callback)
                    true
                }
            }

        } catch (_: Exception) {
            false
        }
    }
}