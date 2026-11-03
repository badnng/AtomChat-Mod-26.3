package com.atom.chat.miuix.ui

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope

/**
 * Desktop wheels already supply their scroll deltas, including trackpad inertia.
 * Return the unused velocity so the parent Miuix bar receives the end-of-scroll
 * notification and can finish expanding at the list boundary. Compose's default
 * wheel behavior skips that notification and can leave a partially folded bar.
 */
internal object DesktopPageFlingBehavior : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float = initialVelocity
}
