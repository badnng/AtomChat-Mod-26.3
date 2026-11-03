package com.atom.chat.miuix.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One instance per selected message. Input callbacks belong to the overlay owner. */
@Stable
internal class MessageContextMenuState {
    var isOpen by mutableStateOf(true)
        private set
    var reactionsOpen by mutableStateOf(false)
        private set

    fun showReactions() {
        if (isOpen) reactionsOpen = true
    }

    fun showActions() {
        reactionsOpen = false
    }

    fun dismiss() {
        isOpen = false
    }

    /** Escape returns from the reaction picker before closing the bubble menu. */
    fun back(): Boolean {
        if (!isOpen) return false
        if (reactionsOpen) showActions() else dismiss()
        return true
    }
}
