package com.example.shopradar

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun AdaptiveCardGrid(
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(top = 12.dp, bottom = 24.dp),
    photoGrid: Boolean = false,
    fixedColumns: Int? = null,
    content: LazyGridScope.() -> Unit
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        val contentWidth = maxWidth.coerceAtMost(RadarLayoutPolicy.CONTENT_MAX_WIDTH.dp)
        LazyVerticalGrid(
            columns = GridCells.Fixed(fixedColumns?.coerceIn(1, 2) ?: if (photoGrid) RadarLayoutPolicy.photoColumns(contentWidth.value, fontScale)
                else RadarLayoutPolicy.cardColumns(contentWidth.value, fontScale)),
            modifier = Modifier.widthIn(max = RadarLayoutPolicy.CONTENT_MAX_WIDTH.dp).fillMaxSize(),
            state = state,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun RadarNavigation(selected: Int, onSelect: (Int) -> Unit) {
    val buttons: @Composable () -> Unit = {
        listOf("搜尋", "訂閱", "競拍").forEachIndexed { i, name ->
            val active = selected == i
            val scale by animateFloatAsState(if (active) 1.08f else 1f, tween(220), label = "tabScale")
            val background by animateColorAsState(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, tween(220), label = "tabBackground")
            val foregroundColor by animateColorAsState(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, tween(220), label = "tabForeground")
            FilledIconToggleButton(checked = active, onCheckedChange = { onSelect(i) },
                modifier = Modifier.size(48.dp).graphicsLayer { scaleX = scale; scaleY = scale },
                colors = IconButtonDefaults.filledIconToggleButtonColors(
                    containerColor = background, contentColor = foregroundColor,
                    checkedContainerColor = background, checkedContentColor = foregroundColor)) {
                Icon(when (i) { 0 -> Icons.Default.Search; 1 -> Icons.Default.Store; else -> Icons.Default.Timer }, name)
            }
        }
    }
    LiquidGlassSurface(
        Modifier.widthIn(max = 280.dp).fillMaxWidth(0.8f),
        shape = RoundedCornerShape(30.dp)
    ) {
        Row(Modifier.padding(6.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) { buttons() }
    }
}

private data class ShopReadState(
    val subscriptionKey: String,
    val checkpointMillis: Long,
    val seen: Set<String> = emptySet(),
    val page: Int = 1,
    val products: List<Product> = emptyList()
)

private data class YahooConsentRetry(val request: SearchCapture, val shopKey: String,
    val preview: Boolean, val viewOnly: Boolean)

// Lightweight, glass-inspired edges on solid surfaces. Avoid nested translucent
// layers and live blur so cards share a consistent background on every device.
private val LiquidBackdrop = Color(0xFFF3F6FC)
private val RadarLightColors = lightColorScheme(
    primary = Color(0xFF3868D9), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF), onPrimaryContainer = Color(0xFF102A65),
    secondary = Color(0xFF396F70), background = LiquidBackdrop,
    surface = Color.White, surfaceVariant = Color(0xFFE9EEF8),
    onBackground = Color(0xFF1C2942), onSurface = Color(0xFF1C2942),
    onSurfaceVariant = Color(0xFF59677F),
    outline = Color(0xFF9AA7BE)
)

private val RadarDarkColors = darkColorScheme(
    primary = Color(0xFFAEC6FF), onPrimary = Color(0xFF102E68),
    primaryContainer = Color(0xFF26477E), onPrimaryContainer = Color(0xFFDCE7FF),
    background = Color(0xFF10141C), surface = Color(0xFF191F2A),
    surfaceVariant = Color(0xFF252E3D), onBackground = Color(0xFFE4EAF4),
    onSurface = Color(0xFFE4EAF4), onSurfaceVariant = Color(0xFFB9C4D5),
    outline = Color(0xFF65748B)
)

@Composable
internal fun RadarLiquidTheme(dark: Boolean, content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (dark) RadarDarkColors else RadarLightColors, content = content)

@Composable
private fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
    content: @Composable BoxScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        shadowElevation = 2.dp,
        tonalElevation = 0.dp
    ) {
        Box(content = content)
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val bounds = remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    CompositionLocalProvider(LocalCardMenuBounds provides bounds) {
    Surface(
        modifier = modifier.onGloballyPositioned { bounds.value = it.boundsInWindow() },
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        content = content
    )
    }
}

/** Grid items lay out sibling roots in the same space. Keep heading/empty state in one Column. */
@Composable
private fun SubscriptionSection(title: String, emptyMessage: String?, actions: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (actions == null) Text(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
        else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            actions()
        }
        if (emptyMessage != null) GlassCard(Modifier.fillMaxWidth()) {
            Text(emptyMessage, Modifier.fillMaxWidth().padding(12.dp))
        }
    }
}

/** One consistent, blocking status surface for public marketplace reads. */
@Composable
private fun SearchProgressDialog(detail: String, title: String = "正在搜尋", onStop: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStop,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(title) },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Text(detail)
            }
        },
        confirmButton = { TextButton(onClick = onStop) { Text("停止") } }
    )
}

