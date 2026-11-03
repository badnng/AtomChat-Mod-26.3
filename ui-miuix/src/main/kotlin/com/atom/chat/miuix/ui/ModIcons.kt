package com.atom.chat.miuix.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * AtomChat's own glyphs for actions without a matching Miuix icon.
 *
 * These retain the existing mod SVG geometry from AppIcons and DrawPrimitives.
 * Native vectors stay sharp at every UI scale and inherit Miuix Icon's theme tint;
 * they do not need an encoded image bridge or a polling image cache.
 */
internal object AtomChatIcons {
    val Filter: ImageVector by lazy {
        lineIcon("AtomChat.Filter", "M4 4.6 L16 4.6 L11.6 9.8 L11.6 14.2 L8.4 16 L8.4 9.8 Z")
    }

    val Announcement: ImageVector by lazy {
        lineIcon("AtomChat.Announcement", "M3.6 8.2 L6.2 8.2 L11.4 4.9 L11.4 15.1 L6.2 11.8 L3.6 11.8 Z" +
            " M14.6 7.6 A3.7 3.7 0 0 1 14.6 12.4")
    }

    val Player: ImageVector by lazy {
        lineIcon("AtomChat.Player", "M10 3.2 a3.4 3.4 0 1 1 0 6.8 a3.4 3.4 0 1 1 0 -6.8" +
            " M4.5 16.8 C4.5 13.6 6.9 11.8 10 11.8 C13.1 11.8 15.5 13.6 15.5 16.8")
    }

    val Emoji: ImageVector by lazy {
        lineIcon("AtomChat.Emoji", "M10 3 a7 7 0 1 0 0 14 a7 7 0 1 0 0 -14" +
            " M7 8.6 v1.3 M13 8.6 v1.3" +
            " M6.8 12.2 C8.5 14.3 11.5 14.3 13.2 12.2")
    }

    val QuickPhrase: ImageVector by lazy {
        lineIcon("AtomChat.QuickPhrase", "M13 2 3 14h9l-1 8 10-12h-9l1-8z", viewport = 24f)
    }

    private fun lineIcon(name: String, path: String, viewport: Float = 20f): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = viewport, viewportHeight = viewport).apply {
            addPath(pathData = addPathNodes(path), fill = null, stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round)
        }.build()
}
