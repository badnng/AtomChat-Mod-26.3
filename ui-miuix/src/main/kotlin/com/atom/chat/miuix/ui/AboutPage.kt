package com.atom.chat.miuix.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atom.chat.miuix.api.MiuixHost
import com.atom.chat.miuix.api.MiuixHost.Command
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

internal val LocalAboutListState = staticCompositionLocalOf<LazyListState?> { null }

/** The scrolling brand row drives the shared compact bar and optional OS3 background. */
internal fun aboutScrollProgress(state: LazyListState): Float {
    if (state.firstVisibleItemIndex > 0) return 1f
    val hero = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "logoSpacer" }
    return if (hero != null && hero.size > 0) {
        (state.firstVisibleItemScrollOffset.toFloat() / hero.size).coerceIn(0f, 1f)
    } else 0f
}

// SettingsSectionPage.drawHero/drawAuthors, using UiTokens' existing 1.25 design scale.
// Keep these mapping-neutral; ui-miuix does not depend on the legacy Java renderer.
private object AboutDimensions {
    val heroHeight = 200.dp
    val iconSize = 68.75.dp
    val iconRadius = 18.75.dp
    val brandGap = 11.25.dp
    val wordmarkWidth = 176.25.dp
    val wordmarkHeight = 33.75.dp
    val versionHeight = 25.dp
    val authorHeight = 140.dp
    val authorImageSize = 50.dp
    val tileGap = 12.5.dp
}

@Composable
internal fun AboutPage(host: MiuixHost, state: MiuixHost.State, padding: PaddingValues, modifier: Modifier) {
    val listState = LocalAboutListState.current ?: rememberLazyListState()
    val progress by remember(listState) { derivedStateOf { aboutScrollProgress(listState) } }
    val rows = state.settings().rows()
    val version = rows.firstOrNull { it.group() == "about_version" }
        ?: rows.firstOrNull { it.id().startsWith("atomchat.settings.about.version") }
    val authors = rows.firstOrNull { it.group() == "about_credits" }?.displayValue()
        ?.split('\u00b7')?.map { it.trim() }?.takeIf { it.size == 2 && it.all(String::isNotEmpty) }
        ?: listOf("E33EPUS", "NoWordz")
    val modInfoGroups = setOf("about_version", "about_license", "about_source", "about_modinfo")
    val modInfo = rows.filter { it.group() in modInfoGroups }
        .sortedBy { when (it.group()) { "about_version" -> 0; "about_license" -> 1; "about_source" -> 2; else -> 0 } }
    val thirdParty = rows.filter { it.group() == "about_thirdparty" }
    val advanced = rows.filter { it.group() !in modInfoGroups && it.group() != "about_thirdparty" && it.group() != "about_credits" }
        .sortedBy { if (it.id() == "cache_clear") 0 else 1 }
    var confirmation by remember { mutableStateOf<MiuixHost.Setting?>(null) }
    Box(modifier) {
        AboutBackground(progress = { progress }, animations = state.animations())
        LazyColumn(modifier = Modifier.fillMaxSize().overScrollVertical(), state = listState,
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 12.dp)) {
            // Retain the shared progress key; this now contains the real scrolling brand, not a spacer.
            item(key = "logoSpacer") { AboutBrand(host, version) }
            item(key = "authors") {
                SmallTitle(host.label("atomchat.settings.about.authors"))
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("about-authors"),
                    horizontalArrangement = Arrangement.spacedBy(AboutDimensions.tileGap)) {
                    AboutAuthor(host, authors[0], "e33epus", Modifier.weight(1f))
                    AboutAuthor(host, authors[1], "nowordz", Modifier.weight(1f))
                }
            }
            item(key = "modinfo") {
                AboutGroup(host, "modinfo", "atomchat.settings.about.modinfo", modInfo) { confirmation = it }
            }
            item(key = "thirdparty") {
                AboutGroup(host, "thirdparty", "atomchat.settings.about.thirdparty.group", thirdParty) { confirmation = it }
            }
            item(key = "advanced") {
                AboutGroup(host, "advanced", "atomchat.settings.group.advanced", advanced) { confirmation = it }
            }
        }
    }
    confirmation?.let { setting ->
        ConfirmDialog(true, setting.title(), setting.description(), host.label("atomchat.config.cancel"),
            host.label("atomchat.config.save"), onDismiss = { confirmation = null },
            onConfirm = { host.action(Command.SETTINGS_ACTION, setting.id()) })
    }
}

