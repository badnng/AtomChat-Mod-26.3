package com.atom.chat.miuix.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.atom.chat.miuix.api.MiuixHost
import com.atom.chat.miuix.api.MiuixHost.Command
import java.util.Locale
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun SettingsPage(host: MiuixHost, state: MiuixHost.State, padding: PaddingValues, modifier: Modifier) {
    if (state.settings().section() == "about" && state.route().page() == "settings_section") {
        AboutPage(host, state, padding, modifier)
        return
    }
    var modal by remember { mutableStateOf<MiuixHost.Setting?>(null) }
    var deletePreset by remember { mutableStateOf<MiuixHost.Preset?>(null) }
    val settings = state.settings()
    val cancel = host.label("atomchat.config.cancel")
    val save = host.label("atomchat.config.save")
    LazyColumn(modifier.overScrollVertical(), contentPadding = padding, flingBehavior = DesktopPageFlingBehavior) {
        item { Spacer(Modifier.height(12.dp)) }
        if (state.route().page() == "settings") {
            items(settings.sections(), key = { "section:" + it.id() }) { section ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
                    insideMargin = PaddingValues(0.dp)) {
                    ArrowPreference(title = section.title(), modifier = Modifier.testTag("settings-section-" + section.id()),
                        onClick = { host.action(Command.SETTINGS_SECTION, section.id()) })
                }
            }
        } else {
            items(settings.rows(), key = { "setting:" + it.id() }) { setting ->
                if (setting.kind() == "label") SmallTitle(setting.title())
                else Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
                    insideMargin = PaddingValues(0.dp)) {
                    val rowModifier = Modifier.testTag("setting-" + setting.id())
                    when (setting.kind()) {
                        "switch" -> SwitchPreference(checked = setting.checked(),
                            onCheckedChange = { host.action(Command.SET_SWITCH, setting.id(), it.toString()) },
                            title = setting.title(), summary = setting.description().takeIf { it.isNotBlank() },
                            enabled = setting.available(), modifier = rowModifier)
                        "slider" -> SettingSlider(host, setting, rowModifier)
                        "info" -> BasicComponent(title = setting.title(), summary = setting.description().takeIf { it.isNotBlank() },
                            modifier = rowModifier, enabled = setting.available(),
                            onClick = setting.uri()?.takeIf { it.isNotEmpty() }?.let { uri ->
                                { host.action(Command.OPEN_LINK, uri) }
                            })
                        else -> ArrowPreference(title = setting.title(), summary = setting.description().takeIf { it.isNotBlank() },
                            modifier = rowModifier, enabled = setting.available(), holdDownState = modal?.id() == setting.id(),
                            endActions = {
                                if (setting.kind() == "color") {
                                    IconButton(onClick = { modal = setting }, backgroundColor = Color(setting.color()), minWidth = 28.dp, minHeight = 28.dp) {}
                                } else if (!setting.displayValue().isNullOrEmpty()) Text(setting.displayValue(),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            },
                            onClick = {
                                when {
                                    setting.kind() == "color" || setting.kind() == "choice" || setting.destructive() -> modal = setting
                                    else -> host.action(Command.SETTINGS_ACTION, setting.id())
                                }
                            })
                    }
                }
            }
            items(settings.presets(), key = { "preset:" + it.id() }) { preset ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
                    insideMargin = PaddingValues(0.dp)) {
                    ArrowPreference(title = preset.title(), onClick = { host.action(Command.SETTINGS_ACTION,
                        "apply_preset", preset.id()) }, endActions = {
                        IconButton(onClick = { host.action(Command.SETTINGS_ACTION, "apply_preset", preset.id()) },
                            backgroundColor = Color(preset.accentColor()), minWidth = 28.dp, minHeight = 28.dp) {}
                        IconButton(onClick = { deletePreset = preset }) {
                            Icon(MiuixIcons.Delete, host.label("atomchat.config.phrase_delete"))
                        }
                    })
                }
            }
            items(settings.blockedPlayers(), key = { "blocked:" + it.key() }) { player ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
                    insideMargin = PaddingValues(0.dp)) {
                    BasicComponent(title = player.displayName(), startAction = { PlayerAvatar(host, player) },
                        endActions = { TextButton(host.label("atomchat.context.unblock"),
                            onClick = { host.action(Command.BLOCK, player.key()) }) })
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    deletePreset?.let { preset ->
        ConfirmDialog(true, host.label("atomchat.config.phrase_delete"), preset.title(), cancel,
            host.label("atomchat.config.phrase_delete"), onDismiss = { deletePreset = null },
            onConfirm = { host.action(Command.SETTINGS_ACTION, "delete_preset", preset.id()) })
    }
    val active = modal
    if (active != null) when {
        active.kind() == "color" -> ColorDialog(true, active.title(), active.color(), cancel, save,
            onDismiss = { modal = null }, onConfirm = { host.action(Command.SET_COLOR, active.id(), it.toString()) })
        active.kind() == "choice" -> ChoiceDialog(true, active.title(), active.options(), active.selectedIndex(), cancel,
            onDismiss = { modal = null }, onSelect = { host.action(Command.SET_CHOICE, active.id(), it.toString()) })
        active.destructive() -> ConfirmDialog(true, active.title(), active.description(), cancel, save,
            onDismiss = { modal = null }, onConfirm = { host.action(Command.SETTINGS_ACTION, active.id()) })
    }
}

@Composable
private fun SettingSlider(host: MiuixHost, setting: MiuixHost.Setting, modifier: Modifier) {
    var draft by remember(setting.id()) { mutableFloatStateOf(setting.value()) }
    // Changing this slider's viewport/density during its own gesture cancels
    // Compose's drag detector. Keep the native control stable until release.
    val changesGeometry = when (setting.id()) {
        "width", "scale", "contentscale" -> true
        else -> false
    }
    var pointerDown by remember(setting.id()) { mutableStateOf(false) }
    var pending by remember(setting.id()) { mutableStateOf(false) }
    LaunchedEffect(setting.value()) {
        if (!pointerDown && !pending) draft = setting.value()
    }
    val commitDraft by rememberUpdatedState {
        if (pending) {
            pending = false
            host.action(Command.SET_SLIDER, setting.id(), draft.toString())
        }
    }
    val pointerModifier = if (changesGeometry) Modifier.pointerInput(setting.id()) {
        awaitEachGesture {
            // Observe the press before the child can emit a value change.
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            pointerDown = true
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                } while (event.changes.any { it.pressed })
            } finally {
                pointerDown = false
                // Also settle a cancelled gesture, including window focus loss.
                commitDraft()
            }
        }
    } else Modifier
    val valueText = if (changesGeometry && pending) when (setting.id()) {
        "scale" -> "x" + String.format(Locale.ROOT, "%.2f", draft)
        "contentscale" -> "${(draft * 100).roundToInt()}%"
        else -> draft.roundToInt().toString()
    } else setting.displayValue()
    SliderPreference(value = draft, onValueChange = {
        draft = it
        if (changesGeometry && pointerDown) pending = true
        else host.action(Command.SET_SLIDER, setting.id(), it.toString(), commit = changesGeometry)
    }, onValueChangeFinished = {
        // Miuix also calls Finished when its drag detector is cancelled. Finished
        // alone must not commit geometry while a button is still held.
        if (changesGeometry) {
            if (!pointerDown) commitDraft()
        } else host.action(Command.SET_SLIDER, setting.id(), draft.toString())
    },
        title = setting.title(), summary = setting.description().takeIf { it.isNotBlank() }, valueText = valueText,
        enabled = setting.available(), modifier = modifier.then(pointerModifier), valueRange = setting.min()..setting.max(),
        steps = if (!changesGeometry && setting.step() > 0f)
            (((setting.max() - setting.min()) / setting.step()).roundToInt() - 1).coerceAtLeast(0) else 0,
        showKeyPoints = setting.id() == "scale",
        keyPoints = if (setting.id() == "scale") listOf(.75f, 1f, 1.25f, 1.5f) else null,
        magnetThreshold = 0f)
}