class MainActivity : ComponentActivity() {
    private var launchWindowDismissed by mutableStateOf(Build.VERSION.SDK_INT < 31)
    private var openAuctions by mutableIntStateOf(0)
    private var foreground by mutableStateOf(false)
    private lateinit var screenBackBinding: ScreenBackBinding
    private var sharedSubscription by mutableStateOf<SharedSubscriptionRequest?>(null)
    private var shareError by mutableStateOf<String?>(null)
    private var subscriptionNotification by mutableStateOf<SubscriptionNotificationRoute?>(null)
    private fun receiveSubscriptionNotification(intent: Intent) {
        if (intent.action != SubscriptionNotificationRoute.ACTION) return
        subscriptionNotification = SubscriptionNotificationRoute.parse(
            intent.getStringExtra(SubscriptionNotificationRoute.EXTRA_KIND), intent.getStringExtra(SubscriptionNotificationRoute.EXTRA_URL))
        intent.removeExtra(SubscriptionNotificationRoute.EXTRA_KIND)
        intent.removeExtra(SubscriptionNotificationRoute.EXTRA_URL)
        intent.action = Intent.ACTION_MAIN
        intent.data = null
    }
    private fun receiveShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val link = SubscriptionShareInput.parse(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
        if (link == null) shareError = "請分享一個支援平台的 HTTPS 商品或店家連結。"
        else sharedSubscription = SharedSubscriptionRequest(java.util.UUID.randomUUID().toString(), link)
        // A consumed share must not be imported again if Android recreates the activity.
        intent.removeExtra(Intent.EXTRA_TEXT)
        intent.action = Intent.ACTION_MAIN
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Register on the actual Activity dispatcher before composing any screen.
        val screenBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                android.util.Log.d("RadarBack", "activity_back_received")
                if (!screenBackBinding.dispatch()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
        screenBackBinding = ScreenBackBinding { screenBackCallback.isEnabled = it }
        onBackPressedDispatcher.addCallback(this, screenBackCallback)
        // MainActivity uses the documented predictive-animation opt-out for
        // Android 15/OEM compatibility. ComponentActivity owns the dispatcher;
        // do not manually attach a second platform bridge or consume edge touches.
        // Only Compose animates the brand. Do not play it behind the native starting window.
        window.setBackgroundDrawableResource(R.color.launch_background)
        if (Build.VERSION.SDK_INT >= 31) {
            splashScreen.setOnExitAnimationListener { splash ->
                splash.remove()
                launchWindowDismissed = true
            }
        }
        openAuctions = if (intent.getBooleanExtra("openAuctions", false)) {
            intent.removeExtra("openAuctions")
            1
        } else 0
        receiveShare(intent)
        receiveSubscriptionNotification(intent)
        setContent { CompositionLocalProvider(LocalScreenBackBinding provides screenBackBinding) {
            RadarLaunchHost(savedInstanceState == null, nativeReady = launchWindowDismissed) { RadarApp(openAuctions, foreground, sharedSubscription,
            onShareConsumed = { sharedSubscription = null }, shareError = shareError, onShareErrorConsumed = { shareError = null },
            subscriptionNotification = subscriptionNotification, onSubscriptionNotificationConsumed = { subscriptionNotification = null },
            onMoveToBackground = { moveTaskToBack(true) }) } } }
    }
    // Background initialization is deferred until after the search page's first frame.
    override fun onStart() { super.onStart(); foreground = true }
    override fun onStop() { AdultVisibility.lock(); foreground = false; super.onStop() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.getBooleanExtra("openAuctions", false)) {
            intent.removeExtra("openAuctions")
            openAuctions++
        }
        receiveShare(intent)
        receiveSubscriptionNotification(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun RadarApp(openAuctions: Int, foreground: Boolean,
    sharedSubscription: SharedSubscriptionRequest?, onShareConsumed: () -> Unit,
    shareError: String?, onShareErrorConsumed: () -> Unit,
    subscriptionNotification: SubscriptionNotificationRoute?, onSubscriptionNotificationConsumed: () -> Unit,
    onMoveToBackground: () -> Unit) {
    val context = LocalContext.current
    val appearancePrefs = remember { context.getSharedPreferences("appearance", 0) }
    var themeMode by remember { mutableStateOf(appearancePrefs.getString("mode", if (Build.VERSION.SDK_INT >= 28) "system" else "light") ?: "light") }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var showBackgroundDeliverySettings by remember { mutableStateOf(false) }
    var showDebugNotificationTest by remember { mutableStateOf(false) }
    val dark = themeMode == "dark" || (themeMode == "system" && Build.VERSION.SDK_INT >= 28 && isSystemInDarkTheme())
    DisposableEffect(context, dark) {
        (context as? android.app.Activity)?.window?.let { window ->
            val colors = if (dark) RadarDarkColors else RadarLightColors
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        onDispose { }
    }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val uriHandler = LocalUriHandler.current
    // Search is deliberately the first rendered screen. The foreground auction
    // monitor is lifecycle-owned below; opening/switching tabs does not create extra polls.
    val pager = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val pageFling = PagerDefaults.flingBehavior(pager)
    val navigationScope = rememberCoroutineScope()
    val tab = pager.currentPage
    LaunchedEffect(openAuctions) { if (openAuctions > 0) pager.animateScrollToPage(2) }
    var warning by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(shareError) {
        if (shareError != null) { warning = shareError; onShareErrorConsumed() }
    }
    var captureDiagnostic by remember { mutableStateOf<Pair<String, String>?>(null) }
    var yahooConsentRetry by remember { mutableStateOf<YahooConsentRetry?>(null) }
    var showYahooConsent by remember { mutableStateOf(false) }
    var shopeeSearchRetry by remember { mutableStateOf<SearchCapture?>(null) }
    var shopeeRecoveryLaunching by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var shopeeSignedIn by remember { mutableStateOf(false) }
    var showShopeeLogoutDialog by remember { mutableStateOf(false) }
    var shopeeLoggingOut by remember { mutableStateOf(false) }
    LaunchedEffect(foreground, menu) {
        if (foreground && menu) {
            // This status is only displayed in the menu. Do not start WebView during the launch animation.
            shopeeSignedIn = ShopeeWebConfiguration.canSearch()
        }
    }
    LaunchedEffect(pager.targetPage) {
        // A text field's selection handles and keyboard must not follow a tab change.
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        menu = false
    }
    val auctionStore = remember { AuctionStore(context) }
    var auctionRefreshing by remember { mutableStateOf(false) }
    var showUsernameDialog by remember { mutableStateOf(false) }
    // These drafts are populated when the settings dialog opens, not during startup.
    var usernameDraft by rememberSaveable { mutableStateOf("") }
    var rutenUsernameDraft by rememberSaveable { mutableStateOf("") }
    var showBackgroundStatus by remember { mutableStateOf(false) }
    var selectedLabel by rememberSaveable { mutableStateOf(Market.CAROUSELL.label) }
    val selected = Market.searchSelection(selectedLabel).label
    var yahooMainCategory by rememberSaveable { mutableStateOf("") }
    var rutenMainCategory by rememberSaveable { mutableStateOf("") }
    var rutenCategory by rememberSaveable { mutableStateOf("") }
    var appliedRutenCategory by remember { mutableStateOf("") }
    var yahooSubcategory by rememberSaveable { mutableStateOf("") }
    var yahooState by rememberSaveable { mutableStateOf("0") }
    var yahooSort by rememberSaveable { mutableStateOf("") }
    var facebookSort by rememberSaveable { mutableStateOf(FacebookFilters.DEFAULT_SORT) }
    var appliedFacebookSort by remember { mutableStateOf(FacebookFilters.DEFAULT_SORT) }
    var boboMall by rememberSaveable { mutableStateOf("0") }
    var boboCategory by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var min by rememberSaveable { mutableStateOf("") }
    var max by rememberSaveable { mutableStateOf("") }
    var searchPage by rememberSaveable { mutableIntStateOf(1) }
    var shopeeSort by rememberSaveable { mutableStateOf("relevancy") }
    var shopeeMall by rememberSaveable { mutableStateOf(false) }
    var shopeePreferred by rememberSaveable { mutableStateOf(false) }
    var shopeeCondition by rememberSaveable { mutableStateOf("") }
    var carousellSort by rememberSaveable { mutableStateOf("近期") }
    var carousellCategory by rememberSaveable { mutableStateOf("") }
    var carousellMainCategory by rememberSaveable { mutableStateOf("") }
    val searchHistoryStore = remember(context) { SearchHistoryStore(context) }
    var searchHistoryEntries by remember(searchHistoryStore) { mutableStateOf<List<SearchHistoryEntry>>(emptyList()) }
    var searchHistoryLoaded by remember(searchHistoryStore) { mutableStateOf(false) }
    fun ensureSearchHistoryLoaded() {
        if (!searchHistoryLoaded) {
            searchHistoryEntries = searchHistoryStore.load()
            searchHistoryLoaded = true
        }
    }
    var deleteSearchHistory by remember { mutableStateOf<SearchHistoryEntry?>(null) }
    var clearSearchHistory by remember { mutableStateOf(false) }
    var searchHistoryExpanded by remember { mutableStateOf(false) }
    val historyMarket = Market.searchSelection(selected)
    LaunchedEffect(historyMarket, foreground, pager.targetPage) { searchHistoryExpanded = false }
    fun saveSearchHistory() {
        ensureSearchHistoryLoaded()
        val criteria = when (historyMarket) {
            Market.SHOPEE -> SearchHistoryCriteria(historyMarket, query, min, max, sort = shopeeSort,
                condition = shopeeCondition, mall = shopeeMall, preferred = shopeePreferred)
            Market.CAROUSELL -> SearchHistoryCriteria(historyMarket, query, min, max, sort = carousellSort,
                mainCategory = carousellMainCategory, category = carousellCategory)
            Market.YAHOO -> SearchHistoryCriteria(historyMarket, query, min, max, sort = yahooSort,
                yahooState = yahooState, mainCategory = yahooMainCategory, category = yahooSubcategory)
            Market.RUTEN -> SearchHistoryCriteria(historyMarket, query, min, max,
                mainCategory = rutenMainCategory, category = rutenCategory)
            Market.FACEBOOK -> SearchHistoryCriteria(historyMarket, query, min, max, sort = facebookSort)
            Market.BBOBO -> SearchHistoryCriteria(historyMarket, query, min, max, boboMall = boboMall, category = boboCategory)
            Market.IOPEN -> SearchHistoryCriteria(historyMarket, query, min, max)
        }
        searchHistoryEntries = SearchHistory.record(searchHistoryEntries, criteria)
        searchHistoryStore.save(searchHistoryEntries)
    }
    fun restoreSearchHistory(entry: SearchHistoryEntry) {
        val criteria = entry.criteria
        if (!criteria.market.supportsSearch || criteria.market != historyMarket || !criteria.visible(AdultVisibility.unlocked)) return
        focusManager.clearFocus(force = true); keyboard?.hide()
        query = criteria.keyword; min = criteria.min; max = criteria.max
        when (historyMarket) {
            Market.SHOPEE -> {
                shopeeSort = criteria.sort; shopeeCondition = criteria.condition
                shopeeMall = criteria.mall; shopeePreferred = criteria.preferred
            }
            Market.CAROUSELL -> {
                carousellSort = criteria.sort
                carousellMainCategory = criteria.mainCategory; carousellCategory = criteria.category
            }
            Market.YAHOO -> {
                yahooSort = criteria.sort; yahooState = criteria.yahooState
                yahooMainCategory = criteria.mainCategory; yahooSubcategory = criteria.category
            }
            Market.RUTEN -> { rutenMainCategory = criteria.mainCategory; rutenCategory = criteria.category }
            Market.FACEBOOK -> facebookSort = criteria.sort
            Market.BBOBO -> { boboMall = criteria.boboMall; boboCategory = criteria.category }
            Market.IOPEN -> Unit
        }
    }
    LaunchedEffect(AdultVisibility.unlocked) {
        if (!AdultVisibility.unlocked) {
            if (deleteSearchHistory?.criteria?.visible(false) == false) deleteSearchHistory = null
            warning = null
            if (AdultVisibility.adultCategory(Market.RUTEN, rutenCategory)) { rutenMainCategory = ""; rutenCategory = "" }
            if (AdultVisibility.adultCategory(Market.YAHOO, yahooSubcategory)) { yahooMainCategory = ""; yahooSubcategory = "" }
            if (AdultVisibility.adultText(query)) query = ""
        }
    }
    var resultNote by rememberSaveable { mutableStateOf("") }
    // Form fields are only drafts.  A search takes a snapshot below, so editing a
    // field never changes the results which are already on screen.
    var appliedQuery by remember { mutableStateOf("") }
    var appliedMin by remember { mutableStateOf("") }
    var appliedMax by remember { mutableStateOf("") }
    var appliedPage by remember { mutableIntStateOf(1) }
    var appliedYahooSubcategory by remember { mutableStateOf("") }
    var appliedYahooState by remember { mutableStateOf("0") }
    var appliedYahooSort by remember { mutableStateOf("") }
    var appliedShopeeSort by remember { mutableStateOf("relevancy") }
    var appliedShopeeMall by remember { mutableStateOf(false) }
    var appliedShopeePreferred by remember { mutableStateOf(false) }
    var appliedShopeeCondition by remember { mutableStateOf("") }
    var appliedBoboMall by remember { mutableStateOf("0") }
    var appliedBoboCategory by remember { mutableStateOf("") }
    var appliedCarousellSortBy by remember { mutableStateOf("3") }
    var appliedCarousellCategory by remember { mutableStateOf("") }
    var hasCompletedSearch by remember { mutableStateOf(false) }
    var showSearchResults by remember { mutableStateOf(false) }
    val products = remember { mutableStateListOf<Product>() }
    // Keep each platform's findings off-screen until the whole requested search ends.
    // This prevents a moving/partial list while pages are still being read.
    val stagedProducts = remember { mutableStateListOf<Product>() }
    var previousSearchPageUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
    var searchPageCount by remember { mutableStateOf<Int?>(null) }
    // In-flight work is not restorable: an obsolete platform must never be dispatched.
    var queue by remember { mutableStateOf(arrayListOf<String>()) }
    var pendingShop by rememberSaveable { mutableStateOf("") }
    var pendingShopPreview by remember { mutableStateOf(false) }
    // Store lookups use the same invisible WebView as product search.  Keeping this
    // state independent means the subscription screen never opens the old
    // intermediate browser activity while a shop is being identified.
    var subscriptionShopCapture by remember { mutableStateOf<SearchCapture?>(null) }
    var subscriptionShopToken by remember { mutableIntStateOf(0) }
    var subscriptionShopSearching by remember { mutableStateOf(false) }
    // Store pages are newest-first. This state exists only while a Ruten
    // subscription check is reading its bounded continuation pages.
    var shopRead by remember { mutableStateOf<ShopReadState?>(null) }
    var newItemsPaging by remember { mutableStateOf<SubscriptionNewItemsPaging?>(null) }
    var showNewItemsPage by remember { mutableStateOf(false) }
    var nextNewItemsCapture by remember { mutableStateOf<SearchCapture?>(null) }
    var newItemsPageStartedAt by remember { mutableLongStateOf(0) }
    var currentMarket by rememberSaveable { mutableStateOf("") }
    var dispatch by remember { mutableIntStateOf(0) }
    var fetching by remember { mutableStateOf(false) }
    var searchToken by remember { mutableIntStateOf(0) }
    var resultRevision by remember { mutableIntStateOf(0) }
    var searchPageComplete by remember { mutableStateOf(false) }
    var searchFeedPaused by remember { mutableStateOf(false) }
    var backgroundCapture by remember { mutableStateOf<SearchCapture?>(null) }
    // Retain a selected category URL so its manual "more" action stays in the
    // category flow rather than rebuilding a keyword search URL.
    var activeCarousellUrl by remember { mutableStateOf("") }
    var carousellMoreBatch by remember { mutableIntStateOf(0) }
    var productsBeforeMore by remember { mutableStateOf<Set<String>>(emptySet()) }
    var timedOut by remember { mutableStateOf(false) }
    var browserFallback by remember { mutableStateOf(false) }
    var subscriptionProducts by remember { mutableStateOf<List<Product>>(emptyList()) }
    var subscriptionNewItemUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
    var subscriptionPageRequest by remember { mutableIntStateOf(0) }
    var subscriptionHistoryShop by remember { mutableStateOf<String?>(null) }
    var showSubscriptionProducts by remember { mutableStateOf(false) }
    var productPageTitle by remember { mutableStateOf("新商品") }
    var previewOnlyProducts by remember { mutableStateOf(false) }
    val shopPreviewStaged = remember { mutableStateListOf<Product>() }
    var shopPreviewNote by remember { mutableStateOf("") }
    var previewShopName by remember { mutableStateOf("") }
    var previewShopUrl by remember { mutableStateOf("") }
    var previewShopMarket by remember { mutableStateOf<Market?>(null) }
    var previewShopProducts by remember { mutableStateOf<List<Product>>(emptyList()) }
    var showShopDetails by remember { mutableStateOf(false) }

    val shopsPrefs = remember { context.getSharedPreferences("shop_subscriptions_v2", 0) }
    var shopsVersion by remember { mutableIntStateOf(0) }
    var startupSubscriptionNotice by remember { mutableStateOf<List<ProductSubscriptionAlert>?>(null) }
    var subscriptionRefreshing by remember { mutableStateOf(false) }
    var subscriptionRefreshJob by remember { mutableStateOf<Job?>(null) }
    var subscriptionRefreshId by remember { mutableIntStateOf(0) }
    fun refreshSubscriptions() {
        if (subscriptionRefreshing || subscriptionShopSearching || fetching) return
        if (!RefreshNetwork.connected(context)) { warning = "網路未連線，請連線後再刷新。"; return }
        val targets = RefreshRunner.subscriptions(context)
        if (targets.isEmpty()) return
        val requestId = ++subscriptionRefreshId
        subscriptionRefreshing = true
        subscriptionRefreshJob = navigationScope.launch {
            val alerts = mutableListOf<ProductSubscriptionAlert>()
            val errors = mutableListOf<String>()
            try {
                targets.forEach { target ->
                    val result = RefreshRunner.refresh(context, target) { subscriptionRefreshId == requestId }
                    alerts.addAll(result.alerts)
                    result.error?.let(errors::add)
                }
                if (subscriptionRefreshId == requestId) {
                    shopsVersion++
                    if (alerts.isNotEmpty()) startupSubscriptionNotice = alerts.distinctBy { it.shop + it.title }
                    if (errors.isNotEmpty()) warning = "${errors.size} 筆刷新未完成；已保留原有資料。\n${errors.distinct().take(3).joinToString("\n")}"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (subscriptionRefreshId == requestId) warning = "刷新未完成，已保留原有資料，請重試。" }
            finally { if (subscriptionRefreshId == requestId) { subscriptionRefreshing = false; subscriptionRefreshJob = null } }
        }
    }
    fun refreshAuctions() {
        if (auctionRefreshing) return
        if (!RefreshNetwork.connected(context)) { warning = "網路未連線，請連線後再刷新。"; return }
        val targets = auctionStore.list().map { RefreshTarget(RefreshKind.AUCTION, it.optString("url")) }
        if (targets.isEmpty()) return
        auctionRefreshing = true
        // Owned by the app, not a lazy pager page: switching tabs must not cancel a manual refresh.
        navigationScope.launch {
            val errors = mutableListOf<String>()
            try {
                targets.forEach { target -> RefreshRunner.refresh(context, target).error?.let(errors::add) }
                if (errors.isNotEmpty()) warning = "${errors.size} 筆競標刷新未完成。\n${errors.distinct().take(3).joinToString("\n")}"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { warning = "競標刷新未完成，已保留原有資料。" }
            finally { auctionRefreshing = false }
        }
    }
    fun presentFinishedResults() {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        showSearchResults = true
    }
    fun publishSearchProgress(force: Boolean = false) {
        val market = Market.searchSelection(selected)
        // Numbered Bobo results wait for the seven-second preparation and date reads.
        if (market == Market.BBOBO && fetching) return
        val eligible = stagedProducts.distinctBy { it.url }.filter { AdultVisibility.visible(it) &&
            it.price >= (appliedMin.toLongOrNull() ?: 0) && it.price <= (appliedMax.toLongOrNull() ?: Long.MAX_VALUE) }
        val count = if (SearchPageLoading.infinite(market) && !force)
            maxOf(products.size, SearchPageLoading.batchCount(eligible.size, market)) else eligible.size
        val snapshot = eligible.take(count)
        val visibleCount = snapshot.count { AdultVisibility.visible(it) &&
            it.price >= (appliedMin.toLongOrNull() ?: 0) && it.price <= (appliedMax.toLongOrNull() ?: Long.MAX_VALUE) }
        if (force && visibleCount > 0 || visibleCount >= SearchPageLoading.firstBatch(market) || showSearchResults && products.isNotEmpty()) {
            if (products.toList() != snapshot) { products.clear(); products.addAll(snapshot) }
            if (!showSearchResults) presentFinishedResults()
        }
    }
    fun stopSearch() {
        // Invalidate any late WebView/Ruten response before dismissing the dialog.
        searchToken++
        queue = arrayListOf()
        backgroundCapture = null
        stagedProducts.clear()
        fetching = false
        searchFeedPaused = false
        timedOut = false
        hasCompletedSearch = products.isNotEmpty()
        resultNote = if (products.isEmpty()) "已停止搜尋。" else "已停止讀取更多結果；保留目前 ${products.count { AdultVisibility.visible(it) }} 件商品。"
    }
    fun selectResultsPage(page: Int, keepProducts: Boolean = false) {
        if (page !in 1..(searchPageCount?.coerceIn(1, 500) ?: appliedPage)) return
        val market = Market.values().firstOrNull { it.label == selected }
        if (market == null || !market.supportsSearch) return
        if (SearchPageLoading.infinite(market) && page != appliedPage) return
        if (fetching) stopSearch()
        searchFeedPaused = false
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        searchToken++
        if (SearchPageLoading.infinite(market)) previousSearchPageUrls = emptySet()
        else if (page != appliedPage) previousSearchPageUrls = products.map { it.url }.toSet()
        else if (searchPageComplete) previousSearchPageUrls = emptySet()
        appliedPage = page; searchPage = page
        if (!keepProducts) resultRevision++
        if (!keepProducts) products.clear()
        stagedProducts.clear(); stagedProducts.addAll(products); backgroundCapture = null
        carousellMoreBatch = 0; productsBeforeMore = emptySet()
        hasCompletedSearch = false; showSearchResults = true; fetching = true; timedOut = false
        resultNote = "正在搜尋第 $page 頁商品，請稍候…"
        queue = arrayListOf(market.name)
        dispatch++
    }
    fun startSubscriptionShopCapture(url: String, market: Market, preview: Boolean, viewOnly: Boolean = false) {
        newItemsPaging = null; showNewItemsPage = false; nextNewItemsCapture = null
        previewOnlyProducts = viewOnly;
        shopPreviewStaged.clear(); shopPreviewNote = "正在讀取店鋪商品…"
        if (viewOnly) { subscriptionProducts = emptyList(); subscriptionHistoryShop = null; showSubscriptionProducts = false }
        subscriptionShopToken++
        val canonicalUrl = runCatching { market.shopPage(url) }.getOrElse {
            warning = it.message ?: "露天使用者名稱格式不正確。"; return
        }
        pendingShop = if (preview) canonicalUrl else url
        pendingShopPreview = preview
        currentMarket = market.label
        subscriptionShopSearching = true
        val existing = shopSubscriptionRecord(pendingShop, shopsPrefs.getString(pendingShop, null))
        shopRead = if (market != Market.FACEBOOK && !preview) ShopReadState(
            pendingShop, existing.optLong("listingCheckpointAt", existing.optLong("rutenCheckpointAt")),
            existing.optJSONArray("seen")?.let { list -> (0 until list.length()).map { list.optString(it) }.toSet() }.orEmpty()
        ) else null
        val state = shopRead
        if (state != null && (state.checkpointMillis > 0 || state.seen.isNotEmpty()))
            newItemsPaging = SubscriptionNewItemsPaging(state.checkpointMillis, state.seen)
        newItemsPageStartedAt = android.os.SystemClock.elapsedRealtime()
        subscriptionShopCapture = SearchCapture(market, canonicalUrl, subscriptionShopToken,
            shopPreview = preview,
            subscriptionCheckpoint = state?.checkpointMillis ?: 0, subscriptionSeen = state?.seen.orEmpty(),
            scanSubscription = newItemsPaging != null)
    }
    fun saveNewItemsProgress(complete: Boolean) {
        val state = newItemsPaging ?: return
        if (pendingShop.isBlank() || !shopsPrefs.contains(pendingShop)) return
        synchronized(SubscriptionMutations.lock) {
            val raw = shopsPrefs.getString(pendingShop, null) ?: return
            val shop = shopSubscriptionRecord(pendingShop, raw)
            if (shop.optLong("listingCheckpointAt", shop.optLong("rutenCheckpointAt")) != state.checkpoint) return
            ShopNewItems.merge(shop, state.collected)
            ShopSubscriptionCover.update(shop, state.collected)
            state.collected.firstOrNull { it.shop.isNotBlank() }?.let { shop.put("name", it.shop) }
            if (complete) {
                shop.put("seen", JSONArray((state.seen + state.collected.map { it.url }).toList()))
                    .put("listingCheckpointAt", maxOf(state.checkpoint, ShopCheckpoint.newest(state.collected)))
                    .put("checkedAt", System.currentTimeMillis())
            }
            shopsPrefs.edit().putString(pendingShop, shop.toString()).commit()
        }
        shopsVersion++
    }
    fun offerYahooConsent(capture: SearchCapture, message: String): Boolean {
        if (capture.market != Market.YAHOO || message != YahooConsentPolicy.MESSAGE) return false
        yahooConsentRetry = YahooConsentRetry(capture, if (capture.quickSearch) "" else pendingShop.ifBlank { capture.url },
            pendingShopPreview, previewOnlyProducts)
        showYahooConsent = true
        warning = null; captureDiagnostic = null
        return true
    }
    fun failSubscriptionShopCapture(capture: SearchCapture, message: String) {
        if (subscriptionShopCapture != capture) return
        val consentRequired = offerYahooConsent(capture, message)
        if (newItemsPaging != null) {
            newItemsPaging = newItemsPaging!!.fail(message)
            saveNewItemsProgress(complete = false)
            nextNewItemsCapture = capture
            subscriptionShopCapture = null; subscriptionShopSearching = false
            if (newItemsPaging!!.hasNew) showNewItemsPage = true else if (!consentRequired) warning = message
            return
        }
        subscriptionShopCapture = null
        subscriptionShopSearching = false
        pendingShopPreview = false
        pendingShop = ""
        shopRead = null
        if (!consentRequired) warning = "${capture.market.label}$message"
    }
    fun finishSubscriptionShopCapture(capture: SearchCapture, incoming: List<Product>) {
        if (subscriptionShopCapture != capture) return
        if (capture.token != subscriptionShopToken) return
        newItemsPaging?.let { original ->
            var state = original.observe(incoming)
            // Yahoo emits an empty success only for the official list's explicit empty-state.
            if (capture.market == Market.YAHOO && incoming.isEmpty()) state = state.copy(reachedOld = true)
            newItemsPaging = state
            if (state.reachedOld) {
                state = state.publish(); newItemsPaging = state
                saveNewItemsProgress(complete = true)
                subscriptionShopCapture = null; subscriptionShopSearching = false
                nextNewItemsCapture = null; shopRead = null
                if (state.hasNew) showNewItemsPage = true else warning = "沒有新商品。"
                return
            }
            val read = shopRead
            if (state.awaitingTime || incoming.isEmpty() || read == null || read.page >= 50 ||
                incoming.none { item -> read.products.none { it.url == item.url } }) {
                failSubscriptionShopCapture(capture, "尚未確認上架時間或讀到舊商品；已保留新品及原有時間基準，請重試。")
                return
            }
            val nextRead = read.copy(page = read.page + 1, products = state.collected)
            shopRead = nextRead
            val nextUrl = runCatching { capture.market.shopPage(read.subscriptionKey, nextRead.page) }.getOrElse {
                failSubscriptionShopCapture(capture, "無法讀取下一頁；已保留新品及原有時間基準，請重試。")
                return
            }
            val next = SearchCapture(capture.market, nextUrl, subscriptionShopToken,
                loadMore = capture.market == Market.CAROUSELL, moreClicks = nextRead.page - 1,
                subscriptionCheckpoint = state.checkpoint, subscriptionSeen = state.seen, scanSubscription = true)
            nextNewItemsCapture = next
            // Before the five-second publication, keep filling this UI page. After
            // publication, source-page continuation waits for the next-page button.
            if (state.collecting) { subscriptionShopCapture = next; subscriptionShopSearching = true }
            else { subscriptionShopCapture = null; subscriptionShopSearching = false }
            return
        }
        var parsed = incoming.distinctBy { it.url }
        val subscriptionKey = pendingShop
        if (capture.token != subscriptionShopToken) return
        val rutenState = shopRead
        if (rutenState != null) {
            val pageScan = ShopCheckpoint.scan(parsed, rutenState.checkpointMillis, rutenState.seen)
            val all = (rutenState.products + pageScan.products).distinctBy { it.url }
            val more = !pageScan.reachedOld && parsed.isNotEmpty() && (rutenState.checkpointMillis > 0 || rutenState.seen.isNotEmpty())
            if (more && (rutenState.page >= 50 || pageScan.products.none { p -> rutenState.products.none { it.url == p.url } })) {
                failSubscriptionShopCapture(capture, "尚未讀到舊商品；保留上次時間基準，請重試。"); return
            }
            if (more) {
                val next = rutenState.copy(page = rutenState.page + 1, products = all)
                shopRead = next
                subscriptionShopCapture = SearchCapture(capture.market,
                    capture.market.shopPage(rutenState.subscriptionKey, next.page), subscriptionShopToken,
                    loadMore = capture.market == Market.CAROUSELL, moreClicks = next.page - 1)
                return
            }
            shopRead = null
            parsed = all.ifEmpty { parsed.take(1) }
        }
        subscriptionShopCapture = null
        subscriptionShopSearching = false
        val isPreview = pendingShopPreview
        pendingShopPreview = false
        pendingShop = ""
        if (isPreview) {
            shopPreviewNote = "已讀取 ${parsed.size} 件商品。"
            if (parsed.isNotEmpty()) {
                previewShopUrl = capture.url
                previewShopMarket = capture.market
                previewShopProducts = parsed
                previewShopName = parsed.map { it.shop.trim() }.firstOrNull { it.isNotBlank() } ?: "店家"
                if (previewOnlyProducts) {
                    subscriptionHistoryShop = null
                    subscriptionProducts = parsed; productPageTitle = "${previewShopName} 的商品"; showSubscriptionProducts = true
                } else showShopDetails = true
            } else {
                warning = "找不到可辨識的店家商品，請確認網址、登入狀態或稍後重試。"
            }
            return
        }

        if (parsed.isEmpty()) {
            warning = "店家頁讀取未完成；已保留上次商品基準。"
            return
        }
        val shop = shopSubscriptionRecord(subscriptionKey, shopsPrefs.getString(subscriptionKey, null))
        val old = shop.optJSONArray("seen")
        val seen = old?.let { (0 until it.length()).map { i -> it.getString(i) }.toSet() }.orEmpty()
        val rutenCheckpoint = shop.optLong("listingCheckpointAt", shop.optLong("rutenCheckpointAt"))
        val newItems = if (old == null) emptyList() else ShopCheckpoint.scan(parsed, rutenCheckpoint, seen).products.filter { ShopCheckpoint.isNew(it, rutenCheckpoint, seen) }
        val shopName = parsed.map { it.shop.trim() }.firstOrNull { it.isNotBlank() }
        shop.put("seen", JSONArray((seen + parsed.map { it.url }).toList())).put("checkedAt", System.currentTimeMillis())
            .put("newCount", newItems.size)
        if (shopName != null) shop.put("name", shopName)
        ShopSubscriptionCover.update(shop, parsed)
        shop.put("listingCheckpointAt", maxOf(rutenCheckpoint, ShopCheckpoint.newest(if (old == null) parsed else newItems)))
        // History is intentionally retained across app launches and checks.
        ShopNewItems.merge(shop, newItems)
        shopsPrefs.edit().putString(subscriptionKey, shop.toString()).apply(); shopsVersion++
        when {
            old == null -> warning = "已建立目前可見商品的比對基準，下次檢查才會標示新品。"
            newItems.isEmpty() -> warning = "沒有新商品。"
            else -> {
                subscriptionHistoryShop = subscriptionKey; subscriptionProducts = ShopNewItems.newestFirst(newItems)
                subscriptionNewItemUrls = newItems.map { it.url }.toSet()
                productPageTitle = ShopNewItems.pageTitle(shop.optString("name"), newItems); showSubscriptionProducts = true
            }
        }
    }
    fun observeSubscriptionProgress(capture: SearchCapture, found: List<Product>) {
        if (subscriptionShopCapture != capture || capture.token != subscriptionShopToken) return
        synchronized(SubscriptionMutations.lock) {
            if (shopsPrefs.contains(pendingShop)) {
                val saved = shopSubscriptionRecord(pendingShop, shopsPrefs.getString(pendingShop, null))
                if (ShopSubscriptionCover.update(saved, found)) {
                    shopsPrefs.edit().putString(pendingShop, saved.toString()).commit(); shopsVersion++
                }
            }
        }
        if (pendingShopPreview) {
            shopPreviewStaged.clear(); shopPreviewStaged.addAll(found.distinctBy { it.url })
            if (previewOnlyProducts && showSubscriptionProducts) subscriptionProducts = shopPreviewStaged.toList()
            return
        }
        val state = newItemsPaging ?: return
        val updated = state.observe(found)
        newItemsPaging = updated
        if (updated.reachedOld) finishSubscriptionShopCapture(capture, found)
    }
    fun stopNewItemsCheck() {
        val capture = subscriptionShopCapture
        if (capture != null) failSubscriptionShopCapture(capture, "已停止讀取；已找到的新品及原有時間基準會保留。")
    }
    fun nextNewItemsPage() {
        val state = newItemsPaging ?: return
        val next = state.next()
        if (!next.collecting) { newItemsPaging = next; return }
        newItemsPageStartedAt = android.os.SystemClock.elapsedRealtime()
        newItemsPaging = if (next.reachedOld) next.publish() else next
        if (!next.reachedOld && subscriptionShopCapture == null) {
            nextNewItemsCapture?.let { subscriptionShopCapture = it; subscriptionShopSearching = true }
        }
    }
    fun retryNewItemsPage() {
        val state = newItemsPaging ?: return
        val capture = nextNewItemsCapture ?: return
        newItemsPaging = state.copy(collecting = true, error = null)
        newItemsPageStartedAt = android.os.SystemClock.elapsedRealtime()
        subscriptionShopCapture = capture; subscriptionShopSearching = true
    }
    fun closeNewItemsPage() {
        saveNewItemsProgress(complete = newItemsPaging?.reachedOld == true)
        subscriptionShopToken++
        subscriptionShopCapture = null; subscriptionShopSearching = false
        nextNewItemsCapture = null; shopRead = null
        newItemsPaging = null; showNewItemsPage = false
    }
    fun restartKeywordCapture(request: SearchCapture) {
        searchToken++
        resultRevision++
        queue = arrayListOf(); stagedProducts.clear(); products.clear()
        hasCompletedSearch = false; fetching = true; timedOut = false; showSearchResults = true
        resultNote = "正在重新讀取 ${request.market.label}商品…"
        backgroundCapture = request.copy(token = searchToken)
    }
    val shopeeRecoveryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        shopeeSignedIn = ShopeeWebConfiguration.canSearch()
        val retry = shopeeSearchRetry
        shopeeSearchRetry = null; shopeeRecoveryLaunching = false
        if (retry != null && result.resultCode == android.app.Activity.RESULT_OK) {
            restartKeywordCapture(retry.copy(officialRecoveryAttempt = retry.officialRecoveryAttempt + 1, retryAttempt = 0))
        } else if (retry != null) {
            resultNote = "已返回；尚未確認官方驗證完成，可由右上角繼續或重新搜尋。"
        }
    }
    LaunchedEffect(shopeeSearchRetry, foreground) {
        val retry = shopeeSearchRetry ?: return@LaunchedEffect
        val recovery = ShopeeWebConfiguration.recovery ?: return@LaunchedEffect
        if (!ShopeeLoginPolicy.shouldAutoRecover(retry, foreground, recovery) || shopeeRecoveryLaunching) return@LaunchedEffect
        shopeeRecoveryLaunching = true
        focusManager.clearFocus(force = true); keyboard?.hide(); menu = false
        Toast.makeText(context, if (recovery.route == ShopeeLoginPolicy.AccessRoute.VERIFICATION)
            "蝦皮需要官方驗證，正在開啟內建瀏覽器。" else "蝦皮要求登入，正在開啟官方頁面。", Toast.LENGTH_LONG).show()
        runCatching {
            shopeeRecoveryLauncher.launch(Intent(context, ShopeeLoginActivity::class.java)
                .putExtra(ShopeeLoginActivity.EXTRA_URL, recovery.officialUrl)
                .putExtra(ShopeeLoginActivity.EXTRA_SEARCH_URL, recovery.searchUrl))
        }.onFailure {
            shopeeSearchRetry = null; shopeeRecoveryLaunching = false
            warning = "無法開啟官方頁面，請由右上角重試。"
        }
    }
    val yahooConsentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val retry = yahooConsentRetry
        yahooConsentRetry = null; showYahooConsent = false
        if (result.resultCode == android.app.Activity.RESULT_OK && retry != null) {
            if (retry.request.quickSearch) {
                restartKeywordCapture(retry.request)
            } else if (nextNewItemsCapture == retry.request && newItemsPaging != null) {
                retryNewItemsPage()
            } else {
                startSubscriptionShopCapture(retry.shopKey, Market.YAHOO, retry.preview, retry.viewOnly)
            }
        }
    }
    LaunchedEffect(subscriptionNotification?.id) {
        val request = subscriptionNotification ?: return@LaunchedEffect
        if (fetching) stopSearch()
        if (newItemsPaging != null) closeNewItemsPage()
        subscriptionShopToken++; subscriptionShopCapture = null; subscriptionShopSearching = false
        pendingShopPreview = false; pendingShop = ""; shopRead = null
        subscriptionRefreshId++; subscriptionRefreshJob?.cancel(); subscriptionRefreshJob = null; subscriptionRefreshing = false
        focusManager.clearFocus(force = true); keyboard?.hide()
        menu = false; searchHistoryExpanded = false; warning = null; captureDiagnostic = null; startupSubscriptionNotice = null
        yahooConsentRetry = null; showYahooConsent = false
        shopeeSearchRetry = null; shopeeRecoveryLaunching = false
        showThemeDialog = false; showSleepDialog = false; showBackgroundDeliverySettings = false
        showBackgroundStatus = false; showUsernameDialog = false; showShopeeLogoutDialog = false
        deleteSearchHistory = null; clearSearchHistory = false
        showSearchResults = false; showShopDetails = false; showSubscriptionProducts = false
        subscriptionHistoryShop = null; subscriptionNewItemUrls = emptySet(); previewOnlyProducts = false
        subscriptionPageRequest++
        pager.scrollToPage(1)
        if (request.target.kind == RefreshKind.SHOP) {
            val saved = shopsPrefs.getString(request.target.url, null)?.let { shopSubscriptionRecord(request.target.url, it) }
            if (saved == null) warning = "這個店家已取消訂閱。"
            else if (!AdultVisibility.unlocked && AdultVisibility.adultText(saved.optString("name"))) warning = "此訂閱目前隱藏。"
            else {
                subscriptionHistoryShop = request.target.url
                subscriptionProducts = ShopNewItems.read(saved)
                subscriptionNewItemUrls = ShopNewItems.latestUrls(saved)
                productPageTitle = ShopNewItems.pageTitle(saved.optString("name"), subscriptionProducts)
                showSubscriptionProducts = true
            }
        } else {
            val productStore = ProductSubscriptionStore(context)
            val product = productStore.prefs.getString(request.target.url, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (product == null) {
                warning = "這個商品已取消訂閱。"
            } else {
                val finalUrl = product.optString("resolvedUrl").takeIf { PublicProductParser.sameProductUrl(it, request.target.url) }
                    ?: request.target.url
                runCatching { uriHandler.openUri(finalUrl) }
                    .onFailure { warning = "無法開啟商品連結。" }
            }
        }
        onSubscriptionNotificationConsumed()
    }
    DisposableEffect(shopsPrefs, subscriptionHistoryShop, showSubscriptionProducts) {
        val key = subscriptionHistoryShop
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (showSubscriptionProducts && key != null && changed == key) {
                val saved = shopsPrefs.getString(key, null)?.let { shopSubscriptionRecord(key, it) }
                subscriptionProducts = saved?.let(ShopNewItems::read).orEmpty()
                subscriptionNewItemUrls = saved?.let(ShopNewItems::latestUrls).orEmpty()
                if (saved != null) productPageTitle = ShopNewItems.pageTitle(saved.optString("name"), subscriptionProducts)
            }
        }
        shopsPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { shopsPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(subscriptionShopToken) {
        if (!pendingShopPreview || !subscriptionShopSearching) return@LaunchedEffect
        val token = subscriptionShopToken
        val started = SystemClock.elapsedRealtime()
        var retried = false
        while (subscriptionShopSearching && subscriptionShopToken == token && pendingShopPreview) {
            val elapsed = SystemClock.elapsedRealtime() - started
            val streamingPreview = subscriptionShopCapture?.infiniteSearch == true
            if (elapsed >= SearchWaitPolicy.DEADLINE_MILLIS && (!streamingPreview || shopPreviewStaged.isEmpty())) {
                val capture = subscriptionShopCapture ?: break
                if (shopPreviewStaged.isNotEmpty()) finishSubscriptionShopCapture(capture, shopPreviewStaged.toList())
                else failSubscriptionShopCapture(capture, SearchWaitPolicy.TIMEOUT_MESSAGE)
                break
            }
            if (previewOnlyProducts && !showSubscriptionProducts &&
                    ShopPreviewPresentation.showResults(elapsed, shopPreviewStaged.count { AdultVisibility.visible(it) }, subscriptionShopCapture?.market)) {
                    subscriptionProducts = shopPreviewStaged.toList()
                    val savedName = shopSubscriptionRecord(pendingShop, shopsPrefs.getString(pendingShop, null)).optString("name")
                    productPageTitle = "${shopPreviewStaged.firstOrNull { it.shop.isNotBlank() }?.shop ?: savedName.ifBlank { "店鋪" }} 的商品"
                    showSubscriptionProducts = true
            }
            if (elapsed >= SearchWaitPolicy.SLOW_MILLIS) {
                shopPreviewNote = if (shopPreviewStaged.isEmpty()) SearchWaitPolicy.SLOW_MESSAGE else "正在載入更多店鋪商品…"
                if (SearchWaitPolicy.retry(elapsed, shopPreviewStaged.size, retried)) {
                    retried = true
                    subscriptionShopCapture = subscriptionShopCapture?.copy(retryAttempt = 1)
                }
            }
            delay(100)
        }
    }
    LaunchedEffect(subscriptionShopToken, newItemsPaging?.collecting, newItemsPaging?.pages?.size,
        newItemsPaging?.pending?.isNotEmpty()) {
        val state = newItemsPaging ?: return@LaunchedEffect
        if (!state.collecting || state.pending.isEmpty()) return@LaunchedEffect
        delay((5000L - (android.os.SystemClock.elapsedRealtime() - newItemsPageStartedAt)).coerceAtLeast(0))
        newItemsPaging = newItemsPaging?.publish()
        showNewItemsPage = true
        saveNewItemsProgress(complete = false)
    }
    // Restore opt-in schedules and retire legacy automatic jobs; no launch refresh.
    LaunchedEffect(sharedSubscription?.id) {
        if (sharedSubscription != null) {
            if (fetching) stopSearch()
            if (subscriptionShopSearching) stopNewItemsCheck()
            showSearchResults = false; showSubscriptionProducts = false; showShopDetails = false
            pager.animateScrollToPage(1)
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        delay(500)
        withContext(Dispatchers.IO) { AutomaticRefreshScheduler.initialize(context) }
    }
    LaunchedEffect(dispatch, searchToken) {
        if (dispatch > 0 && queue.isNotEmpty()) {
            val token = searchToken
            val market = Market.valueOf(queue.first())
            if (!market.supportsSearch) {
                stopSearch()
                resultNote = "此平台已不提供搜尋；既有訂閱仍保留。"
                return@LaunchedEffect
            }
            queue = ArrayList(queue.drop(1)); currentMarket = market.label
            searchPageComplete = false
                backgroundCapture = SearchCapture(market,
                    market.search(appliedQuery, appliedMin, appliedMax,
                        if (market == Market.YAHOO) appliedYahooSubcategory.ifBlank { null } else null,
                        if (SearchPageLoading.infinite(market)) 1 else appliedPage, appliedCarousellSortBy, appliedCarousellCategory.ifBlank { null }, appliedYahooState, appliedYahooSort,
                        rutenCategoryId = appliedRutenCategory, boboMall = appliedBoboMall, shopeeSort = appliedShopeeSort,
                        shopeeMall = appliedShopeeMall, shopeePreferred = appliedShopeePreferred,
                        shopeeCondition = appliedShopeeCondition, facebookSort = appliedFacebookSort), token, quickSearch = true,
                    requestedPage = appliedPage, previousPageUrls = previousSearchPageUrls, minimumPrice = appliedMin.toLongOrNull() ?: 0,
                    maximumPrice = appliedMax.toLongOrNull() ?: Long.MAX_VALUE)
        }
    }
    LaunchedEffect(fetching, searchToken) {
        if (!fetching) return@LaunchedEffect
        val token = searchToken
        val market = Market.searchSelection(selected)
        val started = SystemClock.elapsedRealtime()
        while (fetching && searchToken == token) {
            val elapsed = SystemClock.elapsedRealtime() - started
            val count = stagedProducts.count { AdultVisibility.visible(it) &&
                it.price >= (appliedMin.toLongOrNull() ?: 0) && it.price <= (appliedMax.toLongOrNull() ?: Long.MAX_VALUE) }
            if (SearchPageLoading.showPreview(elapsed, count, market))
                publishSearchProgress(force = elapsed >= SearchPageLoading.previewMillis(market) && products.isEmpty())
            // The mobile reader keeps streaming while it makes progress. Numbered
            // sites must finish the current page before navigation is enabled.
            if (!SearchPageLoading.infinite(market) && elapsed >= SearchPageLoading.deadline(count, market)) break
            if (SearchPageLoading.infinite(market) && count == 0 &&
                elapsed >= SearchPageLoading.emptyDeadline(market, true)) break
            if (elapsed >= SearchWaitPolicy.SLOW_MILLIS) resultNote = SearchPageLoading.SLOW_MESSAGE
            delay(100)
        }
        if (fetching && searchToken == token) {
            // Publish the real cards collected so far; late responses cannot replace them.
            products.clear(); products.addAll(stagedProducts.distinctBy { it.url })
            queue = arrayListOf(); backgroundCapture = null; fetching = false
            hasCompletedSearch = true
            timedOut = true
            searchPageComplete = false
            resultNote = if (products.isEmpty()) SearchWaitPolicy.TIMEOUT_MESSAGE
                else SearchPageLoading.PARTIAL_MESSAGE
            if (products.isEmpty()) warning = SearchWaitPolicy.TIMEOUT_MESSAGE
            searchToken++
            presentFinishedResults()
        }
    }
    LaunchedEffect(showSearchResults, searchToken, fetching) {
        if (!showSearchResults || fetching) return@LaunchedEffect
        BoboProductMetadata.enrich(products.toList()) { enriched ->
            val index = products.indexOfFirst { it.url == enriched.url }
            if (index >= 0) products[index] = enriched
        }
    }
    val rootBack = remember { RootBackPolicy() }
    val backToast = remember(context) { Toast.makeText(context, "再按一次返回，將 App 移至背景", Toast.LENGTH_SHORT) }
    val imeVisible = WindowInsets.isImeVisible
    val startupNoticeVisible = startupSubscriptionNotice?.any {
        AdultVisibility.unlocked || !(it.isAdult || AdultVisibility.adultText(it.title) || AdultVisibility.adultText(it.shop))
    } == true
    val rootBackEnabled = !showSearchResults && !showSubscriptionProducts && !showShopDetails && !showNewItemsPage &&
        !fetching && !subscriptionShopSearching &&
        warning == null && captureDiagnostic == null && !menu && !showThemeDialog && !showSleepDialog &&
        !showBackgroundDeliverySettings && !showBackgroundStatus && !showUsernameDialog && !showShopeeLogoutDialog &&
        !startupNoticeVisible && !searchHistoryExpanded && deleteSearchHistory == null && !clearSearchHistory && !showYahooConsent && shopeeSearchRetry == null
    // Leaving a root page, opening a modal/keyboard, or resuming never counts as the first back.
    LaunchedEffect(foreground, pager.targetPage, rootBackEnabled, imeVisible) {
        rootBack.reset()
        backToast.cancel()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(backToast, lifecycleOwner) {
        // Observe lifecycle directly, including quick Home/resume without a Compose frame between them.
        val observer = LifecycleEventObserver { _, _ -> rootBack.reset(); backToast.cancel() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            backToast.cancel()
        }
    }
    fun closeSearchResults() {
        if (fetching || backgroundCapture != null) stopSearch()
        showSearchResults = false
    }
    fun closeSubscriptionProducts() {
        if (previewOnlyProducts && subscriptionShopSearching) {
            subscriptionShopToken++; subscriptionShopCapture = null; subscriptionShopSearching = false
            pendingShopPreview = false; pendingShop = ""; shopRead = null
        }
        showSubscriptionProducts = false; subscriptionHistoryShop = null
    }
    // Keep one screen-level callback registered even while captures or stale modal
    // flags are present. Real dialogs/popups own their windows and consume Back first.
    // Children no longer compete by composition/registration order.
    ScreenBackHandler(enabled = foreground) {
        // The IME/system gets first chance to consume keyboard Back. Once this
        // callback receives an event, always navigate: stale OEM IME insets must
        // not turn every subsequent gesture into another no-op hide request.
        focusManager.clearFocus(force = true)
        keyboard?.hide()
            val destination = BackDestination.resolve(showNewItemsPage, showSubscriptionProducts,
                showShopDetails, showSearchResults, fetching, subscriptionShopSearching)
            if (destination != BackDestination.ROOT) {
                rootBack.reset(); backToast.cancel()
            }
            when (destination) {
                BackDestination.NEW_ITEMS -> closeNewItemsPage()
                BackDestination.SUBSCRIPTION_PRODUCTS -> closeSubscriptionProducts()
                BackDestination.SHOP -> { showShopDetails = false }
                BackDestination.SEARCH_RESULTS -> closeSearchResults()
                BackDestination.SEARCH_LOADING -> stopSearch()
                BackDestination.SHOP_LOADING -> {
                    subscriptionShopToken++; subscriptionShopCapture = null; subscriptionShopSearching = false
                    pendingShopPreview = false; pendingShop = ""; shopRead = null
                }
                BackDestination.ROOT -> {
            val page = if (pager.currentPage != 0 || pager.targetPage != 0) 1 else 0
            when (rootBack.back(page, SystemClock.elapsedRealtime())) {
                RootBackPolicy.Action.SEARCH -> {
                    backToast.cancel()
                    navigationScope.launch {
                        // A still-settling touch/fling must not cancel explicit Back navigation.
                        pager.stopScroll(MutatePriority.PreventUserInput)
                        pager.animateScrollToPage(0, animationSpec = tween(300))
                    }
                }
                RootBackPolicy.Action.PROMPT -> backToast.show()
                RootBackPolicy.Action.BACKGROUND -> {
                    backToast.cancel()
                    onMoveToBackground()
                }
            }
                }
            }
    }
    RadarLiquidTheme(dark) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        subscriptionShopCapture?.let { capture ->
            HiddenMarketCapture(
                capture,
                onDiagnostic = { message, report -> if (subscriptionShopCapture == capture) captureDiagnostic = message to report },
                onProgress = { found -> observeSubscriptionProgress(capture, found) },
                onShopName = { name ->
                    if (subscriptionShopCapture == capture) synchronized(SubscriptionMutations.lock) {
                        shopsPrefs.getString(pendingShop, null)?.let { raw ->
                            val record = shopSubscriptionRecord(pendingShop, raw)
                            if (record.optString("name") != name) {
                                record.put("name", name)
                                shopsPrefs.edit().putString(pendingShop, record.toString()).apply()
                                shopsVersion++
                            }
                        }
                    }
                },
                onProducts = { found -> finishSubscriptionShopCapture(capture, found) },
                onTimeout = { message -> failSubscriptionShopCapture(capture, message) }
            )
        }
                    backgroundCapture?.let { capture ->
                        HiddenMarketCapture(capture, onDiagnostic = { message, report ->
                            if (backgroundCapture == capture && capture.token == searchToken && fetching &&
                                !SearchPageLoading.loadMoreAfterTimeout(products.size, message)) captureDiagnostic = message to report },
                        onFeedPaused = { paused -> if (backgroundCapture?.token == capture.token) searchFeedPaused = paused },
                        onPageCount = { count -> if (backgroundCapture == capture && capture.token == searchToken && !capture.infiniteSearch)
                            searchPageCount = maxOf(searchPageCount ?: 1, count) }, onProgress = { found ->
                            if (backgroundCapture == capture && capture.token == searchToken && (fetching || capture.infiniteSearch)) {
                            if (capture.market == Market.SHOPEE && !capture.infiniteSearch) stagedProducts.removeAll { it.platform == Market.SHOPEE.label }
                            found.forEach { candidate ->
                                val index = stagedProducts.indexOfFirst { it.url == candidate.url }
                                if (index >= 0) stagedProducts[index] = candidate else stagedProducts.add(candidate)
                            }
                            publishSearchProgress()
                            }
                        }, onProducts = { found ->
                            if (backgroundCapture == capture && capture.token == searchToken && fetching) {
                                backgroundCapture = null
                                if (capture.market == Market.SHOPEE && !capture.infiniteSearch) stagedProducts.removeAll { it.platform == Market.SHOPEE.label }
                                found.forEach { candidate ->
                                    val index = stagedProducts.indexOfFirst { it.url == candidate.url }
                                    if (index >= 0) stagedProducts[index] = candidate else stagedProducts.add(candidate)
                                }
                                if (queue.isNotEmpty()) dispatch++
                                else {
                                    products.clear()
                                    products.addAll(stagedProducts.distinctBy { it.url })
                                    fetching = false
                                    timedOut = false
                                    hasCompletedSearch = true
                                    searchPageComplete = true
                                    presentFinishedResults()
                                    if (capture.loadMore && found.none { it.url !in productsBeforeMore })
                                        carousellMoreBatch = (carousellMoreBatch - 1).coerceAtLeast(0)
                                    resultNote = if (capture.loadMore && found.none { p -> p.url !in productsBeforeMore }) "沒有讀到更多商品；已保留原有結果，可稍後重試。" else if (products.isEmpty()) "本次沒有可辨識商品。" else "本次已讀取 ${products.count { AdultVisibility.visible(it) }} 件商品。"
                                }
                            }
                        }, onTimeout = { message ->
                            if (backgroundCapture == capture && capture.token == searchToken && fetching) {
                                val consentRequired = offerYahooConsent(capture, message)
                                val recovery = ShopeeWebConfiguration.recovery
                                if (ShopeeLoginPolicy.shouldAutoRecover(capture, foreground, recovery) &&
                                    recovery != null && message == ShopeeLoginPolicy.accessMessage(recovery.route)) {
                                    shopeeSearchRetry = capture
                                    captureDiagnostic = null; warning = null
                                }
                                backgroundCapture = null
                                queue = arrayListOf()
                                if (capture.loadMore) {
                                    carousellMoreBatch = (carousellMoreBatch - 1).coerceAtLeast(0)
                                    showSearchResults = true
                                }
                                products.clear(); products.addAll(stagedProducts.distinctBy { it.url })
                                stagedProducts.clear()
                                fetching = false
                                hasCompletedSearch = true
                                presentFinishedResults()
                                timedOut = true
                                searchPageComplete = false
                                resultNote = "${capture.market.label}$message"
                                // One dismissible dialog, never a persistent error on the form/cards.
                                // Keep ordinary append timeouts as the existing load-more footer.
                                if (!consentRequired && shopeeSearchRetry == null && captureDiagnostic == null &&
                                    !SearchPageLoading.loadMoreAfterTimeout(products.size, message))
                                    warning = if (message == SearchWaitPolicy.TIMEOUT_MESSAGE) message else resultNote
                            }
                        })
                    }

        // Opaque native UI covers the attached reader without making its WebView transparent.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        if (!showSearchResults) Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
            TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background), title = {
                Text(listOf("搜尋", "訂閱", "競拍追蹤")[tab.coerceIn(0, 2)], fontWeight = FontWeight.Bold)
            }, actions = {
                if (tab == 1 || tab == 2) {
                    val refreshing = if (tab == 1) subscriptionRefreshing else auctionRefreshing
                    IconButton(enabled = !refreshing, onClick = {
                        if (tab == 1) refreshSubscriptions() else refreshAuctions()
                    }) {
                        Icon(Icons.Default.Refresh, if (tab == 1) "刷新訂閱" else "刷新競拍")
                    }
                }
                val menuRotation by animateFloatAsState(if (menu) 90f else 0f, tween(180), label = "menuRotation")
                IconButton(onClick = { menu = !menu }) {
                    Icon(Icons.Default.MoreVert, "選單", Modifier.graphicsLayer { rotationZ = menuRotation })
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("清除歷史紀錄") }, onClick = {
                        menu = false; clearSearchHistory = true
                    })
                    DropdownMenuItem(text = { Text("外觀模式") }, onClick = { menu = false; showThemeDialog = true })
                    DropdownMenuItem(text = { Text("睡眠模式") }, onClick = { menu = false; showSleepDialog = true })
                    DropdownMenuItem(text = { Text("背景通知設定") }, onClick = { menu = false; showBackgroundDeliverySettings = true })
                    DebugBackgroundRefreshMenu { menu = false; showBackgroundStatus = true }
                    DebugNotificationTestMenu { menu = false; showDebugNotificationTest = true }
                    DebugShopeeGuestMenu { menu = false }
                    ShopeeWebConfiguration.recovery?.let { recovery ->
                        DropdownMenuItem(text = { Text(if (recovery.route == ShopeeLoginPolicy.AccessRoute.VERIFICATION)
                            "完成蝦皮驗證" else "重新登入蝦皮") }, onClick = {
                            menu = false
                            context.startActivity(Intent(context, ShopeeLoginActivity::class.java)
                                .putExtra(ShopeeLoginActivity.EXTRA_URL, recovery.officialUrl)
                                .putExtra(ShopeeLoginActivity.EXTRA_SEARCH_URL, recovery.searchUrl))
                        })
                    }
                    DropdownMenuItem(text = { Text(if (shopeeSignedIn) "蝦皮登出" else "登入蝦皮") }, onClick = {
                        menu = false
                        if (ShopeeWebConfiguration.canSearch()) showShopeeLogoutDialog = true
                        else context.startActivity(Intent(context, ShopeeLoginActivity::class.java))
                    })
                    DropdownMenuItem(text = { Text("設定競拍用戶名") }, onClick = {
                        menu = false; usernameDraft = auctionStore.username(); rutenUsernameDraft = auctionStore.username(Market.RUTEN); showUsernameDialog = true
                    })
                }
            })
        }, bottomBar = {
            Box(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                RadarNavigation(pager.targetPage) { i ->
                    navigationScope.launch { pager.animateScrollToPage(i, animationSpec = tween(300)) }
                }
            }
        }) { padding ->
            HorizontalPager(pager, modifier = Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()
                .intentionalPageSwipe(enabled = !fetching && !subscriptionShopSearching &&
                    !showSubscriptionProducts && !showShopDetails && !showNewItemsPage,
                    pager = pager, fling = pageFling), flingBehavior = pageFling, userScrollEnabled = false) { page ->
            val modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
            CompositionLocalProvider(LocalCardBackActive provides (foreground && pager.currentPage == page &&
                pager.targetPage == page && !showSearchResults && !showSubscriptionProducts &&
                !showShopDetails && !showNewItemsPage)) {
            when (page) {
                0 -> Box(modifier, contentAlignment = Alignment.TopCenter) {
                    LazyColumn(Modifier.widthIn(max = RadarLayoutPolicy.FORM_MAX_WIDTH.dp).fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        LiquidGlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                            var drop by remember { mutableStateOf(false) }
                            Box(Modifier.widthIn(max = 132.dp)) {
                                TextButton(onClick = { drop = true }) {
                                    Text(selected, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Icon(Icons.Default.ArrowDropDown, "選擇平台", Modifier.size(20.dp))
                                }
                                DropdownMenu(expanded = drop, onDismissRequest = { drop = false }) {
                                    Market.searchMarkets.map { it.label }.forEach { name ->
                                        DropdownMenuItem(text = { Text(name) }, onClick = { selectedLabel = name; drop = false })
                                    }
                                }
                            }
                            VerticalDivider(Modifier.height(34.dp))
                            BasicTextField(query, { value ->
                                if (AdultVisibility.consumeInput(value)) { query = ""; warning = "隱藏功能解鎖" }
                                else query = value
                            }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                modifier = Modifier.weight(1f).padding(start = 16.dp, end = 12.dp), decorationBox = { inner ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (query.isBlank()) Text("商品名稱", Modifier.padding(start = 2.dp),
                                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        inner()
                                    }
                                })
                            SearchHistoryDropdown(historyMarket,
                                SearchHistory.forMarket(searchHistoryEntries, historyMarket, AdultVisibility.unlocked),
                                enabled = !fetching && foreground,
                                expanded = searchHistoryExpanded, onExpandedChange = { if (it) ensureSearchHistoryLoaded(); searchHistoryExpanded = it },
                                onRestore = { restoreSearchHistory(it) }, onDelete = { deleteSearchHistory = it })
                        } }
                    }
                    if (selected == Market.CAROUSELL.label) item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CategoryDropdown("排序", carousellSort,
                                SearchHistoryCriteria.CAROUSELL_SORTS.map { it to it },
                                onSelect = { carousellSort = it })
                            CategorySelectors(Market.CAROUSELL, carousellMainCategory, carousellCategory) { main, category ->
                                carousellMainCategory = main; carousellCategory = category
                            }
                        }
                    }
                    if (selected == Market.BBOBO.label) item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CategoryDropdown("商城", BoboSearch.malls.first { it.first == BoboSearch.zone(boboMall) }.second,
                            BoboSearch.malls, onSelect = { boboMall = it })
                        CategoryDropdown("商品分類", boboCategory.ifBlank { "全部分類" },
                            listOf("" to "全部分類") + BoboSearch.categories.map { it to it }, onSelect = { boboCategory = it })
                        Text("分類篩選目前頁面的商品", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (selected == Market.SHOPEE.label) item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CategoryDropdown("排序", ShopeeFilters.sorts.firstOrNull { it.first == shopeeSort }?.second ?: "綜合排名",
                                    ShopeeFilters.sorts, modifier = Modifier.weight(1f), compact = true,
                                    onSelect = { shopeeSort = it })
                                CategoryDropdown("商品狀況", ShopeeFilters.conditions.firstOrNull { it.first == shopeeCondition }?.second ?: "全部",
                                    ShopeeFilters.conditions, modifier = Modifier.weight(1f), compact = true,
                                    onSelect = { shopeeCondition = it })
                            }
                            FilterDropdown("賣場類型", ShopeeFilters.shopTypeLabel(shopeeMall, shopeePreferred), compact = true) { dismiss ->
                                DropdownMenuItem(text = { Text("不限賣場") },
                                    leadingIcon = { RadioButton(selected = !shopeeMall && !shopeePreferred, onClick = null) },
                                    onClick = { shopeeMall = false; shopeePreferred = false; dismiss() })
                                DropdownMenuItem(text = { Text("蝦皮商城") },
                                    leadingIcon = { Checkbox(checked = shopeeMall, onCheckedChange = null) },
                                    onClick = { shopeeMall = !shopeeMall })
                                DropdownMenuItem(text = { Text("蝦皮優選") },
                                    leadingIcon = { Checkbox(checked = shopeePreferred, onCheckedChange = null) },
                                    onClick = { shopeePreferred = !shopeePreferred })
                            }
                        }
                    }
                    if (selected == Market.FACEBOOK.label) item {
                        CategoryDropdown("排序", FacebookFilters.sorts.firstOrNull { it.first == facebookSort }?.second ?: "最新",
                            FacebookFilters.sorts, onSelect = { facebookSort = it })
                    }
                    if (selected == Market.RUTEN.label) item {
                        CategorySelectors(Market.RUTEN, rutenMainCategory, rutenCategory) { main, category ->
                            rutenMainCategory = main; rutenCategory = category
                        }
                    }
                    if (selected == Market.YAHOO.label) item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            YahooFilterControls(yahooState, yahooSort,
                                onState = { yahooState = it }, onSort = { yahooSort = it })
                            CategorySelectors(Market.YAHOO, yahooMainCategory, yahooSubcategory) { main, category ->
                                yahooMainCategory = main; yahooSubcategory = category
                            }
                        }
                    }
                    // Shared final filters: every platform places prices directly above Search.
                    // Platform-specific controls must stay before this item, never below it.
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(min, { min = it.filter(Char::isDigit).take(9) }, label = { Text("最低價格") }, singleLine = true, shape = RoundedCornerShape(16.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                            OutlinedTextField(max, { max = it.filter(Char::isDigit).take(9) }, label = { Text("最高價格") }, singleLine = true, shape = RoundedCornerShape(16.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        }
                    }
                    item {
                        if (!fetching) Button(onClick = {
                            focusManager.clearFocus(force = true)
                            keyboard?.hide()
                            when {
                                AdultVisibility.consumeInput(query) -> { query = ""; warning = "隱藏功能解鎖" }
                                query.isBlank() && !(selected == Market.CAROUSELL.label && carousellCategory.isNotBlank()) && !(selected == Market.YAHOO.label && yahooSubcategory.isNotBlank()) && !(selected == Market.RUTEN.label && rutenCategory.isNotBlank()) -> warning = "請輸入商品名稱，或先選擇露天／旋轉／Y拍的大分類。"
                                min.isNotBlank() && max.isNotBlank() && min.toLong() > max.toLong() -> warning = "最低價格不能大於最高價格。"
                                selected == Market.SHOPEE.label && !ShopeeWebConfiguration.canSearch() && !ShopeeGuestSearch.enabled ->
                                    context.startActivity(Intent(context, ShopeeLoginActivity::class.java))
                                else -> {
                                    saveSearchHistory()
                                    searchToken++
                                    // A normal keyword search must never reuse the category
                                    // result destination after the user has visited that tab.


                                    appliedQuery = query.trim(); appliedMin = min; appliedMax = max
                                    searchPage = 1; appliedPage = 1; appliedYahooSubcategory = yahooSubcategory
                                    previousSearchPageUrls = emptySet()
                                    searchPageCount = null
                                    appliedRutenCategory = if (selected == Market.RUTEN.label) rutenCategory else ""
                                    appliedYahooState = yahooState; appliedYahooSort = yahooSort; appliedBoboMall = BoboSearch.zone(boboMall)
                                    appliedBoboCategory = BoboSearch.category(boboCategory)
                                    appliedFacebookSort = facebookSort
                                    appliedShopeeSort = shopeeSort
                                    appliedShopeeMall = shopeeMall; appliedShopeePreferred = shopeePreferred
                                    appliedShopeeCondition = shopeeCondition
                                    appliedCarousellSortBy = mapOf("最佳匹配" to "1", "近期" to "3", "價位高至低" to "5", "價位低至高" to "4")[carousellSort] ?: "3"
                                    appliedCarousellCategory = carousellCategory
                                    activeCarousellUrl = ""
                                    carousellMoreBatch = 0
                                    hasCompletedSearch = false
                                    resultRevision++; products.clear(); stagedProducts.clear(); backgroundCapture = null; browserFallback = false; timedOut = false; fetching = true
                                    resultNote = "抓取中，請稍候…"
                                    queue = ArrayList(Market.searchMarkets.filter {
                                        it.label == selected
                                    }.map { it.name })
                                    dispatch++
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("搜尋商品") }
                        if (!fetching && browserFallback) TextButton(onClick = {
                            currentMarket = Market.RUTEN.label
                            searchToken++; appliedQuery = query.trim(); appliedMin = min; appliedMax = max
                            
                            activeCarousellUrl = ""
                            carousellMoreBatch = 0
                            appliedPage = searchPage; hasCompletedSearch = false
                            resultRevision++; products.clear(); stagedProducts.clear(); queue = arrayListOf(); fetching = true
                            resultNote = "抓取中，請稍候…"; browserFallback = false
                            backgroundCapture = SearchCapture(Market.RUTEN, Market.RUTEN.search(appliedQuery, appliedMin, appliedMax, page = appliedPage, rutenCategoryId = appliedRutenCategory), searchToken, quickSearch = true)
                        }) { Text("改用露天網頁搜尋") }
                    }
                    }
                }
                1 -> ShopSubscriptions(modifier, shopsVersion, subscriptionRefreshing,
                    onWarning = { warning = it }, onViewProducts = { shopUrl, items, latest ->
                    subscriptionHistoryShop = shopUrl
                    subscriptionProducts = items; subscriptionNewItemUrls = latest
                    productPageTitle = ShopNewItems.pageTitle(shopSubscriptionRecord(shopUrl, shopsPrefs.getString(shopUrl, null)).optString("name"), items)
                    showSubscriptionProducts = true
                }, onCheck = { url, market ->
                    startSubscriptionShopCapture(url, market, preview = false)
                }, onSearchShop = { url, market ->
                    startSubscriptionShopCapture(url, market, preview = true)
                }, onViewShop = { url, market -> startSubscriptionShopCapture(url, market, preview = true, viewOnly = true) },
                    sharedSubscription = sharedSubscription.takeIf { pager.settledPage == 1 }, onShareConsumed = onShareConsumed,
                    onAuctionAdded = { navigationScope.launch { pager.animateScrollToPage(2) } })
                2 -> AuctionScreen(modifier, foreground && pager.settledPage == 2,
                    refreshing = auctionRefreshing, onWarning = { warning = it })

            }
            }
            }
        }
        }
        if (showSearchResults) SearchResultsPage(
            requestId = resultRevision,
            products = products.filter { it.price >= (appliedMin.toLongOrNull() ?: 0) && it.price <= (appliedMax.toLongOrNull() ?: Long.MAX_VALUE) &&
                BoboSearch.matchesCategory(it, appliedBoboCategory) },
            currentPage = appliedPage,
            pageCount = searchPageCount?.coerceIn(1, 500) ?: appliedPage,
            pageSelectionEnabled = selected in listOf(Market.BBOBO.label, Market.IOPEN.label),
            pageComplete = searchPageComplete,
            fetching = fetching,
            feedPaused = searchFeedPaused,
            onConsumed = { count -> backgroundCapture?.let { capture ->
                if (capture.infiniteSearch) backgroundCapture = capture.copy(consumedProducts = count)
            } },
            note = if (selected == Market.BBOBO.label && appliedBoboCategory.isNotBlank() && !fetching && !timedOut)
                "這一頁沒有符合所選分類的商品，可切換頁數或返回搜尋調整分類。" else resultNote,
            canRetry = timedOut,
            onStop = { stopSearch() },
            onRetry = { if (fetching) stopSearch(); selectResultsPage(appliedPage) },
            onLoadMore = { selectResultsPage(appliedPage, keepProducts = true) },
            onBack = { closeSearchResults() },
            onSelectPage = { page -> selectResultsPage(page) },

            onSubscribe = { product -> warning = subscribeListing(context, product).takeIf { it.isNotBlank() } },
            onOpen = { product -> runCatching { openListingDetails(context, product) { uriHandler.openUri(it) } }.onFailure { warning = "無法開啟連結。" } }
        )
        if (showSubscriptionProducts) key(subscriptionHistoryShop, subscriptionPageRequest) {
        SubscriptionProductsPage(productPageTitle, subscriptionProducts,
            inlineActions = subscriptionHistoryShop != null,
            newItemUrls = if (subscriptionHistoryShop != null) subscriptionNewItemUrls else emptySet(),
            fetching = previewOnlyProducts && subscriptionShopSearching,
            onBack = { closeSubscriptionProducts() },
            onClear = subscriptionHistoryShop?.let { shopUrl -> {
                synchronized(SubscriptionMutations.lock) {
                    if (shopsPrefs.contains(shopUrl)) {
                        val saved = clearShopNewItems(shopUrl, shopsPrefs.getString(shopUrl, null))
                        shopsPrefs.edit().putString(shopUrl, saved.toString()).commit()
                        shopsVersion++
                    }
                }
                subscriptionProducts = emptyList()
                subscriptionNewItemUrls = emptySet()
            } })
        }
        if (showShopDetails && !showSubscriptionProducts && previewShopMarket != null) ShopDetailsPage(previewShopName, previewShopMarket!!, previewShopProducts,
            onBack = { showShopDetails = false }, onViewProducts = {
                subscriptionHistoryShop = null
                subscriptionProducts = previewShopProducts; productPageTitle = "${previewShopName} 的商品"; showSubscriptionProducts = true
            }, onSubscribe = {
                runCatching {
                    synchronized(SubscriptionMutations.lock) {
                        require(!shopsPrefs.contains(previewShopUrl) && shopsPrefs.all.keys.none { SubscriptionIdentity.sameShop(it, previewShopUrl) }) {
                            SubscriptionIdentity.DUPLICATE_MESSAGE
                        }
                        val saved = JSONObject(shopsPrefs.getString(previewShopUrl, "{}") ?: "{}")
                        saved.put("url", previewShopUrl).put("platform", previewShopMarket!!.name).put("name", previewShopName)
                        ShopSubscriptionCover.update(saved, previewShopProducts)
                        if (!saved.has("seen")) saved.put("seen", JSONArray(previewShopProducts.map { it.url }))
                        if (!saved.has("newItems")) saved.put("newItems", JSONArray())
                        if (previewShopMarket == Market.RUTEN) {
                            // The first page is the baseline. Later checks will continue
                            // to page 2+ only while listings still reach this timestamp.
                            saved.put("rutenCheckpointAt", RutenShopPaging.newestMillis(previewShopProducts))
                        }
                        saved.put("listingCheckpointAt", ShopCheckpoint.newest(previewShopProducts))
                        saved.put("checkedAt", System.currentTimeMillis()).put("newCount", 0)
                        shopsPrefs.edit().putString(previewShopUrl, saved.toString()).apply(); shopsVersion++
                        SubscriptionRefreshQueue.request(context, RefreshTarget(RefreshKind.SHOP, previewShopUrl))
                        showShopDetails = false; warning = "已新增訂閱：$previewShopName"
                    }
                }.onFailure { warning = it.message ?: "無法新增店家訂閱，請重試。" }
            })
        if (showNewItemsPage) newItemsPaging?.let { state ->
            SubscriptionNewItemsPage(ShopNewItems.pageTitle(shopSubscriptionRecord(pendingShop, shopsPrefs.getString(pendingShop, null)).optString("name"), state.displayed), state, subscriptionShopSearching,
                onPrevious = { newItemsPaging = newItemsPaging?.previous() },
                onNext = { nextNewItemsPage() }, onRetry = { retryNewItemsPage() },
                onStop = { stopNewItemsCheck() }, onBack = { closeNewItemsPage() })
        }
        if (fetching && !showSearchResults && !CaptureDebugPolicy.visibleReader(backgroundCapture)) SearchProgressDialog(
            detail = when {
                backgroundCapture?.loadMore == true -> "正在讀取更多 ${Market.CAROUSELL.label} 商品，請稍候…"
                else -> resultNote.ifBlank { "正在搜尋公開商品，請稍候…" }
            },
            onStop = { stopSearch() }
        )
        subscriptionShopCapture?.takeIf { subscriptionShopSearching && !showNewItemsPage && !showSubscriptionProducts }?.let { capture ->
            val stopShopRead: () -> Unit = {
                if (newItemsPaging != null) stopNewItemsCheck()
                else failSubscriptionShopCapture(capture, "已停止讀取。")
            }
            SearchProgressDialog(
                detail = if (pendingShopPreview) shopPreviewNote else "正在讀取 ${capture.market.label} 的公開商品，請稍候…",
                onStop = stopShopRead
            )
        }
        ForegroundAuctions(foreground, dialogBusy = warning != null || captureDiagnostic != null ||
            fetching || subscriptionRefreshing || subscriptionShopSearching || auctionRefreshing || menu || showThemeDialog ||
            showSleepDialog || showBackgroundDeliverySettings || showUsernameDialog || showShopeeLogoutDialog || startupSubscriptionNotice != null ||
            searchHistoryExpanded || deleteSearchHistory != null || clearSearchHistory || showYahooConsent || shopeeSearchRetry != null)
        SearchHistoryDeleteDialog(deleteSearchHistory, clearSearchHistory,
            onDismiss = { deleteSearchHistory = null; clearSearchHistory = false },
            onConfirm = {
                searchHistoryEntries = if (clearSearchHistory) emptyList()
                    else deleteSearchHistory?.let { SearchHistory.delete(searchHistoryEntries, it.id) } ?: searchHistoryEntries
                searchHistoryStore.save(searchHistoryEntries)
                deleteSearchHistory = null; clearSearchHistory = false
            })
        if (showShopeeLogoutDialog) AlertDialog(
            onDismissRequest = { if (!shopeeLoggingOut) showShopeeLogoutDialog = false },
            title = { Text("蝦皮登出") },
            text = { Text(if (shopeeLoggingOut) "正在登出…" else "要登出此 App 的蝦皮帳號嗎？不影響其他瀏覽器或平台的登入。") },
            confirmButton = { TextButton(enabled = !shopeeLoggingOut, onClick = {
                shopeeLoggingOut = true
                if (backgroundCapture?.market == Market.SHOPEE) stopSearch()
                navigationScope.launch {
                    try {
                        val cleared = ShopeeWebConfiguration.logout()
                        shopeeSignedIn = ShopeeWebConfiguration.canSearch()
                        showShopeeLogoutDialog = false
                        if (!cleared) warning = "蝦皮登入狀態未清除完成，請重試。"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { warning = "蝦皮登出未完成，請重試。"; showShopeeLogoutDialog = false }
                    finally { shopeeLoggingOut = false }
                }
            }) { Text("登出") } },
            dismissButton = { TextButton(enabled = !shopeeLoggingOut, onClick = { showShopeeLogoutDialog = false }) { Text("取消") } }
        )
        if (showSleepDialog) RefreshSleepDialog { showSleepDialog = false }
        if (showBackgroundDeliverySettings) BackgroundDeliverySettingsDialog { showBackgroundDeliverySettings = false }
        if (showDebugNotificationTest) DebugNotificationTestDialog { showDebugNotificationTest = false }
        if (showBackgroundStatus) BackgroundRefreshDialog { showBackgroundStatus = false }
        if (showThemeDialog) AlertDialog(
            onDismissRequest = { showThemeDialog = false }, title = { Text("外觀模式") },
            text = { Column {
                val options = (if (Build.VERSION.SDK_INT >= 28) listOf("system" to "系統預設") else emptyList()) +
                    listOf("light" to "淺色模式", "dark" to "深色模式")
                options.forEach { (mode, label) ->
                    TextButton(onClick = {
                        themeMode = mode; appearancePrefs.edit().putString("mode", mode).apply(); showThemeDialog = false
                    }, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = themeMode == mode, onClick = null)
                        Text(label, Modifier.weight(1f))
                    }
                }
            } }, confirmButton = {}
        )
        if (showUsernameDialog) AlertDialog(
            onDismissRequest = { showUsernameDialog = false },
            title = { Text("設定競拍用戶名") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(usernameDraft, { usernameDraft = it }, label = { Text("Yahoo 公開帳號 ID／用戶名") }, singleLine = true)
                    OutlinedTextField(rutenUsernameDraft, { rutenUsernameDraft = it }, label = { Text("露天公開帳號／用戶名") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = {
                if (usernameDraft.isNotBlank() && !usernameDraft.trim().matches(Regex("[Yy]\\d+"))) warning = "Yahoo 請填入完整公開帳號 ID（Y 加數字）。"
                else if (rutenUsernameDraft.isNotBlank() && !rutenUsernameDraft.trim().matches(Regex("[A-Za-z0-9_.-]{2,80}"))) warning = "露天請填入完整公開用戶名，不使用遮罩名稱。"
                else {
                    if (auctionStore.username() != usernameDraft.trim()) auctionStore.username(usernameDraft.trim())
                    if (auctionStore.username(Market.RUTEN) != rutenUsernameDraft.trim()) auctionStore.username(rutenUsernameDraft.trim(), Market.RUTEN)
                    showUsernameDialog = false
                }
            }) { Text("儲存") } },
            dismissButton = { TextButton(onClick = { showUsernameDialog = false }) { Text("取消") } }
        )
        startupSubscriptionNotice?.filter { AdultVisibility.unlocked || !(it.isAdult || AdultVisibility.adultText(it.title) || AdultVisibility.adultText(it.shop)) }?.takeIf { it.isNotEmpty() }?.let { alerts ->
            if (warning == null && captureDiagnostic == null) AlertDialog(
                onDismissRequest = { startupSubscriptionNotice = null },
                title = { Text(if (alerts.any { it.kind == "新品" }) "有新商品加入" else "訂閱商品有更新") },
                text = {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(alerts) { alert ->
                            Column(Modifier.fillMaxWidth()) {
                                Text(alert.shop, style = MaterialTheme.typography.titleSmall)
                                Text(alert.title, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { startupSubscriptionNotice = null }) { Text("查看完成") } }
            )
        }
        if (showYahooConsent) AlertDialog(
            onDismissRequest = { showYahooConsent = false; yahooConsentRetry = null },
            title = { Text("Y拍頁面尚未載入") }, text = { Text(YahooConsentPolicy.MESSAGE) },
            confirmButton = { TextButton(onClick = {
                val retry = yahooConsentRetry ?: return@TextButton
                showYahooConsent = false
                yahooConsentLauncher.launch(Intent(context, MarketWebActivity::class.java)
                    .putExtra("market", Market.YAHOO.name).putExtra("url", retry.request.url).putExtra("yahooConsent", true))
            }) { Text("開啟官方頁面") } },
            dismissButton = { TextButton(onClick = { showYahooConsent = false; yahooConsentRetry = null }) { Text("取消") } })
        if (captureDiagnostic == null && !showYahooConsent) warning?.let { message -> AlertDialog(onDismissRequest = { warning = null }, title = { Text("提醒") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { warning = null }) { Text("知道了") } }) }
        captureDiagnostic?.let { (message, report) ->
            CaptureDiagnosticDialog(message, report) { captureDiagnostic = null; warning = null }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AuctionScreen(modifier: Modifier, foreground: Boolean, refreshing: Boolean,
                          onWarning: (String) -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val store = remember { AuctionStore(context) }
    val refreshingTargets by SubscriptionRefreshQueue.running.collectAsState()
    var version by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var url by rememberSaveable { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) onWarning("通知權限未開啟，仍可在競拍頁查看超標提醒。")
    }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(foreground) {
        if (!foreground) return@LaunchedEffect
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val entries = remember(version) { store.list() }.filter { AdultVisibility.visible(it) }
    AdaptiveCardGrid(modifier, contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), fixedColumns = 2) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = RadarLayoutPolicy.FORM_MAX_WIDTH.dp).fillMaxWidth()) {
            Row {
                val notificationGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!notificationGranted) TextButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("允許超標通知") }
            }
            OutlinedTextField(url, { url = it }, label = { Text("Yahoo／露天競拍商品網址") }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), singleLine = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    runCatching { store.add(AuctionParser.canonicalUrl(url)); url = "" }
                        .onFailure { onWarning(it.message ?: "網址無效。") }
                }) { Text("訂閱競拍") }
            }
            }
            }
        }
        if (entries.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("貼上競拍網址即可追蹤價格與倒數。") }
        items(entries, key = { it.getString("url") }) { entry ->
            val checked = entry.optLong("checkedAt")
            val end = entry.optLong("endsAt")
            val estimatedServer = entry.optLong("serverAt", now) + (now - checked).coerceAtLeast(0)
            val state = entry.optString("displayState", "unknown")
            val cancelled = state == "cancelled" || entry.optBoolean("cancelled")
            val ended = !cancelled && (state == "ended" || end > 0 && estimatedServer >= end)
            val confirmedEnded = state == "ended" || end > 0 && entry.optLong("serverAt") >= end
            val bidderIds = entry.optJSONArray("bidderIds")?.let { a ->
                (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
            }.orEmpty()
            val target = RefreshTarget(RefreshKind.AUCTION, entry.getString("url"))
            val reading = refreshing || target.key in refreshingTargets
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                AuctionCardCover(target, reading,
                    onRefresh = { SubscriptionRefreshQueue.request(context, target) },
                    extraMenu = { closeMenu ->
                        val enabled = entry.optBoolean("alertsEnabled", true)
                        DropdownMenuItem(text = { Text("啟用提醒") },
                            leadingIcon = { Checkbox(checked = enabled, onCheckedChange = null) },
                            onClick = { store.alertsEnabled(entry.getString("url"), !enabled); closeMenu() })
                    }
                ) { cover -> ProductCover(entry.optString("image"), "競拍商品", cover) }
                ProvideTextStyle(MaterialTheme.typography.bodySmall) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(entry.optString("title"), minLines = 3, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(Market.fromPublicProductUrl(entry.optString("url"))?.label.orEmpty(), style = MaterialTheme.typography.labelSmall)
                    if (entry.has("price")) Text("目前 NT$${entry.optLong("price")} · ${entry.optInt("bidCount")} 次出價")
                    Text(when {
                        end == 0L -> "等待取得結標時間"
                        cancelled -> "競拍已取消"
                        ended -> "已結標"
                        else -> "剩餘 ${countdown(end - estimatedServer)}"
                    })
                    if (end > 0) Text("結標：${dateText(end)}", style = MaterialTheme.typography.bodySmall)
                    if (ended) Text(AuctionResultText.winner(bidderIds, entry.optInt("bidCount"), store.accountFor(entry.optString("url")), confirmedEnded),
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    else if (!cancelled) {
                        val standing = AuctionStanding.text(entry, store.accountFor(entry.optString("url")))
                        Text(standing, color = if (standing.startsWith("被（")) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface)
                    }
                    if (entry.optString("bidRecordsError").isNotBlank()) Text(entry.getString("bidRecordsError"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (entry.optString("error").isNotBlank()) {
                        Text("讀取失敗：${entry.getString("error")}", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { onWarning(entry.getString("error")) }) { Text("查看警告") }
                    }
                    if (checked > 0 && now - checked > 10 * 60000) Text("競拍資料待更新。", color = MaterialTheme.colorScheme.error)
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        TextButton(onClick = {
                            runCatching { uriHandler.openUri(entry.getString("url")) }.onFailure { onWarning("無法開啟競拍網址。") }
                        }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp)) {
                            Text("查看競拍", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { store.remove(entry.getString("url")) },
                            modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp)) {
                            Text("取消追蹤", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } } }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProductCard(product: Product, onSubscribe: () -> Unit, onOpen: () -> Unit, photoGrid: Boolean = false,
    isNew: Boolean = false, inlineActions: Boolean = false) {
    val imageContext = LocalContext.current
    LaunchedEffect(product.url, product.image) { ImageCachePolicy.record(imageContext, listOf(product)) }
    if (photoGrid) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                Box {
                    ProductCover(product.image, product.title, Modifier.fillMaxWidth().aspectRatio(1f))
                    if (isNew) NewProductBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
                }
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProductCardInformation(product, compact = true)
                    if (product.isAdvertisement) Text("可能為廣告", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary)
                    ProductCardActions(product, onSubscribe, onOpen, inlineActions)
                }
            }
        }
        return
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.width(104.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(84.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.ShoppingBag, null, tint = MaterialTheme.colorScheme.outline)
                        if (product.image.isNotBlank()) AsyncImage(product.image, product.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (isNew) NewProductBadge(Modifier.align(Alignment.TopStart).padding(4.dp))
                    }
                    if (!inlineActions) ProductCardActions(product, onSubscribe, onOpen, inline = false)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProductCardInformation(product, compact = false)
                }
            }
            if (product.isAdvertisement) Text("可能為廣告 · 已依時間排序", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            if (inlineActions) ProductCardActions(product, onSubscribe, onOpen, inline = true)
        }
    }
}