@Composable
private fun AboutBrand(host: MiuixHost, version: MiuixHost.Setting?) {
    val wordmark = rememberHostImage(host, "about-wordmark")
    Column(Modifier.fillMaxWidth().height(AboutDimensions.heroHeight).padding(horizontal = 25.dp)
        .testTag("about-hero"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        HostImage(host, "about-logo", Modifier.size(AboutDimensions.iconSize)
            .clip(RoundedCornerShape(AboutDimensions.iconRadius)).testTag("about-logo"), description = "AtomChat")
        Spacer(Modifier.height(AboutDimensions.brandGap))
        Box(Modifier.widthIn(max = AboutDimensions.wordmarkWidth).fillMaxWidth()
            .height(AboutDimensions.wordmarkHeight).testTag("about-name"), contentAlignment = Alignment.Center) {
            if (wordmark != null) ComposeImage(remember(wordmark) { wordmark.toComposeImageBitmap() }, "AtomChat",
                Modifier.fillMaxSize().testTag("about-wordmark"), colorFilter = ColorFilter.tint(Color(0xFFC44337), BlendMode.SrcIn))
            else Text(host.label("atomchat.screen.title"), fontSize = 27.5.sp)
        }
        Spacer(Modifier.height(AboutDimensions.brandGap))
        Text(version?.displayValue().orEmpty(), Modifier.height(AboutDimensions.versionHeight)
            .clip(RoundedCornerShape(6.25.dp))
            .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .05f))
            .then(if (!version?.uri().isNullOrEmpty()) Modifier.clickable {
                version?.uri()?.let { host.action(Command.OPEN_LINK, it) }
            } else Modifier)
            .padding(horizontal = 10.dp).wrapContentHeight(Alignment.CenterVertically).testTag("about-version"),
            fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun AboutAuthor(host: MiuixHost, name: String, key: String, modifier: Modifier) {
    Card(modifier.height(AboutDimensions.authorHeight).testTag("about-author-$key"), insideMargin = PaddingValues(0.dp)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            HostImage(host, "about-author-$key", Modifier.size(AboutDimensions.authorImageSize)
                .testTag("about-author-image-$key"), description = name)
            Spacer(Modifier.height(12.5.dp))
            Text(name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun AboutGroup(host: MiuixHost, id: String, label: String, rows: List<MiuixHost.Setting>,
                       onConfirm: (MiuixHost.Setting) -> Unit) {
    var expanded by rememberSaveable(id) { mutableStateOf(true) }
    Column {
        BasicComponent(title = host.label(label), modifier = Modifier.testTag("about-group-$id"),
            endActions = {
                Icon(MiuixIcons.Back, null, Modifier.size(16.dp).rotate(if (expanded) 90f else -90f),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }, onClick = { expanded = !expanded })
        AnimatedVisibility(expanded) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("about-card-$id"),
                insideMargin = PaddingValues(0.dp)) {
                for ((index, setting) in rows.withIndex()) {
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 17.5.dp))
                    val rowModifier = Modifier.testTag("setting-" + setting.id())
                    if (setting.kind() == "switch") {
                        SwitchPreference(title = setting.title(), summary = setting.description(),
                            checked = setting.checked(), enabled = setting.available(), modifier = rowModifier,
                            onCheckedChange = { host.action(Command.SET_SWITCH, setting.id(), it.toString()) })
                    } else {
                        ArrowPreference(title = setting.title(), modifier = rowModifier, enabled = setting.available(),
                            summary = if (setting.kind() == "info") null else setting.description(),
                            endActions = {
                                if (setting.kind() == "info") Text(setting.displayValue(),
                                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                            }, onClick = {
                                when {
                                    !setting.uri().isNullOrEmpty() -> host.action(Command.OPEN_LINK, setting.uri())
                                    setting.destructive() -> onConfirm(setting)
                                    setting.kind() != "info" -> host.action(Command.SETTINGS_ACTION, setting.id())
                                }
                            })
                    }
                }
            }
        }
    }
}
