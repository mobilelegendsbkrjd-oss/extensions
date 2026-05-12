package com.sololatino

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

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
        // Obtenemos el HTML del embed (el que me pasaste de Overflow)
        val response = app.get(url, referer = referer).text
        
        // Regex específico para los botones onclick="go_to_player('...')"
        val regex = Regex("""go_to_player\(\s*['"]([^'"]+)""")
        
        regex.findAll(response).forEach { match ->
            val embedUrl = match.groupValues[1]
            
            // Filtramos los que no sirven o dan problemas (opcional)
            if (embedUrl.contains("mega.nz") || embedUrl.contains("dailymotion")) return@forEach

            // Normalizamos algunos hosts comunes en este embed
            val fixedUrl = embedUrl
                .replace("wolfstream.tv/embed-", "wolfstream.tv/")
                .replace("ok.ru/videoembed/", "ok.ru/video/")

            // Enviamos al core de Cloudstream para que use tus otros extractores (Dood, etc)
            // o los internos del sistema (Streamwish, OKru, etc)
            loadExtractor(fixedUrl, url, subtitleCallback, callback)
        }
        
        // Fallback: Si hay iframes sueltos en ese HTML
        val doc = org.jsoup.Jsoup.parse(response)
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("abs:src")
            if (src.isNotBlank()) {
                loadExtractor(src, url, subtitleCallback, callback)
            }
        }
    }
}
