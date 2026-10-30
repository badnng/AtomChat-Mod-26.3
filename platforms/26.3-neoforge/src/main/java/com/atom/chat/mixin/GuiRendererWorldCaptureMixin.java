package com.atom.chat.mixin;

import com.atom.chat.render.PanelBlurRenderer;
import com.atom.chat.render.SkiaGraphics;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps asynchronous world readback outside GUI extraction and composition. */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererWorldCaptureMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void atomchat$captureWorldBeforeGui(CallbackInfo ci) {
        PanelBlurRenderer.captureWorld();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void atomchat$recordPresentedGui(CallbackInfo ci) {
        SkiaGraphics.framePresented();
    }
}
