package com.aykkdk.shop

import android.content.Context
import androidx.work.*
import coil.imageLoader
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Only product images are evicted; login cookies and subscription data are untouched. */
@OptIn(coil.annotation.ExperimentalCoilApi::class)
internal object ImageCachePolicy {
    private const val TTL = 7L * 24 * 60 * 60 * 1000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun index(context: Context) = context.getSharedPreferences("image_cache_index", 0)
    private fun dates(context: Context) = context.getSharedPreferences("image_cache_dates", 0)

    fun record(context: Context, products: List<Product>) {
        val entries = index(context); val times = dates(context)
        val urls = entries.edit(); val stamps = times.edit()
        products.filter { it.image.startsWith("https://") }.forEach {
            urls.putString(it.url, it.image)
            if (!times.contains(it.image)) stamps.putLong(it.image, System.currentTimeMillis())
        }
        urls.apply(); stamps.apply()
    }

    private fun evict(context: Context, images: Set<String>) {
        if (images.isEmpty()) return
        val loader = context.imageLoader
        images.forEach { image ->
            loader.diskCache?.remove(image)
            loader.memoryCache?.keys?.filter { it.key == image }?.forEach { loader.memoryCache?.remove(it) }
        }
        val edit = dates(context).edit(); images.forEach { edit.remove(it) }; edit.apply()
        val refs = index(context).edit()
        index(context).all.filterValues { it in images }.keys.forEach { refs.remove(it) }; refs.apply()
    }

    fun removeProducts(context: Context, urls: Collection<String>, extraImages: Collection<String> = emptyList()) {
        val app = context.applicationContext
        val images = (urls.mapNotNull { index(app).getString(it, null) } + extraImages).filter { it.isNotBlank() }.toSet()
        scope.launch { runCatching { evict(app, images) } }
    }

    fun removeShop(context: Context, url: String) {
        val saved = runCatching { JSONObject(context.getSharedPreferences("shop_subscriptions_v2", 0).getString(url, "{}") ?: "{}") }.getOrDefault(JSONObject())
        val seen = saved.optJSONArray("seen")
        val history = saved.optJSONArray("newItems")
        val urls = (0 until (seen?.length() ?: 0)).map { seen!!.optString(it) } +
            (0 until (history?.length() ?: 0)).map { history!!.optJSONObject(it)?.optString("url").orEmpty() }
        val images = (0 until (history?.length() ?: 0)).map { history!!.optJSONObject(it)?.optString("image").orEmpty() }
        removeProducts(context, urls, images)
    }

    fun cleanup(context: Context, now: Long = System.currentTimeMillis()) {
        val expired = dates(context).all.filter { (_, value) -> value !is Long || now - value >= TTL }.keys
        evict(context, expired)
    }

    fun start(context: Context) {
        val app = context.applicationContext
        scope.launch {
            delay(1000) // First frame never waits on the image cache or WorkManager.
            runCatching { cleanup(app) }
            runCatching {
                WorkManager.getInstance(app).enqueueUniquePeriodicWork("image-cache-cleanup", ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<ImageCacheCleanupWorker>(1, TimeUnit.DAYS).build())
            }
        }
    }
}

class ImageCacheCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { ImageCachePolicy.cleanup(applicationContext); Result.success() }.getOrElse { Result.retry() }
    }
}