@Composable
private fun ProductCardActions(product: Product, onSubscribe: () -> Unit, onOpen: () -> Unit, inline: Boolean) {
    val labelStyle = if (inline) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
    val subscribe: @Composable (Modifier) -> Unit = { modifier ->
        TextButton(onClick = onSubscribe, modifier = modifier,
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) {
            Text(if (product.isAuction) "訂閱競標" else "訂閱商品", style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val details: @Composable (Modifier) -> Unit = { modifier ->
        TextButton(onClick = onOpen, enabled = product.platform != Market.FACEBOOK.label || FacebookListingMetadata.directUrl(product) != null,
            modifier = modifier, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) {
            Text("查看詳情", style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (inline) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        subscribe(Modifier.weight(1f).heightIn(min = 48.dp))
        details(Modifier.weight(1f).heightIn(min = 48.dp))
    } else Column {
        subscribe(Modifier.fillMaxWidth())
        details(Modifier.fillMaxWidth())
    }
}

@Composable
private fun ProductCardInformation(product: Product, compact: Boolean) {
    Text(ProductDisplay.title(product.title), style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        maxLines = 3, overflow = TextOverflow.Ellipsis)
    Text("NT$${String.format(Locale.TAIWAN, "%,d", product.price)}",
        style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    if (ListingTimeDisplay.visible(product.platform)) Text("上架：${ListingTimeDisplay.text(product.platform, product.listedAt, product.listedAtEpochMillis)}", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (product.platform == Market.BBOBO.label) Text("商品所在：${product.shop.ifBlank { "尚未取得" }}",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (product.isAuction) AuctionProductInfo(product)
}

@Composable
private fun AuctionProductInfo(product: Product) {
    val context = LocalContext.current
    val store = remember(context) { AuctionStore(context) }
    var now by remember(product.url, product.auctionEndsAt) { mutableLongStateOf(System.currentTimeMillis()) }
    val ended = !product.auctionCancelled && (product.auctionEnded || product.auctionEndsAt > 0 &&
        maxOf(now, product.auctionServerAt) >= product.auctionEndsAt)
    LaunchedEffect(product.url, product.auctionEndsAt, product.auctionCancelled) {
        while (!product.auctionCancelled && product.auctionEndsAt > now) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    Text(when {
        product.auctionCancelled -> "競標已取消"
        product.auctionEndsAt <= 0 -> "剩餘時間：未提供"
        ended -> "已結標"
        else -> "剩餘：" + countdown(product.auctionEndsAt - now)
    }, style = MaterialTheme.typography.bodySmall)
    if (ended) Text(AuctionResultText.winner(
        product.highestBidder.takeUnless { it == "尚無人出價" }.orEmpty().split('、').filter { it.isNotBlank() },
        product.auctionBidCount, store.accountFor(product.url), product.auctionEnded || product.auctionServerAt >= product.auctionEndsAt),
        style = MaterialTheme.typography.bodySmall)
    else if (!product.auctionCancelled) Text("目前最高出價者：" + product.highestBidder.ifBlank { "無出價" }, style = MaterialTheme.typography.bodySmall)
    Text(if (product.auctionPriceKnown) "目前競標價：NT$" + String.format(Locale.TAIWAN, "%,d", product.price)
        else "目前競標價：尚未確認（上方為列表價格）", style = MaterialTheme.typography.bodySmall)
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SearchResultsPage(
    title: String = "搜尋結果", backLabel: String = "返回搜尋", products: List<Product>, currentPage: Int, pageCount: Int = 15, requestId: Int,
    pageSelectionEnabled: Boolean, pageComplete: Boolean, fetching: Boolean, note: String,
    feedPaused: Boolean, onConsumed: (Int) -> Unit,
    canRetry: Boolean, onStop: () -> Unit, onRetry: () -> Unit, onLoadMore: () -> Unit,
    onBack: () -> Unit, onSelectPage: (Int) -> Unit,
    onSubscribe: (Product) -> Unit, onOpen: (Product) -> Unit
) {
    var pagesExpanded by remember { mutableStateOf(false) }
    var manualRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(fetching) { if (!fetching) manualRefreshing = false }
    val offerLoadMore = !pageSelectionEnabled && canRetry && SearchPageLoading.loadMoreAfterTimeout(products.size, note)
    val layout = rememberProductLayout()
    val resultListState = rememberLazyGridState()
    val latestConsumed by rememberUpdatedState(onConsumed)
    LaunchedEffect(resultListState, requestId) {
        snapshotFlow { resultListState.layoutInfo.visibleItemsInfo.filter { it.key is String }
            .maxOfOrNull { it.index + 1 } ?: 0 }.collect { latestConsumed(it) }
    }
    LaunchedEffect(requestId, currentPage) { resultListState.scrollToItem(0) }
    val visibleProducts = products.filter { AdultVisibility.visible(it) }.distinctBy { it.url }
    FacebookListingDates(visibleProducts, resultListState, enabled = !fetching) { datedProducts ->
        val columns = if (layout.photoGrid) 2 else 1
        val ready = SearchPageLoading.readyCount(datedProducts.size, columns, fetching && !pageSelectionEnabled)
        val shown = ready
        val loading = fetching && !feedPaused && !pageSelectionEnabled
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, backLabel) }
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (manualRefreshing && fetching && !feedPaused) IconButton(onClick = onStop,
                        modifier = Modifier.semantics { contentDescription = "停止載入" }) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else IconButton(onClick = { manualRefreshing = true; onRetry() }) {
                        Icon(Icons.Default.Refresh, "刷新這一頁")
                    }
                    ProductLayoutToggle(layout)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (pageSelectionEnabled) Box(Modifier.align(Alignment.CenterVertically)) {
                        OutlinedButton(onClick = { pagesExpanded = true }, enabled = !fetching) { Text("第 $currentPage／$pageCount 頁") }
                        DropdownMenu(expanded = pagesExpanded, onDismissRequest = { pagesExpanded = false }) {
                            (1..pageCount).forEach { page -> DropdownMenuItem(text = { Text("第 $page 頁") }, onClick = {
                                pagesExpanded = false
                                if (page != currentPage) { manualRefreshing = false; onSelectPage(page) }
                            }) }
                        }
                    }
                    if (pageSelectionEnabled) {
                        TextButton(onClick = { manualRefreshing = false; onSelectPage(currentPage - 1) }, enabled = currentPage > 1 && !fetching,
                            modifier = Modifier.align(Alignment.CenterVertically)) { Text("上一頁") }
                        TextButton(onClick = { manualRefreshing = false; onSelectPage(currentPage + 1) }, enabled = currentPage < pageCount && !fetching,
                            modifier = Modifier.align(Alignment.CenterVertically)) { Text("下一頁") }
                    }
                    Text("$shown 件商品",
                        Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodySmall)
                }
                if (!fetching && !canRetry && products.isEmpty()) Text(note, Modifier.padding(top = 18.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                AdaptiveCardGrid(Modifier.fillMaxSize(), state = resultListState, photoGrid = layout.photoGrid,
                    fixedColumns = if (layout.photoGrid) 2 else 1) {
                    items(datedProducts.take(shown), key = { it.url }) { product ->
                        ProductCard(product, { onSubscribe(product) }, { onOpen(product) }, photoGrid = layout.photoGrid, inlineActions = true)
                    }
                    if (loading) item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                    if (!loading && offerLoadMore) item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                            TextButton(onClick = onLoadMore) { Text("載入更多商品") }
                        }
                    }
                }
                if (pageSelectionEnabled && fetching) Surface(
                    modifier = Modifier.align(Alignment.Center).semantics { contentDescription = "正在載入第 $currentPage 頁" },
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
                    CircularProgressIndicator(Modifier.padding(16.dp).size(32.dp), strokeWidth = 3.dp)
                }
                }
            }
        }
    }
}

private data class CategoryOption(val name: String, val id: String, val children: List<CategoryOption> = emptyList())

private fun categoryOptions(market: Market): List<CategoryOption> =
    if (market == Market.CAROUSELL) CarousellCategories.groups.map { group ->
        CategoryOption(group.name, group.url, group.children.map { CategoryOption(it.name, it.url) })
    } else if (market == Market.RUTEN) RutenCategories.groups.map { group ->
        CategoryOption(group.name, group.id, group.children.map { CategoryOption(it.name, it.id) })
    } else YahooCategories.groups.map { group ->
        CategoryOption(group.name, group.cid, group.children.map { CategoryOption(it.name, it.cid) })
    }

@Composable
private fun CategoryDropdown(label: String, value: String, options: List<Pair<String, String>>,
    modifier: Modifier = Modifier, enabled: Boolean = true, compact: Boolean = true, onSelect: (String) -> Unit) {
    FilterDropdown(label, value, modifier, enabled, compact) { dismiss ->
        options.forEach { (id, name) ->
            DropdownMenuItem(text = { Text(name) }, onClick = { dismiss(); onSelect(id) })
        }
    }
}

@Composable
private fun FilterDropdown(label: String, value: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, compact: Boolean = true, options: @Composable (dismiss: () -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
        Text(label, style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled,
                shape = RoundedCornerShape(if (compact) 12.dp else 16.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = if (compact) 48.dp else 56.dp),
                contentPadding = PaddingValues(horizontal = if (compact) 10.dp else 12.dp, vertical = if (compact) 6.dp else 10.dp)) {
                Text(value, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelLarge)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(if (compact) 18.dp else 20.dp))
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 360.dp)) {
                options { expanded = false }
            }
        }
    }
}

@Composable
private fun CategorySelectors(market: Market, mainId: String, selectedId: String, onSelect: (String, String) -> Unit) {
    val unlocked = AdultVisibility.unlocked
    val groups = remember(market, unlocked) {
        categoryOptions(market).filter { unlocked || !AdultVisibility.adultCategory(market, it.id, it.name) }
            .map { group -> group.copy(children = group.children.filter { unlocked || !AdultVisibility.adultCategory(market, it.id, it.name) }) }
    }
    LaunchedEffect(unlocked, mainId, selectedId) {
        if (!unlocked && (AdultVisibility.adultCategory(market, mainId) || AdultVisibility.adultCategory(market, selectedId))) onSelect("", "")
    }
    val main = groups.firstOrNull { it.id == mainId }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CategoryDropdown("大分類", main?.name ?: "選擇大分類",
            listOf("" to "全部分類") + groups.map { it.id to it.name },
            Modifier.weight(1f)) { id -> onSelect(id, id) }
        CategoryDropdown("小分類",
            main?.children?.firstOrNull { it.id == selectedId }?.name ?: if (main == null) "請先選擇前項" else "全部",
            listOf((main?.id ?: "") to "全部") + main?.children.orEmpty().map { it.id to it.name },
            Modifier.weight(1f), enabled = main?.children?.isNotEmpty() == true) { id -> onSelect(mainId, id) }
    }
}

