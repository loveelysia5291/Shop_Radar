package com.aykkdk.shop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogisticsTrackingScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { LogisticsStore(context) }
    var records by remember { mutableStateOf(store.loadAll()) }
    var activeCarrierDialog by remember { mutableStateOf<LogisticsCarrier?>(null) }
    var selectedFilter by remember { mutableStateOf("全部") } // "全部", "待取貨", "已取貨"
    val scope = rememberCoroutineScope()
    var refreshingId by remember { mutableStateOf<String?>(null) }

    val pendingCount = records.count { !it.isPickedUp }
    val arrivedCount = records.count { !it.isPickedUp && it.stage == LogisticsStage.ARRIVED }
    val filteredRecords = when (selectedFilter) {
        "待取貨" -> records.filter { !it.isPickedUp }
        "已取貨" -> records.filter { it.isPickedUp }
        else -> records
    }

    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 頂部標題與說明卡片
            item {
                LiquidGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Build,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(
                                    "貨況查詢",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "隨時掌握各大超商與蝦皮店到店包裹進度，到店提醒不漏接",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(Modifier.height(18.dp))

                        // 五大物流商按鈕區塊
                        Text(
                            "選擇物流商快速查詢",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LogisticsCarrier.entries.forEach { carrier ->
                                CarrierQuickButton(
                                    carrier = carrier,
                                    modifier = Modifier.weight(1f),
                                    onClick = { activeCarrierDialog = carrier }
                                )
                            }
                        }
                    }
                }
            }

            // 狀態統計與過濾標籤列
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("全部", "待取貨", "已取貨").forEach { tab ->
                            val selected = selectedFilter == tab
                            FilterChip(
                                selected = selected,
                                onClick = { selectedFilter = tab },
                                label = {
                                    Text(
                                        when (tab) {
                                            "待取貨" -> "待取貨 ($pendingCount)"
                                            "已取貨" -> "已完成"
                                            else -> "全部 (${records.size})"
                                        }
                                    )
                                }
                            )
                        }
                    }

                    if (arrivedCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFDCFCE7),
                            contentColor = Color(0xFF15803D)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.NotificationsActive, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "$arrivedCount 件貨已到店！",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // 包裹清單
            if (filteredRecords.isEmpty()) {
                item {
                    LiquidGlassSurface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(32.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.LocalShipping,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                if (records.isEmpty()) "尚未新增任何包裹" else "此分類暫無包裹",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "點擊上方 7-11、全家、萊爾富、OK 或 蝦皮店到店 按鈕即可快速輸入單號並追蹤！",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(filteredRecords, key = { it.id }) { record ->
                    LogisticsParcelCard(
                        record = record,
                        isRefreshing = refreshingId == record.id,
                        onTogglePickedUp = {
                            val updated = store.markPickedUp(record.id, !record.isPickedUp)
                            if (updated != null) records = store.loadAll()
                        },
                        onDelete = {
                            store.delete(record.id)
                            records = store.loadAll()
                        },
                        onRefresh = {
                            scope.launch {
                                refreshingId = record.id
                                val res = LogisticsClient.query(
                                    carrier = record.carrier,
                                    trackingNumber = record.trackingNumber
                                )
                                if (res.success) {
                                    val updated = record.copy(
                                        latestStatus = res.statusText,
                                        stage = if (record.isPickedUp) LogisticsStage.PICKED_UP else res.stage,
                                        storeName = res.storeName ?: record.storeName,
                                        expireDateText = res.expireDateText ?: record.expireDateText,
                                        historyDetails = if (res.history.isNotEmpty()) res.history else record.historyDetails,
                                        updatedAtMillis = System.currentTimeMillis()
                                    )
                                    store.save(updated)
                                    records = store.loadAll()
                                    Toast.makeText(context, "貨況已更新", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, res.errorMessage ?: "更新失敗", Toast.LENGTH_SHORT).show()
                                }
                                refreshingId = null
                            }
                        }
                    )
                }
            }
        }
    }

    activeCarrierDialog?.let { carrier ->
        LogisticsQueryDialog(
            carrier = carrier,
            onDismiss = { activeCarrierDialog = null },
            onSaveRecord = { newRecord ->
                store.save(newRecord)
                records = store.loadAll()
                activeCarrierDialog = null
            }
        )
    }
}

