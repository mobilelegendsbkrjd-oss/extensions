package com.verpeliculasonline

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class VerPeliculasOnlinePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(VerPeliculasOnline())
    }
}