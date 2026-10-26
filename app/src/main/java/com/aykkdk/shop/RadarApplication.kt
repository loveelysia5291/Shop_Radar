package com.aykkdk.shop

import android.app.Application
import androidx.work.Configuration

/** WorkManager's database is created on demand, after the first screen is drawn. */
class RadarApplication : Application(), Configuration.Provider {
    override fun onCreate() {
        super.onCreate()
        RefreshNotificationAccess.createChannels(this)
        ImageCachePolicy.start(this)
    }
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()
}
