package com.aykkdk.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.json.JSONObject

@Composable
internal fun SubscriptionThumbnail(item: JSONObject, modifier: Modifier = Modifier.size(72.dp)) {
    val context = LocalContext.current
    val url = item.optString("url")
    val itemImage = ProductThumbnail.url(item.optString("image"))
    val cachedImage = remember(url) {
        context.getSharedPreferences("image_cache_index", 0).getString(url, null)?.let { ProductThumbnail.url(it) }
    }
    val effectiveImage = itemImage ?: cachedImage

    LaunchedEffect(url, effectiveImage) {
        if (effectiveImage != null) {
            ImageCachePolicy.record(context, listOf(
                Product(item.optString("title"), item.optLong("price", -1), item.optString("platform"), url, effectiveImage)))
            if (item.optString("image").isBlank()) {
                val prefs = context.getSharedPreferences("product_subscriptions_v1", 0)
                prefs.getString(url, null)?.let { raw ->
                    runCatching {
                        val obj = JSONObject(raw)
                        obj.put("image", effectiveImage)
                        prefs.edit().putString(url, obj.toString()).apply()
                    }
                }
            }
        } else if (url.isNotBlank()) {
            SubscriptionRefreshQueue.request(context, RefreshTarget(RefreshKind.PRODUCT, url))
        }
    }

    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        Icon(Icons.Default.ShoppingBag, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.outline)
        if (effectiveImage != null) AsyncImage(effectiveImage, "商品縮圖", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}
