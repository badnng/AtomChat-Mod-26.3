package com.atom.chat.miuix.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset

/** Shares dismissal input across the message list and composer without taking focus. */
internal class MessageShortcutInput {
    var dismiss: (() -> Boolean)? = null
    var press: ((Offset) -> Unit)? = null
    var release: (() -> Unit)? = null
    var popupActive: (() -> Boolean)? = null
}

internal val LocalMessageShortcutInput = staticCompositionLocalOf<MessageShortcutInput?> { null }
