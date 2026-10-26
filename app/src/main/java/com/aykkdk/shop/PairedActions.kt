package com.aykkdk.shop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Settings actions stay in two equal columns, including narrow cards/large fonts. */
@Composable
internal fun PairedActions(left: String, onLeft: () -> Unit, right: String, onRight: () -> Unit,
                           rightEnabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onLeft, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text(left) }
        TextButton(onClick = onRight, enabled = rightEnabled, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text(right) }
    }
}
