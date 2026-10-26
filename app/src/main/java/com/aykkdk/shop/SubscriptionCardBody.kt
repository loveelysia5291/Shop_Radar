package com.aykkdk.shop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** All subscription cards reserve identical text slots, including the optional status. */
@Composable
internal fun SubscriptionCardHeading(eyebrow: String, title: String, menuButton: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(eyebrow, style = MaterialTheme.typography.bodySmall,
                minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, Modifier.fillMaxWidth().padding(bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        menuButton()
    }
}

@Composable
internal fun SubscriptionCardBody(eyebrow: String, title: String, target: RefreshTarget,
    metadata: List<String>, status: String, onStatus: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Column(Modifier.padding(10.dp)) {
        AutomaticRefreshMenu(target, compactFeedback = true, inCard = true,
            header = { menuButton -> SubscriptionCardHeading(eyebrow, title, menuButton) })
        repeat(metadata.size) { index ->
            Text(metadata.getOrNull(index).orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (status.isNotBlank()) Text(status, Modifier.fillMaxWidth().clickable(onClickLabel = "查看完整刷新狀態", onClick = onStatus),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), content = actions)
    }
}

/** One minute ticker for the visible subscription page; stops when the Activity is backgrounded. */
@Composable
internal fun rememberRefreshClock(): Long {
    val owner = LocalLifecycleOwner.current
    val now by produceState(System.currentTimeMillis(), owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = System.currentTimeMillis()
                delay(60_000)
            }
        }
    }
    return now
}
