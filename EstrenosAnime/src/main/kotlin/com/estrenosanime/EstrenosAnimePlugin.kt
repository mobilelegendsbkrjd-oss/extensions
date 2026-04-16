package com.estrenosanime

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class EstrenosAnimeProvider: BasePlugin() {
    override fun load() {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(EstrenosAnime())
        //registerExtractorAPI(Extractor())
    }
}