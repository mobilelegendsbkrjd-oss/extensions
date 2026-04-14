package com.pelisplusto

import android.util.Base64
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Document

object PelisplusResolver {

    suspend fun resolve(data: String, referer: String): String? {

        val decoded = try {
            String(Base64.decode(data, Base64.DEFAULT))
        } catch (_: Throwable) {
            return null
        }

        val url = if (decoded.startsWith("http")) {
            decoded
        } else {
            val encoded = Base64.encodeToString(data.toByteArray(), Base64.DEFAULT).trim()
            "https://tioplus.app/player/$encoded"
        }

        val doc: Document = app.get(url, referer = referer).document

        val scripts = doc.select("script")

        for (s in scripts) {

            val script = s.data()

            Regex("""sources:\s*\[\{file:\s*['"](.*?)['"]""")
                .find(script)?.groupValues?.get(1)?.let { return it }

            Regex("""file:\s*['"](https?://.*?)['"]""")
                .find(script)?.groupValues?.get(1)?.let { return it }

            Regex("""https?://[^\s'"]+""")
                .find(script)?.value?.let { return it }
        }

        return null
    }
}