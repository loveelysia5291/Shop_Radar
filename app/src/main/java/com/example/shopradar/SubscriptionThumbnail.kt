package com.example.shopradar

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
    val image = ProductThumbnail.url(item.optString("image"))
    val context = LocalContext.current
    val url = item.optString("url")
    LaunchedEffect(url, image) {
        if (image != null) ImageCachePolicy.record(context, listOf(
            Product(item.optString("title"), item.optLong("price", -1), item.optString("platform"), url, image)))
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        Icon(Icons.Default.ShoppingBag, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.outline)
        if (image != null) AsyncImage(image, "商品縮圖", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}