@Composable
private fun YahooFilterControls(state: String, sort: String,
    onState: (String) -> Unit, onSort: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CategoryDropdown("排序", YahooFilters.sorts.firstOrNull { it.first == sort }?.second ?: "最相關",
            YahooFilters.sorts, Modifier.weight(1f), onSelect = onSort)
        CategoryDropdown("狀態", YahooFilters.states.firstOrNull { it.first == state }?.second ?: "全部",
            YahooFilters.states, Modifier.weight(1f), onSelect = onState)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShopSubscriptions(modifier: Modifier, version: Int, refreshing: Boolean, onWarning: (String) -> Unit,
    onViewProducts: (String, List<Product>, Set<String>) -> Unit, onCheck: (String, Market) -> Unit,
    onSearchShop: (String, Market) -> Unit, onViewShop: (String, Market) -> Unit,
    sharedSubscription: SharedSubscriptionRequest?, onShareConsumed: () -> Unit, onAuctionAdded: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val prefs = remember { context.getSharedPreferences("shop_subscriptions_v2", 0) }
    val productStore = remember { ProductSubscriptionStore(context) }
    val linkScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var localVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(productStore) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> localVersion++ }
        productStore.prefs.registerOnSharedPreferenceChangeListener(listener)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            productStore.prefs.unregisterOnSharedPreferenceChangeListener(listener)
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    var url by rememberSaveable { mutableStateOf("") }
    var resolvingLink by remember { mutableStateOf(false) }
    var linkJob by remember { mutableStateOf<Job?>(null) }
    var linkRequestId by remember { mutableIntStateOf(0) }
    var pendingShopRemoval by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingProductRemoval by remember { mutableStateOf<Pair<String, String>?>(null) }
    val refreshingTargets by SubscriptionRefreshQueue.running.collectAsState()
    fun importLink(input: String) {
        linkJob?.cancel()
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        val requestId = ++linkRequestId
        resolvingLink = true
        linkJob = linkScope.launch {
            try {
                val result = SubscriptionImporter.add(context, input)
                if (requestId == linkRequestId) {
                    url = ""; localVersion++; onWarning(result.message)
                    if (result.kind == RefreshKind.AUCTION) onAuctionAdded()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: IllegalArgumentException) {
                if (requestId == linkRequestId) onWarning(error.message ?: "請貼上有效的公開商品或店家網址。")
            } catch (_: Exception) {
                if (requestId == linkRequestId) onWarning("暫時無法解析分享網址，請重試或改貼瀏覽器中的商品／店家網址。")
            } finally {
                if (requestId == linkRequestId) { resolvingLink = false; linkJob = null }
            }
        }
    }
    LaunchedEffect(sharedSubscription?.id) {
        sharedSubscription?.let { request -> importLink(request.url); onShareConsumed() }
    }
    val shops = remember(version, localVersion) { prefs.all.map { (key, value) -> shopSubscriptionRecord(key, value as? String) }.filter { Market.fromPublicUrl(it.optString("url")) != null }.distinctBy { it.optString("url") } }.filter { AdultVisibility.unlocked || !AdultVisibility.adultText(it.optString("name")) }
    // Repair legacy missing avatars from retained products, without fetching or changing scan state.
    LaunchedEffect(prefs, version, localVersion) {
        synchronized(SubscriptionMutations.lock) {
            val editor = prefs.edit()
            var changed = false
            prefs.all.forEach { (key, value) ->
                val record = shopSubscriptionRecord(key, value as? String)
                if (Market.fromPublicUrl(record.optString("url")) != null &&
                    ProductThumbnail.url(record.optString("image")) == null &&
                    ShopSubscriptionCover.update(record, ShopNewItems.read(record))) {
                    editor.putString(key, record.toString())
                    changed = true
                }
            }
            if (changed) editor.apply()
        }
    }
    val productSubscriptions = remember(localVersion) { productStore.list() }.filter { AdultVisibility.visible(it) }
    val refreshNow = rememberRefreshClock()
    AdaptiveCardGrid(modifier, contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), fixedColumns = 2) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = RadarLayoutPolicy.FORM_MAX_WIDTH.dp).fillMaxWidth()) {
            OutlinedTextField(url, { url = it }, label = { Text("店家或商品網址") },
                enabled = !resolvingLink, shape = RoundedCornerShape(16.dp), maxLines = 2,
                modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = url.isNotBlank() && !resolvingLink, onClick = {
                    importLink(url.trim())
                }) { Text("新增訂閱") }
            }
            }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            SubscriptionSection("訂閱店家", "你目前沒添加任何店家，快去添加試試吧。".takeIf { shops.isEmpty() })
        }
        items(shops, key = { it.getString("url") }) { shop ->
            val shopUrl = shop.getString("url")
            val history = ShopNewItems.read(shop).filter { AdultVisibility.visible(it) }
            val target = RefreshTarget(RefreshKind.SHOP, shopUrl)
            val reading = target.key in refreshingTargets || refreshing
            val shopImage = ProductThumbnail.url(shop.optString("image")).orEmpty()
            val platform = subscriptionPlatform(shop.optString("platform"), shopUrl)
            val name = ShopText.shop(shop.optString("name")).ifBlank { "尚未取得店名" }
            GlassCard(Modifier.fillMaxWidth()) {
            Column {
                SubscriptionCardCover(target, reading, "檢查新品", "取消店家訂閱",
                    onRefresh = {
                        val market = Market.fromSubscription(shop.optString("platform"), shopUrl)
                        if (market != null) onCheck(shopUrl, market) else onWarning("無法辨識店家平台，請重新新增此訂閱。")
                    }, onRemove = { pendingShopRemoval = shopUrl to name }
                ) { cover -> ProductCover(shopImage, "店家商品縮圖", cover) }
                val error = shop.optString("lastRefreshError")
                SubscriptionCardBody(
                    target = target,
                    eyebrow = platform,
                    title = name,
                    metadata = listOf(
                        "上次刷新：${RefreshTimeDisplay.text(shop.optLong("checkedAt"), refreshNow)}",
                        "上次發現 ${shop.optInt("newCount")} 件新品",
                        "保留新品：${history.size} 件"
                    ), status = error, onStatus = { onWarning(error) }) {
                    TextButton(onClick = { Market.fromPublicUrl(shopUrl)?.let { onViewShop(shopUrl, it) } },
                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp)) {
                        Text("查看店鋪", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(enabled = history.isNotEmpty(), onClick = { onViewProducts(shopUrl, history, ShopNewItems.latestUrls(shop)) },
                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp)) {
                        Text("查看新品", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                }
            } }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            SubscriptionSection("商品訂閱", "你目前沒添加任何商品，快去添加試試吧。".takeIf { productSubscriptions.isEmpty() })
        }
        items(productSubscriptions, key = { "product:${it.getString("url")}" }) { item ->
                    val productUrl = item.getString("url")
                    val target = RefreshTarget(RefreshKind.PRODUCT, productUrl)
                    val reading = target.key in refreshingTargets || refreshing
                    val shopName = ShopText.shop(item.optString("shop")).ifBlank {
                        subscriptionPlatform(item.optString("platform"), productUrl)
                    }
                    val eyebrow = if (item.optString("platform") == Market.BBOBO.label) "商品所在：$shopName" else shopName
                    val title = ProductDisplay.title(item.optString("title").ifBlank { "待取得商品資料" })
                    GlassCard(Modifier.fillMaxWidth()) { Column {
                        SubscriptionCardCover(target, reading, "刷新這件商品", "取消商品訂閱",
                            onRefresh = { SubscriptionRefreshQueue.request(context, target) },
                            onRemove = { pendingProductRemoval = productUrl to item.optString("title").ifBlank { "這件商品" } }
                        ) { cover -> SubscriptionThumbnail(item, cover) }
                        val price = item.optLong("price", -1)
                        val error = listOf(item.optString("lastCheckError"), item.optString("lastRefreshError"))
                            .filter { it.isNotBlank() }.distinct().joinToString("\n")
                        SubscriptionCardBody(
                            target = target,
                            eyebrow = eyebrow,
                            title = title,
                            metadata = listOfNotNull(
                                "目前價格：${if (price >= 0) "NT$${price}" else "待確認"}",
                                ProductStock.label(item),
                                if (ListingTimeDisplay.visible(item.optString("platform")))
                                    "上架：${ListingTimeDisplay.text(item.optString("platform"), item.optString("listedAt"), item.optLong("listedAtEpochMillis"))}" else null,
                                "上次刷新：${RefreshTimeDisplay.text(item.optLong("checkedAt"), refreshNow)}"
                            ), status = "", onStatus = { onWarning(error) }) {
                            TextButton(onClick = {
                                val final = item.optString("resolvedUrl").takeIf { PublicProductParser.sameProductUrl(it, item.getString("url")) }
                                    ?: item.getString("url")
                                runCatching { uriHandler.openUri(final) }
                                    .onFailure { onWarning("無法開啟商品連結。") }
                            }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp)) {
                                Text("查看詳情", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    } }
        }
    }
    if (resolvingLink) SearchProgressDialog("正在辨識平台及解析網址…") {
        linkRequestId++
        linkJob?.cancel()
        linkJob = null
        resolvingLink = false
    }
    pendingShopRemoval?.let { (shopUrl, name) ->
        AlertDialog(onDismissRequest = { pendingShopRemoval = null }, title = { Text("取消訂閱？") },
            text = { Text("確定要取消訂閱「$name」嗎？保留的新商品紀錄也會移除。") },
            confirmButton = { TextButton(onClick = {
                ImageCachePolicy.removeShop(context, shopUrl)
                NotificationManagerCompat.from(context).cancel("subscription-updates", RefreshTarget(RefreshKind.SHOP, shopUrl).key.hashCode())
                synchronized(SubscriptionMutations.lock) { prefs.edit().remove(shopUrl).commit() }
                AutomaticRefreshStore(context).configure(RefreshTarget(RefreshKind.SHOP, shopUrl), null)
                localVersion++; pendingShopRemoval = null
            }) { Text("確定取消") } },
            dismissButton = { TextButton(onClick = { pendingShopRemoval = null }) { Text("保留訂閱") } })
    }
    pendingProductRemoval?.let { (productUrl, title) ->
        AlertDialog(onDismissRequest = { pendingProductRemoval = null }, title = { Text("取消商品訂閱？") },
            text = { Text("確定停止追蹤「$title」的降價與售出狀態嗎？") },
            confirmButton = { TextButton(onClick = {
                productStore.remove(productUrl)
                NotificationManagerCompat.from(context).cancel("subscription-updates", RefreshTarget(RefreshKind.PRODUCT, productUrl).key.hashCode())
                localVersion++; pendingProductRemoval = null
            }) { Text("確定取消") } },
            dismissButton = { TextButton(onClick = { pendingProductRemoval = null }) { Text("保留訂閱") } })
    }
}

private fun subscriptionPlatform(storedPlatform: String, url: String): String =
    Market.fromSubscription(storedPlatform, url)?.label.orEmpty()

@Composable
private fun ShopDetailsPage(name: String, market: Market, products: List<Product>, onBack: () -> Unit,
    onViewProducts: () -> Unit, onSubscribe: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = RadarLayoutPolicy.FORM_MAX_WIDTH.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("返回訂閱") }
            Text(if (AdultVisibility.unlocked || !AdultVisibility.adultText(name)) name else "店家", style = MaterialTheme.typography.headlineSmall)
            Text("${market.label} · 已讀取 ${products.count { AdultVisibility.visible(it) }} 件公開商品")
            Button(onClick = onSubscribe, modifier = Modifier.fillMaxWidth()) { Text("新增訂閱") }
            OutlinedButton(onClick = onViewProducts, modifier = Modifier.fillMaxWidth()) { Text("查看詳情") }
        }
        }
    }
}

