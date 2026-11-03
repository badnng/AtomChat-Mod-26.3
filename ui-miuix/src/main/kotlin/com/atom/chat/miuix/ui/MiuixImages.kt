package com.atom.chat.miuix.ui

import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.atom.chat.miuix.api.MiuixHost
import org.jetbrains.skia.Image
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ContactsCircle
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalAvatarStyle = staticCompositionLocalOf { "ATOMCHAT" }
internal val LocalAnimationsEnabled = staticCompositionLocalOf { true }

@Composable
internal fun rememberHostImage(host: MiuixHost, key: String?, onPulse: (() -> Unit)? = null): Image? {
    val latestPulse by rememberUpdatedState(onPulse)
    var bytes by remember(host, key) {
        mutableStateOf(if (key.isNullOrEmpty()) null else host.image(key))
    }
    // The host's byte cache is asynchronous and is deliberately outside Compose state.
    // Poll only mounted image nodes; leaving a lazy viewport cancels this coroutine.
    LaunchedEffect(host, key) {
        if (!key.isNullOrEmpty()) while (isActive) {
            delay(100)
            latestPulse?.invoke()
            val next = host.image(key)
            val previous = bytes
            if (next !== previous && !(next?.contentEquals(previous) ?: (previous == null))) bytes = next
        }
    }
    val image = remember(key, bytes) { bytes?.let { runCatching { Image.makeFromEncoded(it) }.getOrNull() } }
    DisposableEffect(image) { onDispose { image?.close() } }
    return image
}

@Composable
internal fun HostImage(host: MiuixHost, key: String?, modifier: Modifier = Modifier,
                       description: String? = null, contentScale: ContentScale = ContentScale.Fit) {
    val image = rememberHostImage(host, key)
    if (image != null) ComposeImage(bitmap = remember(image) { image.toComposeImageBitmap() },
        contentDescription = description, modifier = modifier, contentScale = contentScale)
}

@Composable
internal fun PlayerAvatar(host: MiuixHost, player: MiuixHost.Player?, size: Dp = 42.dp, radius: Dp? = null) {
    var pokeTime by remember(host, player?.key()) { mutableLongStateOf(player?.let { host.pokeTime(it.key()) } ?: 0L) }
    val image = rememberHostImage(host, player?.avatarKey(), onPulse = {
        if (player != null) pokeTime = host.pokeTime(player.key())
    })
    val animations = LocalAnimationsEnabled.current
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(pokeTime, animations) {
        if (!animations || pokeTime == 0L || System.currentTimeMillis() - pokeTime > 700L) rotation.snapTo(0f)
        else rotation.animateTo(0f, keyframes {
            durationMillis = 600
            0f at 0
            -12f at 70
            10f at 150
            -8f at 230
            6f at 310
            -4f at 390
            2f at 470
            0f at 600
        })
    }
    val e33 = LocalAvatarStyle.current == "E33CHAT"
    val modifier = Modifier.size(size).graphicsLayer { rotationZ = rotation.value }.then(when {
        e33 -> Modifier
        radius != null -> Modifier.clip(RoundedCornerShape(radius))
        else -> Modifier.squircleClip(size / 3)
    })
    if (image == null) Icon(MiuixIcons.ContactsCircle, player?.displayName(), modifier,
        tint = MiuixTheme.colorScheme.primary)
    else Box(modifier) {
        ComposeImage(remember(image) { image.toComposeImageBitmap() }, player?.displayName(),
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            filterQuality = if (e33) FilterQuality.None else FilterQuality.Low)
        if (e33) HostImage(host, player?.hatKey(), Modifier.size(size * 1.1f).offset(-size * .05f, -size * .05f), contentScale = ContentScale.Crop)
    }
}
