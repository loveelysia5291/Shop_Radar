package com.aykkdk.shop

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

internal data class ProductLayoutSelection(val photoGrid: Boolean, val toggle: () -> Unit)

/** Search-only preference: subscriptions and auctions always show two image columns. */
@Composable
internal fun rememberProductLayout(): ProductLayoutSelection {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("product_layout", 0) }
    var grid by remember(prefs) { mutableStateOf(prefs.getBoolean("photo_grid", false)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "photo_grid") grid = prefs.getBoolean("photo_grid", false)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return ProductLayoutSelection(grid) {
        grid = !grid
        prefs.edit().putBoolean("photo_grid", grid).apply()
    }
}

@Composable
internal fun ProductLayoutToggle(selection: ProductLayoutSelection) {
    IconButton(onClick = selection.toggle, modifier = Modifier.size(48.dp)) {
        Icon(if (selection.photoGrid) Icons.Default.ViewAgenda else Icons.Default.GridView,
            if (selection.photoGrid) "切換為單欄商品佈局" else "切換為雙欄圖片佈局")
    }
}

@Composable
internal fun ProductCover(image: String, title: String, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.ShoppingBag, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.outline)
        ProductThumbnail.url(image)?.let { url ->
            AsyncImage(url, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}