@Composable
private fun SubscriptionNewItemsPage(title: String, state: SubscriptionNewItemsPaging, reading: Boolean,
    onPrevious: () -> Unit, onNext: () -> Unit, onRetry: () -> Unit,
    onStop: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var note by remember { mutableStateOf("") }
    val listState = rememberLazyGridState()
    LaunchedEffect(state.currentPage) { listState.scrollToItem(0) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回訂閱") }
                Text(if (AdultVisibility.unlocked || !AdultVisibility.adultText(title)) title else "訂閱賣場的新品", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = onPrevious, enabled = state.canPrevious) { Text("上一頁") }
                Text("第 ${state.currentPage + 1} 頁")
                OutlinedButton(onClick = onNext, enabled = state.canNext) { Text("下一頁") }
            }
            if (reading || state.collecting) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.collecting) "正在搜尋新品…" else "正在確認後續商品…", Modifier.weight(1f))
                    if (reading) TextButton(onClick = onStop) { Text("停止") }
                }
            }
            state.error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry, enabled = !reading) { Text("重試") }
            }
            if (note.isNotBlank() && note != SubscriptionIdentity.DUPLICATE_MESSAGE && (AdultVisibility.unlocked || !AdultVisibility.adultText(note)))
                Text(note, color = MaterialTheme.colorScheme.primary)
            Box(Modifier.weight(1f).fillMaxWidth()) {
            AdaptiveCardGrid(Modifier.fillMaxSize(), state = listState, fixedColumns = 2) {
                items(state.displayed.filter { AdultVisibility.visible(it) }, key = { it.url }) { product ->
                    ProductCard(product, { note = subscribeListing(context, product) },
                        { runCatching { openListingDetails(context, product) { uriHandler.openUri(it) } }.onFailure { note = "無法開啟商品連結。" } }, photoGrid = true, isNew = true, inlineActions = true)
                }
            }
            if (state.collecting) CircularProgressIndicator(
                Modifier.align(Alignment.Center).size(32.dp).semantics { contentDescription = "正在載入新品" }, strokeWidth = 3.dp)
            }
        }
    }
    DuplicateSubscriptionDialog(note) { note = "" }
}

