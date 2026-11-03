package com.atom.chat.automation.mixin;

import com.atom.chat.automation.AutomationRenderStallDiagnostics;
import com.atom.chat.automation.AutomationRenderStallDiagnostics.Stage;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Development source set only: observe vanilla calls without replacing their behavior. */
@Mixin(value = Minecraft.class, remap = false)
public abstract class AutomationRenderStallMixin {
    @Inject(method = "renderFrame(Z)V", at = @At("HEAD"), require = 1)
    private void atomchatAutomationBeginFrame(boolean advanceGameTime, CallbackInfo ci) {
        AutomationRenderStallDiagnostics.beginFrame();
    }

    @Inject(method = "renderFrame(Z)V", at = @At("RETURN"), require = 1)
    private void atomchatAutomationEndFrame(boolean advanceGameTime, CallbackInfo ci) {
        AutomationRenderStallDiagnostics.enter(Stage.IDLE);
    }

    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;render()V"), require = 1)
    private void atomchatAutomationBeforeGameRender(boolean advanceGameTime, CallbackInfo ci) {
        AutomationRenderStallDiagnostics.enter(Stage.GAME_RENDER);
    }

    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;render()V", shift = At.Shift.AFTER), require = 1)
    private void atomchatAutomationAfterGameRender(boolean advanceGameTime, CallbackInfo ci) {
        AutomationRenderStallDiagnostics.enter(Stage.FRAME);
    }

    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V"), require = 1)
    private void atomchatAutomationBeforeSubmit(boolean advanceGameTime, CallbackInfo ci) {
        AutomationRenderStallDiagnostics.enter(Stage.SUBMIT);
    }

    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V", shift = At.Shift.AFTER), require = 1)
    private void atomchatAutomationAfterSubmit(boolean advanceGameTime, CallbackInfo ci) {
        AutomationRenderStallDiagnostics.enter(Stage.FRAME);
    }

    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V"), require = 1)
    private void atomchatAutomationBeforePresent(boolean advanceGameTime, CallbackInfo ci) {
        AutomationRenderStallDiagnostics.enter(Stage.PRESENT);
    }

    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V", shift = At.Shift.AFTER), require = 1)
    private void atomchatAutomationAfterPresent(boolean advanceGameTime, CallbackInfo ci) {
        AutomationRenderStallDiagnostics.enter(Stage.FRAME);
    }

    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V"), require = 1)
    private void atomchatAutomationBeforeLimiter(boolean advanceGameTime, CallbackInfo ci) {
        AutomationRenderStallDiagnostics.enter(Stage.FRAME_LIMITER);
    }

    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V", shift = At.Shift.AFTER), require = 1)
    private void atomchatAutomationAfterLimiter(boolean advanceGameTime, CallbackInfo ci) {
        AutomationRenderStallDiagnostics.enter(Stage.FRAME);
    }
}
