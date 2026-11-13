package com.aykkdk.shop

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RadarFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_SUBSCRIPTIONS = "fcm_subscriptions"
        const val CHANNEL_AUCTIONS = "fcm_auctions"
        const val CHANNEL_LOGISTICS = "fcm_logistics"
        const val CHANNEL_GENERAL = "fcm_general"

        const val EXTRA_FCM_TAB = "extra_fcm_tab"
        const val EXTRA_FCM_TYPE = "extra_fcm_type"
        const val EXTRA_FCM_URL = "extra_fcm_url"
        const val EXTRA_FCM_TRACKING_NO = "extra_fcm_tracking_no"

        fun createNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

                val channels = listOf(
                    NotificationChannel(
                        CHANNEL_SUBSCRIPTIONS,
                        "訂閱更新通知 (FCM 雲端推播)",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "接收跳蚤本舖與商品上架更新推播"
                        enableVibration(true)
                    },
                    NotificationChannel(
                        CHANNEL_AUCTIONS,
                        "競標狀態通知 (FCM 雲端推播)",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "接收競標超標與結標提醒"
                        enableVibration(true)
                    },
                    NotificationChannel(
                        CHANNEL_LOGISTICS,
                        "物流貨況通知 (FCM 雲端推播)",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "接收超商包裹到店與取件提醒"
                        enableVibration(true)
                    },
                    NotificationChannel(
                        CHANNEL_GENERAL,
                        "系統推播通知 (FCM)",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "雲端伺服器測試與系統訊息"
                        enableVibration(true)
                    }
                )

                channels.forEach { manager.createNotificationChannel(it) }
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val store = AccountStore.get(applicationContext)
        store.saveFcmToken(token)

        if (store.isLoggedIn()) {
            CoroutineScope(Dispatchers.IO).launch {
                store.uploadFcmToken(token)
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val title = remoteMessage.notification?.title
            ?: remoteMessage.data["title"]
            ?: "購物雷達通知"
        val body = remoteMessage.notification?.body
            ?: remoteMessage.data["body"]
            ?: ""
        val type = remoteMessage.data["type"] ?: "general"
        val url = remoteMessage.data["url"] ?: ""
        val trackingNo = remoteMessage.data["tracking_no"] ?: ""

        val channelId = when (type) {
            "subscription_update", "subscription" -> CHANNEL_SUBSCRIPTIONS
            "auction_outbid", "auction" -> CHANNEL_AUCTIONS
            "logistics_update", "logistics" -> CHANNEL_LOGISTICS
            else -> CHANNEL_GENERAL
        }

        val targetTab = when (type) {
            "subscription_update", "subscription" -> 1 // 訂閱
            "auction_outbid", "auction" -> 2 // 競拍
            "logistics_update", "logistics" -> 3 // 工具
            else -> 4 // 關於
        }

        createNotificationChannels(applicationContext)

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_FCM_TAB, targetTab)
            putExtra(EXTRA_FCM_TYPE, type)
            putExtra(EXTRA_FCM_URL, url)
            putExtra(EXTRA_FCM_TRACKING_NO, trackingNo)
        }

        val notificationId = (System.currentTimeMillis() % 100000).toInt()
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        try {
            NotificationManagerCompat.from(applicationContext).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
        }
    }
}

object FcmTokenManager {
    fun fetchToken(context: Context, onResult: (String?) -> Unit) {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    if (!token.isNullOrBlank()) {
                        AccountStore.get(context).saveFcmToken(token)
                    }
                    onResult(token)
                } else {
                    onResult(null)
                }
            }
        } catch (e: Exception) {
            onResult(null)
        }
    }
}
