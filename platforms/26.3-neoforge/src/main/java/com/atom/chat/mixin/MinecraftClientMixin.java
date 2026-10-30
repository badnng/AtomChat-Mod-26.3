package com.atom.chat.mixin;

import com.atom.chat.render.SkiaSupport;
import com.atom.chat.screen.AtomChatScreen;
import com.atom.chat.screen.AtomChatScreen.AtomChatOpenMode;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** World chat entry moved from Minecraft to Gui in 26.3. */
@Mixin(Gui.class)
public class MinecraftClientMixin {
    @ModifyArg(method = "openChatScreen", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/ChatComponent;openScreen(Lnet/minecraft/client/gui/components/ChatComponent$ChatMethod;Lnet/minecraft/client/gui/screens/ChatScreen$ChatConstructor;)V"), index = 1)
    private ChatScreen.ChatConstructor<?> atomchat$openChatScreen(ChatScreen.ChatConstructor<?> vanilla) {
        // Keep the native chat path available when the panel cannot initialize.
        if (!SkiaSupport.available()) {
            return vanilla;
        }
        // Let ChatComponent choose command prefixes and restore its draft first.
        return (text, restoredDraft) -> new AtomChatScreen(text, restoredDraft, AtomChatOpenMode.DIRECT_WORLD);
    }
}