@Composable
private fun SubscriptionProductsPage(title: String, products: List<Product>, onBack: () -> Unit,
    onClear: (() -> Unit)? = null, fetching: Boolean = false, newItemUrls: Set<String> = emptySet(), inlineActions: Boolean = false) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val productStore = remember { ProductSubscriptionStore(context) }
    var note by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回訂閱") }
                Text(if (AdultVisibility.unlocked || !AdultVisibility.adultText(title)) title else "訂閱商品", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (fetching) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                if (onClear != null) TextButton(onClick = { confirmClear = true }, enabled = products.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("清空商品")
                }
            }
            if (!fetching && products.none { AdultVisibility.visible(it) }) Text("沒有可顯示的商品。")
            if (note.isNotBlank() && note != SubscriptionIdentity.DUPLICATE_MESSAGE && (AdultVisibility.unlocked || !AdultVisibility.adultText(note))) Text(note, color = MaterialTheme.colorScheme.primary)
            AdaptiveCardGrid(Modifier.weight(1f).fillMaxWidth(), fixedColumns = 2) {
                items(ShopNewItems.newestFirst(products.filter { AdultVisibility.visible(it) }, newItemUrls), key = { it.url }) { product ->
                    ProductCard(product,
                        { note = subscribeListing(context, product) },
                        { runCatching { openListingDetails(context, product) { uriHandler.openUri(it) } }.onFailure { note = "無法開啟商品連結。" } }, photoGrid = true, isNew = product.url in newItemUrls, inlineActions = inlineActions)
                }
            }
        }
    }
    DuplicateSubscriptionDialog(note) { note = "" }
    if (confirmClear && onClear != null) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("清空商品？") },
        text = { Text("確定清空這個店家保留的所有新品嗎？不會取消訂閱，也不會重設新品比對時間。") },
        confirmButton = { TextButton(onClick = { confirmClear = false; note = ""; onClear() }) { Text("確定清空") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
    )
}

