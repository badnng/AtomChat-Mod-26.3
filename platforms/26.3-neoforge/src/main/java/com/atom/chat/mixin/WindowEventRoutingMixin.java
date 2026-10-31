package com.atom.chat.mixin;

import com.atom.chat.render.WindowEventRouting;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Event;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep the private Skia window's resize/focus/close events out of Minecraft. */
@Mixin(Window.class)
public abstract class WindowEventRoutingMixin {
    @Shadow
    public abstract long handle();

    @Inject(method = "handleEvent", at = @At("HEAD"), cancellable = true)
    private void atomchat$routeWindowEvent(SDL_Event event, CallbackInfo ci) {
        if (!WindowEventRouting.accepts(event.type(), event.window().windowID(), SDLVideo.SDL_GetWindowID(handle()))) {
            ci.cancel();
        }
    }
}
