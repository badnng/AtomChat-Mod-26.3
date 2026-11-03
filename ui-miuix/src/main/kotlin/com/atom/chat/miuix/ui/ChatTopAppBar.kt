package com.atom.chat.miuix.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val TopBarSafeArea = 20.dp
private val PanelClockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

internal fun panelClockLabel(time: LocalTime): String = time.format(PanelClockFormat)

/** Shared panel chrome; titles and their transitions belong to upstream Miuix. */
@Composable
internal fun ChatTopAppBar(
    title: String,
    scrollBehavior: ScrollBehavior,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    titleAlpha: () -> Float = { 1f },
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        PanelClock()
        val titleColor = MiuixTheme.colorScheme.onSurface.copy(alpha = titleAlpha().coerceIn(0f, 1f))
        if (compact) {
            // A chat follows incoming content programmatically. A pinned official bar keeps
            // the title and message viewport stable during both following and history reads.
            SmallTopAppBar(
                title = title,
                titleColor = titleColor,
                color = Color.Transparent,
                scrollBehavior = scrollBehavior,
                defaultWindowInsetsPadding = false,
                navigationIcon = navigationIcon,
                actions = actions,
            )
        } else {
            TopAppBar(
                title = title,
                titleColor = titleColor,
                largeTitleColor = titleColor,
                color = Color.Transparent,
                scrollBehavior = scrollBehavior,
                defaultWindowInsetsPadding = false,
                navigationIcon = navigationIcon,
                actions = actions,
            )
        }
    }
}

/** The existing panel safe area reports local time, without inventing OS status indicators. */
@Composable
private fun PanelClock() {
    var label by remember { mutableStateOf(panelClockLabel(LocalTime.now())) }
    LaunchedEffect(Unit) {
        while (true) {
            label = panelClockLabel(LocalTime.now())
            delay(60_000L - Math.floorMod(System.currentTimeMillis(), 60_000L))
        }
    }
    Box(Modifier.fillMaxWidth().height(TopBarSafeArea), contentAlignment = Alignment.Center) {
        Text(label, modifier = Modifier.testTag("miuix-top-clock"),
            style = MiuixTheme.textStyles.footnote1.copy(fontFeatureSettings = "tnum"),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, softWrap = false)
    }
}
