package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class ExamplePlugin: Plugin() {
    override fun load(context: Context) {
        // Register ONLY StardimaProvider (Removes the Lorem Ipsum test provider)
        registerMainAPI(StardimaProvider())
    }
}