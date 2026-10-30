package com.atom.chat.screen;

import com.atom.chat.platform.Platform;
import com.atom.chat.chat.DraftStore;
import com.atom.chat.chat.PlayerRef;
import com.atom.chat.nav.AppPage;
import com.atom.chat.nav.AtomChatState;
import com.atom.chat.nav.NavPage;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Native draft decisions and the installed production responder, with no window or GPU. */
class AtomChatDraftLifecycleTest {
    private Object previousClient;

    @BeforeEach
    void installWindowlessClient() throws Exception {
        Field instance = Minecraft.class.getDeclaredField("instance");
        instance.setAccessible(true);
        previousClient = instance.get(null);
        instance.set(null, allocate(Minecraft.class));
    }

    @AfterEach
    void restoreClient() throws Exception {
        set(Minecraft.class, null, "instance", previousClient);
    }

    @BeforeAll
    static void temporaryPlatform(@TempDir Path root) {
        Platform.install(new Platform.Provider() {
            @Override public Path configDir() { return root.resolve("config"); }
            @Override public Path gameDir() { return root; }
            @Override public boolean isModLoaded(String id) { return false; }
        });
    }

    @Test
    void atomChatNeverLetsNativeChatPublishItsCurrentComposerAsAPublicDraft() {
        AtomChatScreen screen = new AtomChatScreen("private draft", true, AtomChatScreen.AtomChatOpenMode.DIRECT_WORLD);
        assertTrue(screen.shouldDiscardDraft(),
                "private/page drafts belong to DraftSessionStore, not the native public draft slot");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theInstalledResponderClearsTheRealChatScreenDraftFlagOnEveryEdit() throws Exception {
        AtomChatScreen screen = new AtomChatScreen("restored", true, AtomChatScreen.AtomChatOpenMode.DIRECT_WORLD);
        Field draft = ChatScreen.class.getDeclaredField("isDraft");
        draft.setAccessible(true);
        assertTrue(draft.getBoolean(screen), "the native constructor starts with the supplied restored flag");

        // Only the native field's renderer is absent. Keep the real EditBox responder slot and
        // formatter list so SuggestorWiring installs exactly the callback used in production.
        EditBox input = allocate(EditBox.class);
        set(EditBox.class, input, "formatters", new ArrayList<>());
        set(EditBox.class, input, "value", "restored");
        set(ChatScreen.class, screen, "input", input);
        Field wiringField = AtomChatScreen.class.getDeclaredField("suggestorWiring");
        wiringField.setAccessible(true);
        SuggestorWiring wiring = (SuggestorWiring) wiringField.get(screen);
        wiring.install(null, screen, null, "restored");

        // Command querying requires a connected client; it is independent of draft ownership.
        // Removing that optional delegate leaves the installed responder and its real Host intact.
        set(SuggestorWiring.class, wiring, "chatInputSuggestor", null);
        Field responderField = EditBox.class.getDeclaredField("responder");
        responderField.setAccessible(true);
        Consumer<String> responder = (Consumer<String>) responderField.get(input);
        responder.accept("restored plus typed text");
        assertFalse(draft.getBoolean(screen), "the edited draft must not trigger native Backspace's clear-all branch");
        draft.setBoolean(screen, true);
        responder.accept("");
        assertFalse(draft.getBoolean(screen), "clearing the composer is also an edit");
    }

    @Test
    void firstWorldInitializationLoadsTheRetainedDraftWithoutOverwritingALiveResize() throws Exception {
        AtomChatScreen screen = new AtomChatScreen("", AtomChatScreen.AtomChatOpenMode.DIRECT_WORLD);
        draftStore(screen).seedWorld("retained public draft");
        ComposerPort composer = new ComposerPort("");
        set(AtomChatScreen.class, screen, "composerText", composer);

        initializeDraft(screen);
        assertEquals("retained public draft", composer.value);
        assertEquals(1, composer.cursorMoves);

        composer.value = "typing continued before resize";
        initializeDraft(screen);
        assertEquals("typing continued before resize", composer.value,
                "a resize owns live text, not the older saved conversation draft");
        assertEquals(1, composer.cursorMoves, "a resize must not reset the live caret");
    }

    @Test
    void restoredPrivatePageLoadsOnlyItsPartnersDraftAndProtectsBorrowedEditorText() throws Exception {
        List<NavPage> oldStack = AtomChatState.snapshot();
        PlayerRef partner = PlayerRef.of(UUID.fromString("11111111-1111-1111-1111-111111111111"), "Alice");
        try {
            AtomChatState.save(List.of(NavPage.of(AppPage.CHAT_LIST), NavPage.privateChat(partner)));
            AtomChatScreen screen = new AtomChatScreen("", AtomChatScreen.AtomChatOpenMode.RESTORE);
            draftStore(screen).seedWorld("public draft must stay separate");
            draftStore(screen).savePrivateDraft(partner, "retained private draft");
            ComposerPort composer = new ComposerPort("");
            set(AtomChatScreen.class, screen, "composerText", composer);

            initializeDraft(screen);
            assertEquals("retained private draft", composer.value);

            composer.value = "phrase or signature editor borrowed the shared field";
            initializeDraft(screen);
            assertEquals("phrase or signature editor borrowed the shared field", composer.value);
            assertEquals(1, composer.cursorMoves);
        } finally {
            AtomChatState.save(oldStack);
        }
    }

    @Test
    void aRootPageDoesNotAdoptAPublicDraftIntoItsSharedEditor() throws Exception {
        List<NavPage> oldStack = AtomChatState.snapshot();
        try {
            AtomChatState.save(List.of(NavPage.of(AppPage.PROFILE)));
            AtomChatScreen screen = new AtomChatScreen("", AtomChatScreen.AtomChatOpenMode.RESTORE);
            draftStore(screen).seedWorld("saved public text");
            ComposerPort composer = new ComposerPort("profile editor text");
            set(AtomChatScreen.class, screen, "composerText", composer);

            initializeDraft(screen);

            assertEquals("profile editor text", composer.value);
            assertEquals(0, composer.cursorMoves);
        } finally {
            AtomChatState.save(oldStack);
        }
    }

    private static DraftStore draftStore(AtomChatScreen screen) throws Exception {
        Field store = AtomChatScreen.class.getDeclaredField("draftStore");
        store.setAccessible(true);
        return (DraftStore) store.get(screen);
    }

    private static void initializeDraft(AtomChatScreen screen) throws Exception {
        var initialize = AtomChatScreen.class.getDeclaredMethod("initializeComposerDraft");
        initialize.setAccessible(true);
        initialize.invoke(screen);
    }

    private static final class ComposerPort implements DraftStore.ComposerTextPort {
        String value;
        int cursorMoves;
        ComposerPort(String value) { this.value = value; }
        @Override public String get() { return value; }
        @Override public void set(String text) { value = text; }
        @Override public void moveCursorToStart() { cursorMoves++; }
    }

    private static void set(Class<?> owner, Object object, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return type.cast(unsafeType.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type));
    }
}
