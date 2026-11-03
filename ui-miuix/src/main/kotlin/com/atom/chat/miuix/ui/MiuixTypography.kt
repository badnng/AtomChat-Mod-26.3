package com.atom.chat.miuix.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.font.Font as ComposeFont
import androidx.compose.ui.text.platform.Font
import com.atom.chat.miuix.NativeMiuixRuntime
import java.io.InputStream
import java.util.logging.Logger
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.defaultTextStyles

private const val BUNDLED_FONT_RESOURCE = "assets/atomchat/font/bundled.otf"
private const val FONT_FAMILY_ID = "atomchat-noto-sans-sc"

private val composeWeights = listOf(
    FontWeight.W400 to 400,
    FontWeight.W500 to 500,
    FontWeight.W700 to 700,
    FontWeight.W900 to 900,
)

private object MiuixTypographyResources {
    private val logger = Logger.getLogger("AtomChat.MiuixTypography")
    private val fontBytes: ByteArray? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        loadBundledFontBytes()
    }

    val fontFamily: FontFamily? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val bytes = fontBytes ?: return@lazy null
        FontFamily(composeWeights.map { (weight, axisWeight) -> createFont(bytes, weight, axisWeight) })
    }

    // Keep the resolver used for preloading and the real scene identical: desktop
    // font caches belong to a resolver's platform loader, not just the FontFamily.
    val fontFamilyResolver: FontFamily.Resolver by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        createFontFamilyResolver()
    }

    fun preload() {
        val family = fontFamily ?: return
        val resolver = fontFamilyResolver
        // These LoadedFont faces are blocking, CPU-only fonts. Resolving them also
        // loads Skiko's library/font manager before the first visible Compose frame.
        // The scene still uses normal resolution; there is no separate warmup
        // result or failure flag that can bypass that path.
        for ((weight, _) in composeWeights) {
            resolver.resolve(family, weight).value
        }
    }

    fun fontFor(weight: FontWeight): ComposeFont? {
        val bytes = fontBytes ?: return null
        return createFont(bytes, weight, weight.weight.coerceIn(100, 900))
    }

    private fun createFont(bytes: ByteArray, weight: FontWeight, axisWeight: Int): ComposeFont = Font(
        identity = "$FONT_FAMILY_ID-$axisWeight",
        getData = { bytes },
        weight = weight,
        style = FontStyle.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(axisWeight)),
    )

    private fun loadBundledFontBytes(): ByteArray? {
        val loaders = listOfNotNull(
            Thread.currentThread().contextClassLoader,
            NativeMiuixRuntime::class.java.classLoader,
            MiuixTypographyResources::class.java.classLoader,
            ClassLoader.getSystemClassLoader(),
        ).distinct()

        for (loader in loaders) {
            val stream = loader.getResourceAsStream(BUNDLED_FONT_RESOURCE) ?: continue
            val bytes = stream.use(InputStream::readAllBytes)
            logger.info("Loaded bundled Noto Sans SC variable font for Miuix")
            return bytes
        }

        logger.warning(
            "Bundled Noto Sans SC resource was not visible to the Miuix classloaders; " +
                "keeping the Compose platform font",
        )
        return null
    }
}

/**
 * Apply AtomChat's already-bundled variable CJK font to Miuix's stock text styles.
 * The style weights stay upstream defaults: regular text/titles use 400 and the
 * stock bold subtitle uses 700. The font bytes are loaded once from the outer mod
 * JAR and are not copied into ui-miuix's nested runtime.
 */
internal fun miuixTextStyles(): TextStyles {
    val base = defaultTextStyles()
    val family = MiuixTypographyResources.fontFamily ?: return base
    return base.copy(
        main = base.main.copy(fontFamily = family),
        paragraph = base.paragraph.copy(fontFamily = family),
        body1 = base.body1.copy(fontFamily = family),
        body2 = base.body2.copy(fontFamily = family),
        button = base.button.copy(fontFamily = family),
        footnote1 = base.footnote1.copy(fontFamily = family),
        footnote2 = base.footnote2.copy(fontFamily = family),
        headline1 = base.headline1.copy(fontFamily = family),
        headline2 = base.headline2.copy(fontFamily = family),
        subtitle = base.subtitle.copy(fontFamily = family),
        title1 = base.title1.copy(fontFamily = family),
        title2 = base.title2.copy(fontFamily = family),
        title3 = base.title3.copy(fontFamily = family),
        title4 = base.title4.copy(fontFamily = family),
    )
}

/** One face backed by the shared CJK variable-font bytes at the requested weight. */
internal fun bundledMiuixFont(weight: FontWeight): ComposeFont? = MiuixTypographyResources.fontFor(weight)

/** Prepare bundled font bytes and native typefaces without creating a Compose scene or GL target. */
internal fun preloadMiuixTypography() = MiuixTypographyResources.preload()

/** The real scene shares the exact loader/cache prepared during client setup. */
internal fun miuixFontFamilyResolver(): FontFamily.Resolver = MiuixTypographyResources.fontFamilyResolver
