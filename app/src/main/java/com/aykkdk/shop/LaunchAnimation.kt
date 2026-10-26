package com.aykkdk.shop

import android.provider.Settings
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** The first Compose draw contains only branding, not the full app's stores/forms/screens. */
@Composable
internal fun RadarLaunchHost(enabled: Boolean, nativeReady: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val motionEnabled = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }.getOrDefault(true)
    }
    val launchVisible = enabled && motionEnabled
    var contentReady by remember { mutableStateOf(!launchVisible) }
    var animationReady by remember { mutableStateOf(false) }
    var contentDrawn by remember { mutableStateOf(!launchVisible) }
    val theme = remember(context) { context.getSharedPreferences("appearance", 0)
        .getString("mode", if (Build.VERSION.SDK_INT >= 28) "system" else "light") }
    val dark = theme == "dark" || theme == "system" && Build.VERSION.SDK_INT >= 28 && isSystemInDarkTheme()
    LaunchedEffect(view, nativeReady) {
        if (launchVisible && nativeReady) {
            awaitLaunchDraw(view)
            animationReady = true
        }
    }
    LaunchedEffect(view, contentReady) {
        if (launchVisible && contentReady) {
            awaitLaunchDraw(view)
            contentDrawn = true
        }
    }
    RadarLiquidTheme(dark) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (contentReady) content()
            LaunchAnimation(launchVisible, animationReady, contentDrawn, onEntranceFinished = { contentReady = true })
        }
    }
}

/** Frame-clock callbacks run before drawing; release heavy composition after an actual draw instead. */
private suspend fun awaitLaunchDraw(view: View) = suspendCancellableCoroutine<Unit> { continuation ->
    val observer = view.viewTreeObserver
    lateinit var listener: ViewTreeObserver.OnDrawListener
    var posted = false
    fun removeListener() {
        val current = if (observer.isAlive) observer else view.viewTreeObserver
        if (current.isAlive) current.removeOnDrawListener(listener)
    }
    val release = Runnable {
        removeListener()
        if (continuation.isActive) continuation.resume(Unit)
    }
    listener = ViewTreeObserver.OnDrawListener {
        // Removal is illegal inside onDraw; post also lets this draw finish before app creation.
        if (!posted) { posted = true; view.post(release) }
    }
    observer.addOnDrawListener(listener)
    continuation.invokeOnCancellation { view.post { view.removeCallbacks(release); removeListener() } }
    view.invalidate()
}

/** Vector-only entrance; never waits for data/network or adds a fixed pre-initialization delay. */
@Composable
internal fun LaunchAnimation(enabled: Boolean, start: Boolean, contentDrawn: Boolean, onEntranceFinished: () -> Unit) {
    if (!enabled) return
    var visible by remember { mutableStateOf(enabled) }
    val scale = remember { Animatable(0.82f) }
    val entranceFinished by rememberUpdatedState(onEntranceFinished)
    LaunchedEffect(start) {
        if (start) {
            // Build stores/forms only after the entrance finishes, keeping its frames lightweight.
            scale.animateTo(1f, tween(360))
            entranceFinished()
        }
    }
    LaunchedEffect(contentDrawn) {
        if (contentDrawn) visible = false
    }
    AnimatedVisibility(visible, exit = fadeOut(tween(180))) {
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            val shortSide = minOf(maxWidth, maxHeight)
            val logoSize = (shortSide * 0.46f).coerceIn(160.dp, 240.dp).coerceAtMost(shortSide * 0.8f)
            // A tightly cropped vector keeps the visible circle large without adaptive-icon padding.
            // Never pass an adaptive launcher wrapper to Compose painterResource.
            Image(painterResource(R.drawable.ic_brand_mark), "購物雷達", Modifier.size(logoSize).scale(scale.value))
        }
    }
}
