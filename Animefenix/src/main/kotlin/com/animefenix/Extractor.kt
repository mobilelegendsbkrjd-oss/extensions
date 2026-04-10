package com.animefenix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

open class Zilla : ExtractorApi() {
    override var name = "Zilla"
    override var mainUrl = "https://player.zilla-networks.com"
    override val requiresReferer = false

    private suspend fun getAniSkipIntro(malId: Int?, episode: Int): Pair<Double, Double>? {
        if (malId == null) return null

        return try {
            val url = "https://api.aniskip.com/v2/skip-times/$malId/$episode?types=op"
            val res = app.get(url).parsedSafe<Map<String, Any>>() ?: return null

            val results = res["results"] as? List<Map<String, Any>> ?: return null
            val op = results.firstOrNull { it["skip_type"] == "op" } ?: return null
            val interval = op["interval"] as? Map<String, Double> ?: return null

            val start = interval["start_time"] ?: return null
            val end = interval["end_time"] ?: return null

            Pair(start, end)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val videoUrl = "$mainUrl/m3u8/${url.substringAfterLast("/")}"

        val malId: Int? = null      // ← Cambia esto después cuando tengas el ID real
        val episode = 1

        val intro = getAniSkipIntro(malId, episode)

        val linkName = buildString {
            append("Zilla")
            if (intro != null) {
                append(" • Intro ")
                append(intro.first.toInt())
                append("s")
            }
        }

        // Esta es la forma moderna que reemplaza tu código viejo
        return listOf(
            newExtractorLink(
                this.name,
                buildString {
                    append(this@Zilla.name)

                    if (intro != null) {
                        append(" • Intro ")
                        append(intro.first.toInt())
                        append("s")
                    }
                },
                url = videoUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer ?: ""
                this.quality = Qualities.P1080.value
            }
        )
    }
}

class Animeav1upn : VidStack() {
    override var mainUrl = "https://animeav1.uns.bio"
}