@Composable
private fun DuplicateSubscriptionDialog(message: String, onDismiss: () -> Unit) {
    if (message == SubscriptionIdentity.DUPLICATE_MESSAGE) AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提醒") },
        text = { Text(SubscriptionIdentity.DUPLICATE_MESSAGE) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}

private fun dateText(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.TAIWAN).format(Date(ms))
private fun countdown(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "${seconds / 86400}天 ${seconds / 3600 % 24}時 ${seconds / 60 % 60}分 ${seconds % 60}秒"
}


private fun subscribeListing(context: android.content.Context, product: Product): String =
    runCatching {
        if (product.platform == Market.FACEBOOK.label && FacebookCardReference.parse(product.url) != null) {
            val actual = FacebookResolvedLinks.find(product.url)
            if (actual != null) {
                ProductSubscriptionStore(context).add(product.copy(url = actual))
                "已訂閱商品：${product.title}"
            } else {
                FacebookCardActivity.open(context, product, subscribe = true)
                ""
            }
        } else if (product.platform in setOf(Market.YAHOO.label, Market.RUTEN.label) && product.isAuction) {
            val store = AuctionStore(context)
            val url = AuctionParser.canonicalUrl(product.url)
            store.add(url)
            store.update(url) { it.put("title", product.title).put("image", product.image).put("isAdult", product.isAdult || AdultVisibility.adultText(product.title)) }
            ImageCachePolicy.record(context, listOf(product))
            "已訂閱競標：${product.title}"
        } else {
            ProductSubscriptionStore(context).add(product)
            "已訂閱商品：${product.title}"
        }
    }.getOrElse { it.message ?: "無法新增訂閱，請重試。" }

private fun openListingDetails(context: android.content.Context, product: Product, openUri: (String) -> Unit) {
    if (product.platform == Market.FACEBOOK.label) {
        val actual = FacebookListingMetadata.directUrl(product)
            ?: throw IllegalArgumentException("尚未取得這件 Facebook 商品的直接網址，請重新搜尋。")
        openUri(actual)
    }
    else openUri(product.url)
}
