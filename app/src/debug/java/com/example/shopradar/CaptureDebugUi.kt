package com.example.shopradar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object CaptureDebugPolicy {
    fun visibleReader(request: SearchCapture?): Boolean = request?.let {
        it.market == Market.SHOPEE && it.quickSearch && !it.scanSubscription && !it.infiniteSearch
    } == true
    @Suppress("UNUSED_PARAMETER") fun searchMillis(platform: String, normal: Long): Long = normal
}

/** Shows the exact reader used by extraction, not a separate browser or cookie jar. */
@Composable
internal fun CaptureReaderSurface(request: SearchCapture, liveText: String, onStop: () -> Unit,
    content: @Composable (Modifier) -> Unit) {
    if (!CaptureDebugPolicy.visibleReader(request)) { content(Modifier.fillMaxSize()); return }
    var stopping by remember(request) { mutableStateOf(false) }
    fun stop() { if (!stopping) { stopping = true; onStop() } }
    Dialog(onDismissRequest = { stop() }, properties = DialogProperties(
        dismissOnClickOutside = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Debug 蝦皮搜尋", modifier = Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { stop() }, enabled = !stopping) { Text(if (stopping) "保存診斷中" else "結束診斷") }
                }
                Text(liveText.ifBlank { "正在開啟搜尋頁…" }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                content(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
internal fun CaptureDiagnosticDialog(message: String, report: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember(report) { MarketplaceHtmlArchives.sessionId(report) }
    val platformSlug = remember(report) { MarketplaceHtmlArchives.platformSlug(report) }
    var expanded by remember(report) { mutableStateOf(false) }
    var confirmExport by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportNote by remember { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null && session != null) {
            exporting = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val files = MarketplaceHtmlArchives.files(context, session)
                        require(files.any { it.extension == "html" }) { "沒有可匯出的 HTML，請重新搜尋。" }
                        val stream = context.contentResolver.openOutputStream(uri, "w") ?: error("無法寫入指定位置。")
                        ZipOutputStream(stream).use { zip ->
                            for (file in files) {
                                zip.putNextEntry(ZipEntry(file.name))
                                file.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    }
                }
                exporting = false
                exportNote = result.fold({ "HTML 診斷 ZIP 已保存。請用文字編輯器檢查內容，不要直接用瀏覽器開啟 HTML。" },
                    { it.message ?: "匯出失敗，請重試。" })
            }
        }
    }
    fun close() {
        if (exporting) return
        exporting = true
        scope.launch {
            session?.let { id -> withContext(Dispatchers.IO) { MarketplaceHtmlArchives.clear(context, id) } }
            onClose()
        }
    }
    AlertDialog(onDismissRequest = { close() }, title = { Text("Debug 抓取診斷") },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            Text(message)
            if (session != null) {
                Text("已嘗試保存約 4 秒與失敗時的${if (platformSlug == "shopee") "蝦皮" else " Facebook"}搜尋頁 HTML，不會自動傳送。")
                TextButton(onClick = { confirmExport = true }, enabled = !exporting) { Text("匯出 HTML 診斷（ZIP）") }
            }
            if (expanded) Text(report, style = MaterialTheme.typography.bodySmall)
            if (exportNote.isNotBlank()) Text(exportNote)
        } },
        confirmButton = { TextButton(onClick = {
            if (!expanded) expanded = true else {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Debug 抓取診斷", "$message\n$report"))
                Toast.makeText(context, "已複製診斷", Toast.LENGTH_SHORT).show()
            }
        }) { Text(if (expanded) "複製診斷" else "查看診斷") } },
        dismissButton = { TextButton(onClick = { close() }, enabled = !exporting) { Text("關閉") } })
    if (confirmExport) AlertDialog(onDismissRequest = { confirmExport = false }, title = { Text("確認匯出 HTML") },
        text = { Text("HTML 會包含搜尋詞、商品、頁面腳本，可能還有帳號資料或權杖。雖已移除表單輸入值，並未保證全部去識別化。僅保存到你選擇的位置；分享前請用文字編輯器檢查，不要公開上傳。") },
        confirmButton = { TextButton(onClick = {
            confirmExport = false
            save.launch("ShopRadar-$platformSlug-html-${System.currentTimeMillis()}.zip")
        }) { Text("確認並選擇保存位置") } },
        dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("取消") } })
}
