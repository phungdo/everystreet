package com.everystreet.survey

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.osmdroid.config.Configuration

@HiltAndroidApp
class EveryStreetApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Configure OSMDroid
        Configuration.getInstance().apply {
            userAgentValue = packageName
            // Set cache location for map tiles
            osmdroidBasePath = filesDir
            osmdroidTileCache = cacheDir
        }
    }
}