@Composable
private fun CarrierQuickButton(
    carrier: LogisticsCarrier,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(68.dp),
        shape = RoundedCornerShape(16.dp),
        color = carrier.badgeBgColor,
        border = BorderStroke(1.dp, carrier.brandColor.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                when (carrier) {
                    LogisticsCarrier.SEVEN_ELEVEN -> "7-11"
                    LogisticsCarrier.FAMILY_MART -> "全家"
                    LogisticsCarrier.HI_LIFE -> "萊爾富"
                    LogisticsCarrier.OK_MART -> "OK"
                    LogisticsCarrier.SPX -> "蝦皮"
                },
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = carrier.badgeTextColor
            )
            Spacer(Modifier.height(2.dp))
            Text(
                when (carrier) {
                    LogisticsCarrier.OK_MART -> "維護中"
                    LogisticsCarrier.SPX -> "免驗證"
                    else -> "交寄/取貨"
                },
                fontSize = 10.sp,
                color = carrier.badgeTextColor.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LogisticsParcelCard(
    record: LogisticsRecord,
    isRefreshing: Boolean,
    onTogglePickedUp: () -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit
) {
    var expandedHistory by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val timeFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.TAIWAN) }

    LiquidGlassSurface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            // 首行：物流商標籤 + 狀態 Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = record.carrier.badgeBgColor
                ) {
                    Text(
                        record.carrier.displayName,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = record.carrier.badgeTextColor
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = record.stage.badgeBgColor
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            record.stage.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = record.stage.badgeTextColor
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // 備註品名或單號標題
            Text(
                if (record.note.isNotBlank()) record.note else "包裹 ${record.trackingNumber}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(6.dp))

            // 單號資訊與一鍵複製
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "單號：${record.trackingNumber}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(record.trackingNumber))
                        Toast.makeText(context, "已複製物流單號", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(28.dp).padding(start = 4.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, "複製單號", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }

            // 最新狀態摘要
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        record.latestStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    record.storeName?.let { store ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "📍 取件門市：$store",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    record.expireDateText?.let { expire ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "⏳ 取件期限：$expire",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFDC2626),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 更新時間
            Text(
                "最後更新：${timeFormat.format(Date(record.updatedAtMillis))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            // 歷史歷程展開
            if (record.historyDetails.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { expandedHistory = !expandedHistory }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (expandedHistory) "收合物流軌跡" else "展開詳細歷程 (${record.historyDetails.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        if (expandedHistory) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                AnimatedVisibility(visible = expandedHistory) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 4.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        record.historyDetails.forEach { h ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                Text(
                                    h.time,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.width(105.dp)
                                )
                                Text(
                                    h.description,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(8.dp))

            // 底部操作按鈕：我已取貨 / 重新整理 / 開啟官網 / 刪除
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onTogglePickedUp,
                    colors = if (record.isPickedUp) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF16A34A),
                            contentColor = Color.White
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.height(38.dp)
                ) {
                    Icon(
                        if (record.isPickedUp) Icons.Default.CheckCircle else Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (record.isPickedUp) "標記為未取" else "我已取貨", fontSize = 13.sp)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onRefresh,
                        enabled = !isRefreshing,
                        modifier = Modifier.size(36.dp)
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, "刷新貨況", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    IconButton(
                        onClick = { uriHandler.openUri(record.carrier.officialUrl) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.OpenInBrowser, "官網查詢", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.DeleteOutline, "刪除", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogisticsQueryDialog(
    carrier: LogisticsCarrier,
    onDismiss: () -> Unit,
    onSaveRecord: (LogisticsRecord) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current

    var note by remember { mutableStateOf("") }
    var trackingNumber by remember { mutableStateOf("") }
    var captchaCode by remember { mutableStateOf("") }
    var captchaSession by remember { mutableStateOf<CaptchaSession?>(null) }
    var loadingCaptcha by remember { mutableStateOf(false) }
    var querying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var autoRecognized by remember { mutableStateOf(false) }

    fun refreshCaptcha() {
        if (!carrier.needsCaptcha) return
        scope.launch {
            loadingCaptcha = true
            errorMessage = null
            autoRecognized = false
            val session = LogisticsClient.fetchCaptcha(carrier)
            captchaSession = session
            if (session.suggestedCode.isNotBlank()) {
                captchaCode = session.suggestedCode
                autoRecognized = true
            } else {
                captchaCode = ""
            }
            loadingCaptcha = false
        }
    }

    LaunchedEffect(carrier) {
        if (carrier.needsCaptcha) {
            refreshCaptcha()
        }
    }

    val captchaBitmap = remember(captchaSession?.imageBase64) {
        captchaSession?.imageBase64?.takeIf { it.isNotBlank() }?.let { b64 ->
            runCatching {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth(0.92f)
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(22.dp)
                    .fillMaxWidth()
            ) {
                // 對話框標題
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = carrier.badgeBgColor
                        ) {
                            Text(
                                carrier.displayName,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = carrier.badgeTextColor
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "貨況查詢",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "關閉")
                    }
                }

                Spacer(Modifier.height(16.dp))

                // OK 超商維護提示
                if (carrier.isUnderMaintenance) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFFEF3C7),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, null, tint = Color(0xFFB45309), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "OK 超商官方貨況查詢系統目前維護中。您仍可在此輸入單號並備註保存至清單！",
                                fontSize = 12.sp,
                                color = Color(0xFF92400E)
                            )
                        }
                    }
                }

                // 蝦皮店到店免驗證碼說明
                if (carrier == LogisticsCarrier.SPX) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFFFEDE9),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = Color(0xFFEE4D2D), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "✨ 蝦皮店到店支援官方 API 直接查詢，無須輸入任何驗證碼！",
                                fontSize = 12.sp,
                                color = Color(0xFFC23517),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // 最上方小格子：商品備註
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("商品備註（選填）") },
                    placeholder = { Text("例如：iPhone 手機殼、黑色衣服") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    leadingIcon = {
                        Icon(Icons.Default.EditNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                )

                Spacer(Modifier.height(12.dp))

                // 第一個框框：物流編號
                OutlinedTextField(
                    value = trackingNumber,
                    onValueChange = { trackingNumber = it },
                    label = { Text("物流編號 / 取件代碼") },
                    placeholder = { Text("請輸入或貼上包裹單號") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    leadingIcon = {
                        Icon(Icons.Default.LocalShipping, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingIcon = {
                        IconButton(onClick = {
                            clipboard.getText()?.text?.let { text ->
                                if (text.isNotBlank()) trackingNumber = text.trim()
                            }
                        }) {
                            Icon(Icons.Default.ContentPaste, "貼上單號", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = if (carrier.needsCaptcha) ImeAction.Next else ImeAction.Done)
                )

                // 第二個框框：驗證碼（若需要）
                if (carrier.needsCaptcha) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "輸入驗證碼",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = captchaCode,
                            onValueChange = {
                                captchaCode = it
                                autoRecognized = false
                            },
                            placeholder = { Text("驗證碼") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            )
                        )

                        // 驗證碼圖片區域
                        Surface(
                            modifier = Modifier
                                .height(56.dp)
                                .widthIn(min = 96.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (loadingCaptcha) {
                                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                } else if (captchaBitmap != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = captchaBitmap,
                                        contentDescription = "驗證碼圖片",
                                        modifier = Modifier.fillMaxHeight().padding(4.dp),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                                    )
                                } else {
                                    Text("點擊刷新", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }

                        // 刷新按鈕
                        IconButton(
                            onClick = { refreshCaptcha() },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Refresh, "刷新驗證碼", tint = MaterialTheme.colorScheme.primary)
                        }
                    }

                    if (autoRecognized && captchaCode.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("已自動輔助辨識驗證碼（如有誤差可手動修改）", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                // 錯誤訊息提示
                errorMessage?.let { err ->
                    Spacer(Modifier.height(10.dp))
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(20.dp))

                // 操作按鈕
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (trackingNumber.isBlank()) {
                                errorMessage = "請先輸入物流單號"
                                return@Button
                            }
                            if (carrier.needsCaptcha && captchaCode.isBlank() && !carrier.isUnderMaintenance) {
                                errorMessage = "請輸入驗證碼"
                                return@Button
                            }
                            focusManager.clearFocus()
                            scope.launch {
                                querying = true
                                errorMessage = null
                                val result = LogisticsClient.query(
                                    carrier = carrier,
                                    trackingNumber = trackingNumber,
                                    captchaCode = captchaCode,
                                    session = captchaSession
                                )
                                querying = false
                                if (result.success) {
                                    val newRecord = LogisticsRecord(
                                        carrier = carrier,
                                        trackingNumber = trackingNumber.trim(),
                                        note = note.trim(),
                                        latestStatus = result.statusText,
                                        stage = result.stage,
                                        storeName = result.storeName,
                                        expireDateText = result.expireDateText,
                                        historyDetails = result.history
                                    )
                                    onSaveRecord(newRecord)
                                    Toast.makeText(context, "已新增並查詢貨況", Toast.LENGTH_SHORT).show()
                                } else {
                                    errorMessage = result.errorMessage ?: "查詢失敗，請檢查單號或驗證碼"
                                    if (carrier.needsCaptcha) refreshCaptcha()
                                }
                            }
                        },
                        enabled = !querying,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (querying) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("查詢中...")
                        } else {
                            Text("開始查詢")
                        }
                    }
                }
            }
        }
    }
}
