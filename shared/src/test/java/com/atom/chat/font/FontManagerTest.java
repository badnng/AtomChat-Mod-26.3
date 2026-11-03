package com.atom.chat.font;

import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontVariation;
import io.github.humbleui.skija.FontVariationAxis;
import io.github.humbleui.skija.Typeface;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FontManagerTest {
    @Test
    void bodyAndBoldFontsUseIndependentVariableWeightsInsteadOfSyntheticEmboldening() {
        Font body = FontManager.font(18.0F);
        Font font = FontManager.boldFont(18.0F);
        assertFalse(font.isEmboldened(), "The bundled variable font must provide real bold outlines");

        Typeface bodyFace = body.getTypeface();
        Typeface face = font.getTypeface();
        assertNotNull(bodyFace, "The body font must resolve a concrete typeface");
        assertNotNull(face, "The bold font must resolve a concrete typeface");
        try {
            assertWeight(bodyFace, 500.0F);
            assertWeight(face, 700.0F);

            FontVariationAxis axis = Arrays.stream(face.getVariationAxes())
                    .filter(variationAxis -> "wght".equals(variationAxis.getTag()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(axis, "The bundled typeface must expose a real weight axis");
            assertTrue(axis.getMinValue() <= 700.0F && axis.getMaxValue() >= 700.0F);
        } finally {
            bodyFace.close();
            face.close();
        }
    }

    private static void assertWeight(Typeface face, float expectedWeight) {
        FontVariation weight = Arrays.stream(face.getVariations())
                .filter(variation -> "wght".equals(variation.getTag()))
                .findFirst()
                .orElse(null);
        assertNotNull(weight, "The typeface must carry the wght variation");
        assertEquals(expectedWeight, weight.getValue(), 0.0F);
    }
}
