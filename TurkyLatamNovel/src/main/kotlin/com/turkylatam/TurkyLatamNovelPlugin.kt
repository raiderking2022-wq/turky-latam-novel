package com.turkylatam

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TurkyLatamNovelPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TurkyLatamNovelProvider())
    }
}
