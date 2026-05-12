package com.sololatino

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

class SoloLatinoEmbed : ExtractorApi() {

    override val name = "SoloLatino Embed"
    override val mainUrl = "https://re.sololatino.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val response = app.get(
            url,
            referer = referer
        ).text

        val regex = Regex("""go_to_player\(\s*['"]([^'"]+)""")

        regex.findAll(response).forEach { match ->

            val embedUrl = match.groupValues[1]

            // opcional
            if (
                embedUrl.contains("mega.nz") ||
                embedUrl.contains("dailymotion")
            ) return@forEach

            val fixedUrl = embedUrl
                .replace("wolfstream.tv/embed-", "wolfstream.tv/")
                .replace("ok.ru/videoembed/", "ok.ru/video/")

            loadExtractor(
                fixedUrl,
                url,
                subtitleCallback,
                callback
            )
        }

        // fallback iframe
        val doc = Jsoup.parse(response)

        doc.select("iframe").forEach { iframe ->

            val src = iframe.attr("src")

            if (src.startsWith("http")) {

                loadExtractor(
                    src,
                    url,
                    subtitleCallback,
                    callback
                )
            }
        }
    }
}