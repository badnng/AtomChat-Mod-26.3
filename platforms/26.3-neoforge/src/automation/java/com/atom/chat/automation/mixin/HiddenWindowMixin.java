package com.atom.chat.automation.mixin;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Only loaded by runAutomation: create the GPU window hidden from the outset. */
@Mixin(value = Window.class, remap = false)
public abstract class HiddenWindowMixin {
    @ModifyArg(method = "createWindow", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J"),
            index = 3, require = 1)
    private long atomchatAutomationHiddenWindow(long flags) {
        if (Boolean.getBoolean("atomchat.automation")
                && Boolean.parseBoolean(System.getProperty("atomchat.automation.hideWindow", "true"))) {
            System.setProperty("atomchat.automation.windowCreatedHidden", "true");
            return flags | SDLVideo.SDL_WINDOW_HIDDEN;
        }
        return flags;
    }
}
