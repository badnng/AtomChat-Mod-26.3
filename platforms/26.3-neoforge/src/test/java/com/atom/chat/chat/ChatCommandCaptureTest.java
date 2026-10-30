package com.atom.chat.chat;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the production broadcast decision used before the HUD's own-echo suppression. */
class ChatCommandCaptureTest {
    @Test
    void signedSayBroadcastKeepsServerResultDespitePlayerIdentity() {
        Component result = Component.translatableWithFallback("chat.type.announcement", "[%s] %s",
                Component.literal("Dev"), Component.literal("server-command-result"));
        SenderMeta player = new SenderMeta(UUID.randomUUID(), "Dev", "Dev", "server-command-result", false,
                Component.literal("Dev"), Component.literal("server-command-result"));

        assertTrue(ChatClassifier.isSystemBroadcast(result, player),
                "Native player identity must not send command results into own-echo suppression");
        assertEquals("[Dev] server-command-result", result.getString());
    }

    @Test
    void signedEmoteIsSystemEvenWithPlayerMetadata() {
        Component result = Component.translatableWithFallback("chat.type.emote", "* %s %s", "Dev", "waves");
        SenderMeta player = new SenderMeta(UUID.randomUUID(), "Dev", "Dev", "waves", false);

        assertTrue(ChatClassifier.isSystemBroadcast(result, player));
    }

    @Test
    void nativeBroadcastRouteSurvivesFlattenedFinalComponent() {
        Component result = Component.literal("[Dev] server-command-result");
        SenderMeta broadcast = new SenderMeta(UUID.randomUUID(), "Dev", "Dev", "server-command-result", true);

        assertEquals(ChatClassifier.Route.UNKNOWN, ChatClassifier.classifyByKey(result));
        assertTrue(ChatClassifier.isSystemBroadcast(result, broadcast));
    }

    @Test
    void broadcastWithoutNativeMetadataStillReachesFeed() {
        Component result = Component.translatableWithFallback("chat.type.announcement", "[%s] %s", "Server", "hello");

        assertTrue(ChatClassifier.isSystemBroadcast(result, null));
    }

    @Test
    void outgoingPrivateEchoSentinelRemainsSuppressed() {
        SenderMeta suppressed = new SenderMeta(null, null, null, null, true, true, "Alex", null, null);
        Component result = Component.translatableWithFallback("commands.message.display.outgoing",
                "You whisper to %s: %s", "Alex", "hello");

        assertEquals(ChatClassifier.Route.PRIVATE, ChatClassifier.classifyByKey(result));
        assertFalse(ChatClassifier.isSystemBroadcast(result, suppressed),
                "Private echo suppression must not become a public system row");
    }

    @Test
    void ordinarySignedPlayerChatKeepsPublicEchoRouting() {
        Component result = Component.translatableWithFallback("chat.type.text", "<%s> %s", "Dev", "hello");
        SenderMeta player = new SenderMeta(UUID.randomUUID(), "Dev", "Dev", "hello", false);

        assertEquals(ChatClassifier.Route.PLAYER, ChatClassifier.classifyByKey(result));
        assertFalse(ChatClassifier.isSystemBroadcast(result, player));
        assertFalse(ChatClassifier.isSystemBroadcast(result, null));
    }
}
