package com.vfit.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** One choice in a [ComboField]: the text that goes into the field, plus an optional detail shown in the list. */
data class ComboOption<T>(val value: T, val text: String, val detail: String? = null)

/**
 * Text field with a dropdown of existing items, filtered as you type. Picking an item calls [onPick];
 * typing something that isn't in the list just updates the text (the caller treats it as new).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ComboField(
    label: String,
    text: String,
    options: List<ComboOption<T>>,
    onTextChange: (String) -> Unit,
    onPick: (ComboOption<T>) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val query = text.trim().lowercase()
    // show everything until the text narrows it; an exact match (just picked) also shows everything
    val shown = if (query.isEmpty() || options.any { it.text.lowercase() == query }) options
    else options.filter { query in it.text.lowercase() }
    ExposedDropdownMenuBox(expanded = expanded && shown.isNotEmpty(), onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = text,
            onValueChange = { onTextChange(it); expanded = true },
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = true,
            trailingIcon = { if (options.isNotEmpty()) ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = expanded && shown.isNotEmpty(), onDismissRequest = { expanded = false }) {
            for (o in shown) DropdownMenuItem(
                text = { Text(o.text) },
                trailingIcon = o.detail?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
                onClick = { onPick(o); expanded = false },
            )
        }
    }
}
