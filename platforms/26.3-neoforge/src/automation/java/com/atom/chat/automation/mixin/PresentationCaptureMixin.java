package com.atom.chat.automation.mixin;

import com.atom.chat.automation.AutomationPresentationCapture;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The actual render tail, rather than game-thread extraction events. */
@Mixin(value = GameRenderer.class, remap = false)
public abstract class PresentationCaptureMixin {
    @Inject(method = "render", at = @At("TAIL"), require = 1)
    private void atomchatAutomationCapturePresented(CallbackInfo ci) {
        AutomationPresentationCapture.afterRender();
    }
}
