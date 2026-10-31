package com.atom.chat.mixin;

import com.atom.chat.render.PanelBlurRenderer;
import com.atom.chat.render.SkiaGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Capture only rendered world frames, before their GUI is composed. */
@Mixin(GameRenderer.class)
public abstract class GameRendererWorldCaptureMixin {
    @Shadow @Final
    private GameRenderState gameRenderState;

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
    private void atomchat$captureWorldBeforeGui(CallbackInfo ci) {
        // The live screen can change while the extracted frame is still a
        // no-world transition. Its cleared target is not a world snapshot.
        if (gameRenderState.shouldRenderLevel) {
            PanelBlurRenderer.captureWorld();
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void atomchat$recordPresentedGui(CallbackInfo ci) {
        SkiaGraphics.framePresented();
    }
}
