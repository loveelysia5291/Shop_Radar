package com.aykkdk.shop

import android.app.Application
import androidx.work.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RadarApplication : Application(), Configuration.Provider {
    override fun onCreate() {
        super.onCreate()
        RefreshNotificationAccess.createChannels(this)
        ImageCachePolicy.start(this)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AutomaticRefreshScheduler.initialize(this@RadarApplication)
            } catch (_: Exception) { }
        }
    }
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()
}
