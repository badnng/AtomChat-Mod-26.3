package com.atom.chat.miuix

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.LoadedFont
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontVariation as SkiaFontVariation
import org.jetbrains.skia.FontMgr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

import com.atom.chat.miuix.ui.bundledMiuixFont
import com.atom.chat.miuix.ui.miuixTextStyles
import top.yukonga.miuix.kmp.theme.defaultTextStyles

class MiuixTypographyTest {
    @Test
    fun stockMiuixWeightsShareTheBundledVariableFamily() {
        assertNull(defaultTextStyles().main.fontFamily)
        val styles = miuixTextStyles()
        assertNotNull(styles.main.fontFamily, "The Miuix test classpath must expose the outer mod font resource")
        assertSame(styles.main.fontFamily, styles.title1.fontFamily)
        assertSame(styles.main.fontFamily, styles.subtitle.fontFamily)
        assertNull(styles.main.fontWeight)
        assertNull(styles.title1.fontWeight)
        assertEquals(FontWeight.Bold, styles.subtitle.fontWeight)
        assertEquals(32.sp, styles.title1.fontSize)
    }

    @Test
    fun boldComposeFaceTargetsTheRealSevenHundredWeightAxis() {
        val font = bundledMiuixFont(FontWeight.W700)
        assertNotNull(font, "The bundled Noto Sans SC variable resource must load")
        assertTrue(font is LoadedFont, "The Compose face must be backed by the bundled bytes")

        val loadedFont = font as LoadedFont
        val setting = loadedFont.variationSettings.settings.single()
        assertEquals("wght", setting.axisName)
        assertEquals(700.0F, setting.toVariationValue(null))

        val data = Data.makeFromBytes(loadedFont.data)
        val base = requireNotNull(FontMgr.default.makeFromData(data))
        try {
            val weightAxis = base.variationAxes?.singleOrNull { it.tag == "wght" }
            assertNotNull(weightAxis, "The resource must be a real variable font")
            assertTrue(weightAxis!!.minValue <= 700.0F && weightAxis.maxValue >= 700.0F)

            val bold = base.makeClone(arrayOf(SkiaFontVariation("wght", 700.0F)))
            try {
                val appliedWeight = bold.variations?.singleOrNull { it.tag == "wght" }
                assertNotNull(appliedWeight)
                assertEquals(700.0F, appliedWeight!!.value, 0.0F)
            } finally {
                bold.close()
            }
        } finally {
            base.close()
            data.close()
        }
    }
}
