package com.atom.chat.miuix.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/** All modal content is hosted by the panel's Miuix Scaffold, not a desktop OS window. */
@Composable
internal fun ColorDialog(show: Boolean, title: String, argb: Int, cancel: String, confirm: String,
                         onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    ModalRegistration(show)
    var draft by remember(show, argb) { mutableStateOf(Color(argb)) }
    OverlayDialog(show = show, title = title, onDismissRequest = onDismiss,
        defaultWindowInsetsPadding = false) {
        Column(Modifier.heightIn(max = 480.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                ColorPicker(color = draft, onColorChanged = { draft = it }, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(cancel, onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(confirm, onClick = { onConfirm(draft.toArgb()); onDismiss() },
                    modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}

@Composable
internal fun EditDialog(show: Boolean, title: String, initial: String, cancel: String, confirm: String,
                        onDismiss: () -> Unit, onConfirm: (String) -> Unit,
                        onDismissFinished: (() -> Unit)? = null) {
    ModalRegistration(show)
    var draft by remember(show, initial) { mutableStateOf(initial) }
    OverlayDialog(show = show, title = title, onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished, defaultWindowInsetsPadding = false) {
        Column {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            TextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth().focusRequester(focusRequester))
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(cancel, onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(confirm, onClick = { onConfirm(draft); onDismiss() },
                    modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}

@Composable
internal fun ConfirmDialog(show: Boolean, title: String, summary: String, cancel: String, confirm: String,
                           onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ModalRegistration(show)
    OverlayDialog(show = show, title = title, summary = summary, onDismissRequest = onDismiss,
        defaultWindowInsetsPadding = false) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(cancel, onClick = onDismiss, modifier = Modifier.weight(1f))
            TextButton(confirm, onClick = { onConfirm(); onDismiss() }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

@Composable
internal fun ChoiceDialog(show: Boolean, title: String, options: List<String>, selected: Int,
                          cancel: String, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    ModalRegistration(show)
    val latestSelect by rememberUpdatedState(onSelect)
    val entry = remember(options, selected) {
        DropdownEntry(options.mapIndexed { index, label ->
            DropdownItem(text = label, selected = selected == index, onClick = { latestSelect(index) })
        })
    }
    top.yukonga.miuix.kmp.popup.OverlayDropdownDialog(
        entry = entry, title = title, dialogButtonString = cancel, show = show,
        onDismiss = onDismiss, onDismissFinished = {}, dropdownColors = DropdownDefaults.dialogDropdownColors(),
    )
}
