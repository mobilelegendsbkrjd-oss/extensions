package com.sololatino

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class SoloLatinoPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(SoloLatino())
    }
}