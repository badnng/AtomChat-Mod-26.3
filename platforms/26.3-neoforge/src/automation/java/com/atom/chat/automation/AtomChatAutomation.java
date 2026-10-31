package com.atom.chat.automation;

import com.atom.chat.chat.ChatStore;
import com.atom.chat.audio.FeedbackCue;
import com.atom.chat.audio.FeedbackSoundController;
import com.atom.chat.chat.DraftSessionStore;
import com.atom.chat.config.AtomChatConfig;
import com.atom.chat.config.AtomChatServerConfig;
import com.atom.chat.emote.EmoteStore;
import com.atom.chat.history.ChatHistory;
import com.atom.chat.history.HistoryStore;
import com.atom.chat.image.ImageLoader;
import com.atom.chat.nav.AppPage;
import com.atom.chat.nav.PageTransitionDirector;
import com.atom.chat.net.MediaCompanionClient;
import com.atom.chat.net.PackSyncClient;
import com.atom.chat.notification.NotificationBanner;
import com.atom.chat.notification.NotificationController;
import com.atom.chat.profile.ProfileSync;
import com.atom.chat.profile.ProfileField;
import com.atom.chat.reaction.ReactionClient;
import com.atom.chat.render.PanelBlurRenderer;
import com.atom.chat.render.SkiaGraphics;
import com.atom.chat.screen.AtomChatScreen;
import com.atom.chat.screen.ScreenMetrics;
import com.atom.chat.settings.SettingsSection;
import com.atom.chat.settings.SettingsSectionPage;
import com.atom.chat.settings.SettingsCatalog;
import com.atom.chat.settings.SettingsItem;
import com.atom.chat.theme.AppearancePresetStore;
import com.atom.chat.theme.ThemeService;
import com.atom.chat.ui.ComposerInput;
import com.atom.chat.ui.UiLayout;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/** Development-only real-client probe. Compiled by the automation source set, never shipped. */
public final class AtomChatAutomation {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();
    private static final List<Step> STEPS = new ArrayList<>();
    private static final long START = System.nanoTime();
    private static final boolean HISTORY_ONLY = System.getenv("ATOMCHAT_AUTOMATION_HISTORY_WORLD") != null;
    private static final boolean PERFORMANCE = Boolean.getBoolean("atomchat.automation.performance");
    private static final String TOKEN = System.getenv().getOrDefault("ATOMCHAT_AUTOMATION_HISTORY_TOKEN",
            "atomchat-automation-" + System.currentTimeMillis());
    private static final String WORLD = System.getenv().getOrDefault("ATOMCHAT_AUTOMATION_HISTORY_WORLD", TOKEN);
    private static Path report;
    private static int index;
    private static long entered;
    private static boolean begun;
    private static volatile boolean finished;
    private static AtomChatScreen panel;
    private static Path history;
    private static long draftGeneration;
    private static volatile String mediaUrl;
    private static volatile String mediaError;
    private static byte[] mediaBytes;
    private static CompletableFuture<byte[]> downloaded;
    private static long startingFrames;
    private static long startingCaptures;
    private static CompletableFuture<Boolean> integratedPlayerReady;
    private static final Map<String, AtomicInteger> SOUND_PLAYS = new ConcurrentHashMap<>();
    private static int soundBaseline;
    private static volatile String gifUrl;
    private static volatile String gifError;
    private static final java.util.Set<Integer> GIF_COLORS = new java.util.HashSet<>();
    private static final Map<String, Boolean> SWITCH_ORIGINALS = new LinkedHashMap<>();
    private static final java.util.Set<String> SWITCH_VERIFIED = new java.util.LinkedHashSet<>();
    private static final java.util.Set<String> UNRENDERED_SWITCHES = new java.util.LinkedHashSet<>();
    private static long settledAtFrame = -1;
    private static net.minecraft.client.gui.screens.Screen lastConfirmedPrompt;
    private static String lastLoadScreen = "";
    private static CompletableFuture<Void> presentationCapture;
    private static float originalCaptureScale;
    private static float originalCaptureHeight;

    private AtomChatAutomation() { }

    public static void install() {
        if (!Boolean.getBoolean("atomchat.automation")) return;
        if (!System.getProperty("atomchat.automation.pairRole", "").isEmpty()) {
            AtomChatPairAutomation.install();
            return;
        }
        if (!INSTALLED.compareAndSet(false, true)) return;
        report = Path.of(System.getProperty("atomchat.automation.report",
                "automation-report.json")).toAbsolutePath();
        buildSteps();
        NeoForge.EVENT_BUS.addListener(AtomChatAutomation::tick);
        NeoForge.EVENT_BUS.addListener(AtomChatAutomation::soundStarted);
        if (PERFORMANCE) NeoForge.EVENT_BUS.addListener(AutomationPerformance::frame);
        // A stalled renderer/server must still produce evidence and release the process.
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(Long.getLong("atomchat.automation.timeoutSeconds", 600L) * 1000L); }
            catch (InterruptedException ignored) { return; }
            synchronized (RESULTS) {
                if (finished) return;
                result("process-watchdog", "FAIL", "Global deadline exceeded; client thread may be stalled");
                finishReport("FAIL");
            }
            Runtime.getRuntime().halt(2);
        }, "atomchat-automation-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        result("automation-installed", "PASS", "Development source set loaded; no desktop input automation");
    }

    private static void buildSteps() {
        if (HISTORY_ONLY) {
            add("history-diagnostic-title-ready", 90, () -> { }, () -> Minecraft.getInstance().isGameLoadFinished());
            add("history-diagnostic-fixture-scope", 5, () -> {
                requireFixtureWorld();
                if (!Boolean.getBoolean("atomchat.automation.windowCreatedHidden")) throw new AssertionError("Hidden-window mixin absent");
                AtomChatConfig.get().chatHistoryEnabled = true;
                if (!AtomChatConfig.saveChecked(AtomChatConfig.get())) throw new AssertionError("History config save failed");
                Minecraft.getInstance().options.pauseOnLostFocus = false;
                Minecraft.getInstance().options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
            }, () -> true);
            add("history-diagnostic-rejoin-existing-fixture", 90, () -> {
                integratedPlayerReady = null;
                Minecraft.getInstance().createWorldOpenFlows().openWorld(WORLD, () -> { });
            }, AtomChatAutomation::worldReadyWithFixturePrompts);
            add("history-diagnostic-own-message-reloaded", 10, () -> { },
                    () -> ChatStore.get().snapshot().stream().anyMatch(m -> TOKEN.equals(m.getContentText()) && m.isOwn()));
            add("history-diagnostic-disconnect", 20, () -> Minecraft.getInstance().disconnectWithSavingScreen(),
                    () -> Minecraft.getInstance().player == null && Minecraft.getInstance().level == null);
            return;
        }
        add("title-ready", 60, () -> { }, () -> Minecraft.getInstance().isGameLoadFinished());
        add("isolated-directory", 2, () -> {
            Path game = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
            if (!game.getFileName().toString().startsWith("automation")) {
                throw new IllegalStateException("Refusing to mutate non-automation game directory: " + game);
            }
            Minecraft.getInstance().options.pauseOnLostFocus = false;
            Minecraft.getInstance().options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
            Minecraft.getInstance().options.framerateLimit().set(PERFORMANCE ? 260 : 60);
            Minecraft.getInstance().getFramerateLimitTracker().setFramerateLimit(PERFORMANCE ? 260 : 60);
            if (PERFORMANCE) Minecraft.getInstance().options.enableVsync().set(false);
            Minecraft.getInstance().options.renderDistance().set(2);
            Minecraft.getInstance().options.simulationDistance().set(2);
            AtomChatConfig.get().debug = true;
            AtomChatConfig.get().chatHistoryEnabled = true;
            AtomChatConfig.get().blurEnabled = true;
            if (!AtomChatConfig.saveChecked(AtomChatConfig.get())) throw new AssertionError("Initial config save failed");
            var serverConfig = AtomChatServerConfig.get();
            serverConfig.hostingEnabled = true;
            serverConfig.uploadCooldownMs = 0;
            AtomChatServerConfig.save(serverConfig);
            if (Boolean.parseBoolean(System.getProperty("atomchat.automation.hideWindow", "true"))) {
                Class<?> sdl = Class.forName("org.lwjgl.sdl.SDLVideo");
                Object ok = sdl.getMethod("SDL_HideWindow", long.class)
                        .invoke(null, Minecraft.getInstance().getWindow().handle());
                if (Boolean.FALSE.equals(ok)) throw new IllegalStateException("SDL_HideWindow failed");
                long flags = (long) sdl.getMethod("SDL_GetWindowFlags", long.class)
                        .invoke(null, Minecraft.getInstance().getWindow().handle());
                long hidden = sdl.getField("SDL_WINDOW_HIDDEN").getLong(null);
                if ((flags & hidden) == 0) throw new AssertionError("SDL window is not hidden");
                if (!Boolean.getBoolean("atomchat.automation.windowCreatedHidden")) {
                    throw new AssertionError("Automation window-creation mixin was not loaded");
                }
            }
        }, () -> true);
        if (PERFORMANCE) {
            add("performance-resolution", 30,
                    () -> {
                        var window = Minecraft.getInstance().getWindow();
                        if (window.getWidth() == Integer.getInteger("atomchat.automation.width", 1920)
                                && window.getHeight() == Integer.getInteger("atomchat.automation.height", 1080)) return;
                        window.setWindowed(
                            Integer.getInteger("atomchat.automation.width", 1920),
                            Integer.getInteger("atomchat.automation.height", 1080));
                    },
                    () -> Minecraft.getInstance().getWindow().getWidth() == Integer.getInteger("atomchat.automation.width", 1920)
                            && Minecraft.getInstance().getWindow().getHeight() == Integer.getInteger("atomchat.automation.height", 1080));
        }
        add("create-singleplayer-world", 120, () -> Minecraft.getInstance().createWorldOpenFlows()
                .createFreshLevel(WORLD,
                        new LevelSettings(WORLD, GameType.CREATIVE,
                                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                                true, WorldDataConfiguration.DEFAULT),
                        new WorldOptions(263L, false, false), WorldPresets::createTestWorldDimensions,
                        new TitleScreen()), AtomChatAutomation::worldReady);
        add("enable-debug", 2, () -> AtomChatConfig.get().debug = true, () -> true);
        if (PERFORMANCE) {
            add("performance-world-warmup", 30, () -> Minecraft.getInstance().setScreenAndShow(null),
                    () -> ageMs() >= AutomationPerformance.warmupMs());
            add("performance-no-panel", AutomationPerformance.timeoutSeconds(),
                    () -> AutomationPerformance.begin("no-panel"), AutomationPerformance::complete);
            add("performance-open-chat", 30, () -> {
                panel = new AtomChatScreen("");
                Minecraft.getInstance().setScreenAndShow(panel);
                panel.openWorldChat();
            }, AtomChatAutomation::panelFrameReady);
            add("performance-chat-warmup", 30, () -> { },
                    () -> ageMs() >= AutomationPerformance.warmupMs());
            add("performance-stable-chat", AutomationPerformance.timeoutSeconds(),
                    () -> AutomationPerformance.begin("stable-chat", graphics()), AutomationPerformance::complete);
            addPresentationSteps();
            add("performance-disconnect", 30, () -> Minecraft.getInstance().disconnectWithSavingScreen(),
                    () -> Minecraft.getInstance().player == null && Minecraft.getInstance().level == null);
            return;
        }
        add("open-production-panel", 10, () -> {
            panel = new AtomChatScreen("");
            Minecraft.getInstance().setScreenAndShow(panel);
        }, AtomChatAutomation::panelFrameReady);
        add("real-world-blur-GPU-readback", 15, () -> startingCaptures = PanelBlurRenderer.capturedFrames(),
                () -> PanelBlurRenderer.isAvailable() && PanelBlurRenderer.capturedFrames() > startingCaptures);
        add("bundled-sound-resources", 5, () -> {
            for (String sound : List.of("notification", "feedback_button", "feedback_send", "feedback_panel_open")) {
                var id = net.minecraft.resources.Identifier.fromNamespaceAndPath("atomchat", sound);
                if (Minecraft.getInstance().getSoundManager().getSoundEvent(id) == null) {
                    throw new AssertionError("Sound event missing: " + id);
                }
                var ogg = net.minecraft.resources.Identifier.fromNamespaceAndPath("atomchat", "sounds/" + sound + ".ogg");
                if (Minecraft.getInstance().getResourceManager().getResource(ogg).isEmpty()) {
                    throw new AssertionError("Sound asset missing: " + ogg);
                }
            }
        }, () -> true);
        add("notification-actual-audio-channel", 8, () -> {
            AtomChatConfig.get().notificationSoundEnabled = true;
            AtomChatConfig.get().notifyVolume = 0.01F;
            soundBaseline = soundCount("notification");
            NotificationController.playTestSound();
        }, () -> soundCount("notification") > soundBaseline);
        add("notification-audio-disable-gate", 5, () -> {
            AtomChatConfig.get().notificationSoundEnabled = false;
            soundBaseline = soundCount("notification");
            NotificationController.playTestSound();
        }, () -> {
            if (soundCount("notification") != soundBaseline) throw new AssertionError("Disabled notification opened an audio channel");
            return ageMs() > 300;
        });
        for (FeedbackCue cue : FeedbackCue.values()) {
            String sound = "feedback_" + cue.name().toLowerCase(java.util.Locale.ROOT);
            add("feedback-actual-audio-channel-" + cue.name().toLowerCase(java.util.Locale.ROOT), 8, () -> {
                var config = AtomChatConfig.get();
                config.feedbackSoundEnabled = true;
                config.feedbackButtonEnabled = true;
                config.feedbackSendEnabled = true;
                config.feedbackPanelEnabled = true;
                config.feedbackVolume = 0.01F;
                soundBaseline = soundCount(sound);
                FeedbackSoundController.play(cue);
            }, () -> soundCount(sound) > soundBaseline);
        }
        add("feedback-audio-disable-gate", 5, () -> {
            AtomChatConfig.get().feedbackSoundEnabled = false;
            soundBaseline = soundCount("feedback_button");
            FeedbackSoundController.play(FeedbackCue.BUTTON);
        }, () -> {
            if (soundCount("feedback_button") != soundBaseline) throw new AssertionError("Disabled feedback opened an audio channel");
            return ageMs() > 300;
        });
        for (AppPage page : List.of(AppPage.CHAT_LIST, AppPage.PROFILE, AppPage.SETTINGS)) {
            add("panel-root-" + page.id(), 5, () -> panel.switchRoot(page),
                    () -> panel.topPage() == page && panelFrameReady());
        }
        for (SettingsSection section : SettingsSection.values()) {
            add("settings-" + section.id(), 5, () -> panel.openSettingsSection(section),
                    () -> panel.topPage() == AppPage.SETTINGS_SECTION && panelFrameReady());
            add("settings-back-" + section.id(), 5, () -> panel.popPage(), AtomChatAutomation::panelFrameReady);
        }
        add("catalog-rendered-switch-inventory", 5, () -> {
            var page = settingsPage();
            for (SettingsSection section : SettingsSection.values()) {
                int previous = page.activeChipIndex(section);
                var visible = new java.util.HashSet<String>();
                int count = Math.max(1, page.chips(section).size());
                for (int chip = 0; chip < count; chip++) {
                    if (!page.chips(section).isEmpty()) page.selectChip(section, chip);
                    page.rows(section).stream().filter(row -> row.kind() == SettingsSectionPage.RowKind.SWITCH)
                            .forEach(row -> visible.add(row.item().id()));
                }
                if (previous >= 0) page.selectChip(section, previous);
                for (SettingsItem item : SettingsCatalog.items(section)) {
                    if (!visible.contains(item.id())) UNRENDERED_SWITCHES.add(section.id() + "-" + item.id());
                }
            }
            System.out.println("[AtomChat automation] Catalog entries without UI rows: " + UNRENDERED_SWITCHES);
        }, () -> true);
        for (SettingsSection section : SettingsSection.values()) {
            for (SettingsItem item : SettingsCatalog.items(section)) {
                String key = section.id() + "-" + item.id();
                add("setting-switch-prepare-" + key, 8, () -> {
                    if (UNRENDERED_SWITCHES.contains(key)) throw new SkipCheck("Catalog setting has no rendered switch row; pointer not applicable: " + key);
                    panel.openSettingsSection(section);
                    selectSwitchChip(section, item);
                    SWITCH_ORIGINALS.put(key, item.value());
                }, () -> {
                    scrollSwitchIntoView(section, item);
                    return panelFrameReady();
                });
                add("setting-switch-pointer-and-persist-" + key, 6, () -> {
                    if (UNRENDERED_SWITCHES.contains(key)) throw new SkipCheck("Catalog setting has no rendered switch row: " + key);
                    boolean original = SWITCH_ORIGINALS.get(key);
                    boolean expected = item.available() ? !original : original;
                    clickSettingsSwitch(section, item);
                    if (item.value() != expected) throw new AssertionError("Pointer did not toggle/guard switch: " + key);
                    AtomChatConfig.reload();
                    if (item.value() != expected) throw new AssertionError("Switch did not persist: " + key);
                }, () -> ageMs() > 150 && SkiaGraphics.renderedFrames() > startingFrames);
                add("setting-switch-pointer-restore-" + key, 6, () -> {
                    if (UNRENDERED_SWITCHES.contains(key)) throw new SkipCheck("Catalog setting has no rendered switch row: " + key);
                    if (item.value() != SWITCH_ORIGINALS.get(key)) clickSettingsSwitch(section, item);
                    AtomChatConfig.reload();
                    if (item.value() != SWITCH_ORIGINALS.get(key)) throw new AssertionError("Switch restore failed: " + key);
                    SWITCH_VERIFIED.add(key);
                }, () -> ageMs() > 150 && SkiaGraphics.renderedFrames() > startingFrames);
            }
        }
        add("configuration-only-server-packs-opt-out", 8, () -> {
            boolean original = AtomChatConfig.get().serverPacksEnabled;
            AtomChatConfig.get().serverPacksEnabled = false;
            if (!AtomChatConfig.saveChecked(AtomChatConfig.get())) throw new AssertionError("Pack opt-out save failed");
            AtomChatConfig.reload();
            if (AtomChatConfig.get().serverPacksEnabled) throw new AssertionError("Pack opt-out did not persist");
            PackSyncClient.resync();
            if (!PackSyncClient.detail().equals("disabled") || PackSyncClient.currentKey() != null || PackSyncClient.installed() != null) {
                throw new AssertionError("Production pack opt-out did not clear/disable synchronization");
            }
            AtomChatConfig.get().serverPacksEnabled = original;
            if (!AtomChatConfig.saveChecked(AtomChatConfig.get())) throw new AssertionError("Pack preference restore failed");
            AtomChatConfig.reload();
            if (AtomChatConfig.get().serverPacksEnabled != original) throw new AssertionError("Restored pack preference did not persist");
            PackSyncClient.resync();
        }, () -> true);
        for (var theme : ThemeService.presets()) {
            add("theme-render-and-persist-" + theme.id(), 8, () -> {
                var config = AtomChatConfig.get();
                ThemeService.apply(config, theme.id());
                if (!AtomChatConfig.saveChecked(config)) throw new AssertionError("Theme config save failed");
                AtomChatConfig.reload();
                if (AtomChatConfig.get().accentColor != theme.accent()) throw new AssertionError("Theme palette did not survive reload");
                panel.openSettingsSection(SettingsSection.APPEARANCE);
            }, AtomChatAutomation::panelFrameReady);
        }
        add("theme-restore-defaults", 8, () -> {
            ThemeService.applyFactoryDefaults(AtomChatConfig.get());
            if (!AtomChatConfig.saveChecked(AtomChatConfig.get())) throw new AssertionError("Default theme save failed");
            panel.openWorldChat();
        }, AtomChatAutomation::panelFrameReady);
        add("open-public-chat", 5, () -> panel.openWorldChat(), AtomChatAutomation::panelFrameReady);
        addPresentationSteps();
        add("pointer-open-emoji-panel", 8, () -> clickRect(layout().emojiBtn),
                () -> ((com.atom.chat.ui.EmojiPanel) panelField("emojiPanel")).isOpen() && panelFrameReady());
        add("pointer-close-emoji-panel", 8, () -> clickRect(layout().emojiBtn),
                () -> !((com.atom.chat.ui.EmojiPanel) panelField("emojiPanel")).isOpen() && panelFrameReady());
        add("pointer-open-quick-phrase-panel", 8, () -> clickRect(layout().phraseBtn),
                () -> ((com.atom.chat.ui.QuickPhrasePanel) panelField("quickPhrasePanel")).isOpen() && panelFrameReady());
        add("pointer-close-quick-phrase-panel", 8, () -> clickRect(layout().phraseBtn),
                () -> !((com.atom.chat.ui.QuickPhrasePanel) panelField("quickPhrasePanel")).isOpen() && panelFrameReady());
        add("pointer-send-button-production-chain", 15, () -> {
            composer().setText(TOKEN + "-pointer");
            clickRect(layout().sendBtn);
        }, () -> ChatStore.get().snapshot().stream().anyMatch(m -> m.getRawText().contains(TOKEN + "-pointer"))
                && composer().text().isEmpty());
        add("production-send-and-capture", 15, () -> {
            // This is the screen's production send pipeline, not direct ChatStore insertion.
            Method send = AtomChatScreen.class.getDeclaredMethod("sendMessage", String.class);
            send.setAccessible(true);
            send.invoke(panel, TOKEN);
        }, () -> ChatStore.get().snapshot().stream().anyMatch(m -> TOKEN.equals(m.getContentText()) && m.isOwn()));
        add("server-command-roundtrip", 15,
                () -> Minecraft.getInstance().getConnection().sendCommand("say " + TOKEN + "-command"),
                () -> ChatStore.get().snapshot().stream().anyMatch(m -> m.getRawText().contains(TOKEN + "-command")));
        add("reaction-self-network-add", 10, () -> {
            if (!ReactionClient.enabled()) throw new AssertionError("Reaction channel unavailable");
            ReactionClient.toggle(sentMessage(), "👍");
        }, () -> ReactionClient.entries(sentMessage()).stream()
                .anyMatch(e -> e.emoji().equals("👍") && ReactionClient.own(e)));
        add("reaction-self-network-remove", 10,
                () -> ReactionClient.toggle(sentMessage(), "👍"),
                () -> ReactionClient.entries(sentMessage()).stream().noneMatch(e -> e.emoji().equals("👍") && ReactionClient.own(e)));
        add("profile-signature-network-update", 15,
                () -> ProfileSync.signatureEdited(TOKEN + "-signature"),
                () -> ProfileSync.record(Minecraft.getInstance().player.getUUID()).signature().equals(TOKEN + "-signature"));
        add("media-server-state", 10, () -> { }, () -> MediaCompanionClient.hostingStateKnown());
        add("media-upload-real-payloads", 15, () -> {
            if (!MediaCompanionClient.hostingEnabled()) throw new AssertionError("Integrated server hosting disabled");
            mediaBytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==");
            Path image = Minecraft.getInstance().gameDirectory.toPath().resolve("automation-fixture.png");
            Files.write(image, mediaBytes);
            MediaCompanionClient.uploadFile(image, url -> mediaUrl = url, error -> mediaError = error);
        }, () -> {
            if (mediaError != null) throw new AssertionError("Media upload failed: " + mediaError);
            return mediaUrl != null && mediaUrl.startsWith("atomchat-media:");
        });
        add("media-fetch-real-payloads", 15, () -> {
            downloaded = CompletableFuture.supplyAsync(() -> {
                try {
                    Method fetch = MediaCompanionClient.class.getDeclaredMethod("fetchBlocking", String.class);
                    fetch.setAccessible(true);
                    return (byte[]) fetch.invoke(null, mediaUrl);
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
        }, () -> {
            if (!downloaded.isDone()) return false;
            if (!Arrays.equals(mediaBytes, downloaded.join())) throw new AssertionError("Downloaded media bytes differ");
            return true;
        });
        add("hosted-PNG-production-decode", 12, () -> { }, () -> {
            var image = ImageLoader.get().get(mediaUrl, true);
            return image != null && image.getWidth() == 1 && image.getHeight() == 1 && firstPixel(image) == 0xFFFF0000;
        });
        for (ProfileField field : List.of(ProfileField.AVATAR, ProfileField.BANNER)) {
            add("profile-image-upload-" + field.name().toLowerCase(java.util.Locale.ROOT), 20, () -> {
                Path image = Minecraft.getInstance().gameDirectory.toPath().resolve("automation-fixture.png");
                ProfileSync.imageEdited(field, image);
            }, () -> {
                if (ProfileSync.unsynced()) throw new AssertionError("Profile image publication failed");
                var record = ProfileSync.record(Minecraft.getInstance().player.getUUID());
                String image = field == ProfileField.AVATAR ? record.avatar() : record.banner();
                return image != null && image.startsWith("atomchat-media:") && !ProfileSync.syncing();
            });
            add("profile-image-clear-" + field.name().toLowerCase(java.util.Locale.ROOT), 15,
                    () -> ProfileSync.imageCleared(field), () -> {
                        if (ProfileSync.unsynced()) throw new AssertionError("Profile image clear failed");
                        var record = ProfileSync.record(Minecraft.getInstance().player.getUUID());
                        String image = field == ProfileField.AVATAR ? record.avatar() : record.banner();
                        return image != null && image.isEmpty() && !ProfileSync.syncing();
                    });
        }
        add("emote-file-add-reopen-remove", 5, () -> {
            Path game = Minecraft.getInstance().gameDirectory.toPath();
            Path directory = game.resolve("automation-emotes");
            Path fixture = game.resolve("automation-fixture.png");
            EmoteStore store = new EmoteStore(directory);
            if (!store.add(fixture.toFile())) throw new AssertionError("Emote add failed");
            EmoteStore reopened = new EmoteStore(directory);
            if (reopened.count() != 1 || !Arrays.equals(mediaBytes, Files.readAllBytes(reopened.list().getFirst().toPath()))) {
                throw new AssertionError("Emote content did not survive reopening");
            }
            if (!reopened.remove(reopened.list().getFirst()) || reopened.count() != 0) {
                throw new AssertionError("Emote remove failed");
            }
        }, () -> true);
        add("hosted-GIF-upload", 15, () -> {
            Path gif = Minecraft.getInstance().gameDirectory.toPath().resolve("automation-animated.gif");
            try (var input = AtomChatAutomation.class.getResourceAsStream("/atomchat-automation/animated_3frames.gif")) {
                if (input == null) throw new AssertionError("Development GIF fixture missing");
                Files.write(gif, input.readAllBytes());
            }
            MediaCompanionClient.uploadFile(gif, url -> gifUrl = url, error -> gifError = error);
        }, () -> {
            if (gifError != null) throw new AssertionError("GIF upload failed: " + gifError);
            return gifUrl != null;
        });
        add("hosted-GIF-real-download-and-animation", 15, () -> { }, () -> {
            var frame = ImageLoader.get().get(gifUrl, true);
            if (frame == null) return false;
            GIF_COLORS.add(firstPixel(frame));
            return GIF_COLORS.size() >= 2;
        });
        for (String target : List.of("avatar", "wallpaper", "banner")) {
            add("crop-open-and-render-" + target, 10, () -> {
                panel.openSettingsSection(SettingsSection.APPEARANCE);
                cropper().open(Minecraft.getInstance().gameDirectory.toPath().resolve("automation-fixture.png"),
                        target.equals("avatar"), 256, 256, target);
            }, () -> cropper().isActive() && cropperDecoded() && panelFrameReady());
            add("crop-confirm-through-pointer-" + target, 15, () -> {
                var rect = layout().rect();
                Method x = com.atom.chat.avatar.ImageCropper.class.getDeclaredMethod("confirmCx", UiLayout.Rect.class);
                Method y = com.atom.chat.avatar.ImageCropper.class.getDeclaredMethod("btnCy", UiLayout.Rect.class);
                x.setAccessible(true);
                y.setAccessible(true);
                clickVirtual(((Number) x.invoke(cropper(), rect)).doubleValue(),
                        ((Number) y.invoke(cropper(), rect)).doubleValue());
            }, () -> {
                if (cropper().isActive()) return false;
                Path file = croppedFile(target);
                if (file == null || !Files.isRegularFile(file)) return false;
                try (var image = ImageLoader.decodeStatic(Files.readAllBytes(file), 1024)) {
                    if (image == null || firstPixel(image) != 0xFFFF0000) throw new AssertionError("Cropped pixels invalid: " + target);
                    if (target.equals("avatar") && (image.getWidth() != 256 || image.getHeight() != 256)) {
                        throw new AssertionError("Avatar crop dimensions invalid");
                    }
                }
                if (target.equals("wallpaper")) return true;
                var record = ProfileSync.record(Minecraft.getInstance().player.getUUID());
                return !ProfileSync.syncing() && !(target.equals("avatar") ? record.avatar() : record.banner()).isEmpty();
            });
        }
        add("crop-return-to-public-chat", 8, () -> panel.openWorldChat(), AtomChatAutomation::panelFrameReady);
        add("draft-save-on-screen-close", 5, () -> {
            composer().setText(TOKEN + "-draft");
            Minecraft.getInstance().setScreenAndShow(null);
        }, () -> ageMs() > 250);
        add("draft-restore-on-screen-open", 5, () -> {
            panel = new AtomChatScreen("");
            Minecraft.getInstance().setScreenAndShow(panel);
        }, () -> composer().text().equals(TOKEN + "-draft") && panelFrameReady());
        for (String kind : List.of("mention", "quote", "whisper")) {
            add("notification-empty-before-" + kind, 8, () -> { },
                    () -> !NotificationBanner.INSTANCE.hasActive());
            add("notification-command-receive-" + kind, 6,
                    () -> Minecraft.getInstance().getConnection().sendCommand("atomchat test banner " + kind),
                    () -> NotificationBanner.INSTANCE.hasActive() && panelFrameReady());
        }
        add("notification-final-expiry", 8, () -> { }, () -> !NotificationBanner.INSTANCE.hasActive());
        add("notification-disable-gate", 6, () -> {
            AtomChatConfig.get().mentionBannerEnabled = false;
            Minecraft.getInstance().getConnection().sendCommand("atomchat test banner mention " + TOKEN + "-disabled");
        }, () -> {
            if (NotificationBanner.INSTANCE.hasActive()) throw new AssertionError("Disabled mention banner appeared");
            return ageMs() > 1500;
        });
        add("config-disk-roundtrip", 5, () -> {
            AtomChatConfig config = AtomChatConfig.get();
            config.closeAfterSend = true;
            config.retainDraftOnClose = false;
            if (!AtomChatConfig.saveChecked(config)) throw new AssertionError("Config save failed");
            AtomChatConfig.reload();
            if (!AtomChatConfig.get().closeAfterSend || AtomChatConfig.get().retainDraftOnClose) {
                throw new AssertionError("Config did not survive reload");
            }
        }, () -> true);
        add("close-after-send-open-settled-panel", 20, () -> {
            panel = new AtomChatScreen("");
            Minecraft.getInstance().setScreenAndShow(panel);
        }, AtomChatAutomation::panelFrameReady);
        add("close-after-send-production-chain", 10, () -> {
            Method send = AtomChatScreen.class.getDeclaredMethod("sendMessage", String.class);
            send.setAccessible(true);
            send.invoke(panel, TOKEN + "-closing");
        }, () -> Minecraft.getInstance().gui.screen() == null
                && ChatStore.get().snapshot().stream().anyMatch(m -> m.getRawText().contains(TOKEN + "-closing")));
        add("appearance-preset-disk-roundtrip", 5, () -> {
            AtomChatConfig config = AtomChatConfig.get();
            AppearancePresetStore store = AppearancePresetStore.get();
            int accent = config.accentColor;
            var saved = store.saveCurrent(config, null);
            if (!saved.ok()) throw new AssertionError(saved.error());
            config.accentColor = accent ^ 0x00010101;
            var applied = store.apply(saved.preset().id(), config);
            if (!applied.ok() || config.accentColor != accent) throw new AssertionError("Preset apply failed");
            if (!store.delete(saved.preset().id()).ok()) throw new AssertionError("Preset delete failed");
        }, () -> true);
        add("disconnect-save-and-reset", 20, () -> {
                    if (!HistoryStore.isWorldSpecific(ChatHistory.currentKey())) throw new AssertionError("No history world key");
                    history = HistoryStore.file(ChatHistory.currentKey());
                    draftGeneration = DraftSessionStore.generation();
                    Minecraft.getInstance().disconnectWithSavingScreen();
                },
                () -> Minecraft.getInstance().player == null && Minecraft.getInstance().level == null);
        add("disconnect-clears-session-stores", 5, () -> {
            if (!ChatStore.get().isEmpty()) throw new AssertionError("ChatStore survived disconnect");
            if (DraftSessionStore.generation() <= draftGeneration) throw new AssertionError("Draft session not reset");
        }, () -> true);
        add("history-written-after-disconnect", 10, () -> { },
                () -> Files.exists(history) && Files.readAllLines(history).stream().map(HistoryStore::fromLine)
                        .anyMatch(entry -> entry != null && entry.own && TOKEN.equals(entry.contentText)));
        add("rejoin-singleplayer-world", 90,
                () -> {
                    integratedPlayerReady = null;
                    Minecraft.getInstance().createWorldOpenFlows().openWorld(WORLD, () -> { });
                },
                AtomChatAutomation::worldReadyWithFixturePrompts);
        add("history-reloaded-through-join", 10, () -> { },
                () -> ChatStore.get().snapshot().stream().anyMatch(m -> TOKEN.equals(m.getContentText()) && m.isOwn()));
        add("final-world-disconnect", 20, () -> Minecraft.getInstance().disconnectWithSavingScreen(),
                () -> Minecraft.getInstance().player == null && Minecraft.getInstance().level == null);
    }

    private static ComposerInput composer() throws Exception {
        var field = AtomChatScreen.class.getDeclaredField("composer");
        field.setAccessible(true);
        return (ComposerInput) field.get(panel);
    }

    private static Object panelField(String name) throws Exception {
        var field = AtomChatScreen.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(panel);
    }

    private static UiLayout layout() throws Exception {
        var method = AtomChatScreen.class.getDeclaredMethod("layout");
        method.setAccessible(true);
        return (UiLayout) method.invoke(panel);
    }

    private static SettingsSectionPage settingsPage() throws Exception {
        return (SettingsSectionPage) panelField("settingsSectionPage");
    }

    private static void selectSwitchChip(SettingsSection section, SettingsItem item) throws Exception {
        var page = settingsPage();
        int count = Math.max(1, page.chips(section).size());
        for (int chip = 0; chip < count; chip++) {
            if (!page.chips(section).isEmpty()) page.selectChip(section, chip);
            if (switchIndex(page.rows(section), item) >= 0) return;
        }
        String key = section.id() + "-" + item.id();
        UNRENDERED_SWITCHES.add(key);
        throw new SkipCheck("Catalog setting has no rendered switch row: " + key);
    }

    private static int switchIndex(List<SettingsSectionPage.Row> rows, SettingsItem item) {
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            if (row.kind() == SettingsSectionPage.RowKind.SWITCH && row.item().id().equals(item.id())) return i;
        }
        return -1;
    }

    private static UiLayout.Rect switchRectangle(SettingsSection section, SettingsItem item) throws Exception {
        var page = settingsPage();
        var rows = page.rows(section);
        int row = switchIndex(rows, item);
        if (row < 0) throw new AssertionError("Current chip lost switch: " + item.id());
        var method = SettingsSectionPage.class.getDeclaredMethod("rowRect", SettingsSection.class,
                List.class, int.class, float.class, UiLayout.class);
        method.setAccessible(true);
        var scroll = ((com.atom.chat.ui.ScrollSyncService) panelField("scrollSync")).detailScroll();
        return (UiLayout.Rect) method.invoke(page, section, rows, row, scroll.getScrollY(),
                ScreenMetrics.detailLayout(Minecraft.getInstance().getWindow()));
    }

    private static void scrollSwitchIntoView(SettingsSection section, SettingsItem item) throws Exception {
        var page = settingsPage();
        var detail = ScreenMetrics.detailLayout(Minecraft.getInstance().getWindow());
        var scroll = ((com.atom.chat.ui.ScrollSyncService) panelField("scrollSync")).detailScroll();
        scroll.setContent(page.measureContent(detail, section), detail.list.h());
        var rectangle = switchRectangle(section, item);
        if (rectangle.h() <= 1) throw new AssertionError("Switch is folded away: " + item.id());
        float top = detail.list.y() + page.chipBarHeight(section);
        if (rectangle.y() < top || rectangle.bottom() > detail.list.bottom()) {
            scroll.scrollTo(scroll.getScrollY() + rectangle.y() - top, false);
        }
    }

    private static void clickSettingsSwitch(SettingsSection section, SettingsItem item) throws Exception {
        var rectangle = switchRectangle(section, item);
        var detail = ScreenMetrics.detailLayout(Minecraft.getInstance().getWindow());
        float top = Math.max(rectangle.y(), detail.list.y() + settingsPage().chipBarHeight(section));
        float bottom = Math.min(rectangle.bottom(), detail.list.bottom());
        if (bottom <= top) throw new AssertionError("Switch is outside viewport: " + item.id());
        clickVirtual(rectangle.x() + rectangle.w() / 2, (top + bottom) / 2);
    }

    private static void clickRect(UiLayout.Rect rectangle) {
        clickVirtual(rectangle.x() + rectangle.w() / 2, rectangle.y() + rectangle.h() / 2);
    }

    private static void clickVirtual(double vx, double vy) {
        try {
            if (!navigationSettled()) throw new AssertionError("Refusing pointer action during panel/page navigation");
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        var window = Minecraft.getInstance().getWindow();
        double x = (vx - ScreenMetrics.toVirtualX(window, 0))
                / (ScreenMetrics.toVirtualX(window, 1) - ScreenMetrics.toVirtualX(window, 0));
        double y = (vy - ScreenMetrics.toVirtualY(window, 0))
                / (ScreenMetrics.toVirtualY(window, 1) - ScreenMetrics.toVirtualY(window, 0));
        var event = new net.minecraft.client.input.MouseButtonEvent(x, y,
                new net.minecraft.client.input.MouseButtonInfo(1, 0)); // SDL_BUTTON_LEFT
        panel.mouseClicked(event, false);
        panel.mouseReleased(event);
    }

    private static com.atom.chat.avatar.ImageCropper cropper() throws Exception {
        return (com.atom.chat.avatar.ImageCropper) panelField("imageCropper");
    }

    private static boolean cropperDecoded() throws Exception {
        var type = com.atom.chat.avatar.ImageCropper.class;
        var decoded = type.getDeclaredField("decoded");
        var transform = type.getDeclaredField("transformReady");
        decoded.setAccessible(true);
        transform.setAccessible(true);
        return decoded.get(cropper()) != null && transform.getBoolean(cropper());
    }

    private static Path croppedFile(String target) throws Exception {
        return switch (target) {
            case "avatar" -> ((com.atom.chat.avatar.AvatarStore) panelField("avatarStore")).current();
            case "banner" -> com.atom.chat.banner.BannerStore.current();
            case "wallpaper" -> com.atom.chat.wallpaper.WallpaperStore.current();
            default -> throw new AssertionError("Unknown crop target: " + target);
        };
    }

    private static int firstPixel(io.github.humbleui.skija.Image image) {
        try (var bitmap = new io.github.humbleui.skija.Bitmap()) {
            bitmap.allocN32Pixels(image.getWidth(), image.getHeight());
            if (!image.readPixels(bitmap)) throw new AssertionError("Image pixel read failed");
            return bitmap.getColor(0, 0);
        }
    }

    private static void soundStarted(PlaySoundSourceEvent event) {
        var id = event.getSound().getIdentifier();
        if (id.getNamespace().equals("atomchat")) SOUND_PLAYS.computeIfAbsent(id.getPath(), key -> new AtomicInteger()).incrementAndGet();
    }

    private static int soundCount(String sound) {
        var count = SOUND_PLAYS.get(sound);
        return count == null ? 0 : count.get();
    }

    private static SkiaGraphics graphics() throws Exception {
        var field = AtomChatScreen.class.getDeclaredField("graphics");
        field.setAccessible(true);
        return (SkiaGraphics) field.get(panel);
    }

    private static boolean navigationSettled() throws ReflectiveOperationException {
        var directorField = AtomChatScreen.class.getDeclaredField("director");
        var progressField = AtomChatScreen.class.getDeclaredField("panelProgress");
        var closingField = AtomChatScreen.class.getDeclaredField("closing");
        directorField.setAccessible(true);
        progressField.setAccessible(true);
        closingField.setAccessible(true);
        var director = (PageTransitionDirector) directorField.get(panel);
        return !director.pageNavActive() && !director.rootTransitionActive()
                && progressField.getFloat(panel) == 1.0F && !closingField.getBoolean(panel);
    }

    private static boolean panelFrameReady() throws Exception {
        if (!navigationSettled()) {
            settledAtFrame = -1;
            return false;
        }
        if (settledAtFrame < 0) settledAtFrame = SkiaGraphics.renderedFrames();
        return ageMs() > 500 && SkiaGraphics.renderedFrames() >= settledAtFrame + 2
                && graphics().isReady() && graphics().visiblePixelSamples() > 0;
    }

    private static void addPresentationSteps() {
        add("presentation-stable-chat", 30, () -> {
            originalCaptureScale = AtomChatConfig.get().uiScale;
            originalCaptureHeight = AtomChatConfig.get().panelHeight;
            requestPresentation("stable-chat");
        }, AtomChatAutomation::presentationReady);
        add("presentation-half-scale-ready", 30, () -> {
            panel = new AtomChatScreen("");
            AtomChatConfig.get().uiScale = 0.5F;
            AtomChatConfig.get().panelHeight = Math.min(originalCaptureHeight, 400.0F);
            Minecraft.getInstance().setScreenAndShow(panel);
            panel.openWorldChat();
        }, () -> halfScaleReady() && panelFrameReady());
        add("presentation-half-scale-chat", 30, () -> {
            if (!halfScaleReady()) throw new AssertionError("Half-scale scene lost its scale or nonzero raster origin");
            requestPresentation("half-scale-chat");
        },
                AtomChatAutomation::presentationReady);
        add("presentation-restore-scale", 30, () -> {
            panel = new AtomChatScreen("");
            AtomChatConfig.get().uiScale = originalCaptureScale;
            AtomChatConfig.get().panelHeight = originalCaptureHeight;
            Minecraft.getInstance().setScreenAndShow(panel);
            panel.openWorldChat();
        }, AtomChatAutomation::panelFrameReady);
    }

    private static boolean halfScaleReady() throws Exception {
        if (AtomChatConfig.get().uiScale != 0.5F) {
            throw new AssertionError("Expected uiScale=0.5, got " + AtomChatConfig.get().uiScale);
        }
        var bounds = presentationBounds();
        return bounds != null && bounds.y() > 0;
    }

    private static com.atom.chat.render.PanelRasterBounds presentationBounds() throws Exception {
        var boundsField = SkiaGraphics.class.getDeclaredField("bounds");
        boundsField.setAccessible(true);
        return (com.atom.chat.render.PanelRasterBounds) boundsField.get(graphics());
    }

    private static void requestPresentation(String name) throws Exception {
        capturePanel("presentation-" + name + "-surface");
        var path = report.getParent().resolve("frames").resolve("presentation-" + name + "-composited.png");
        presentationCapture = AutomationPresentationCapture.request(path);
        result("presentation-artifacts-" + name, "PASS", "Surface: "
                + path.resolveSibling("presentation-" + name + "-surface.png")
                + "; real composited render target: " + path
                + "; uiScale=" + AtomChatConfig.get().uiScale
                + "; panelHeight=" + AtomChatConfig.get().panelHeight
                + "; bounds=" + presentationBounds()
                + "; activeGraphicsBackend=" + com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().backendName()
                + "; renderBackend=" + (graphics().isGpuAccelerated() ? "skia-opengl" : "skia-raster")
                + "; visual inspection required for alignment, orientation and alpha blending");
    }

    private static boolean presentationReady() {
        if (!presentationCapture.isDone()) return false;
        presentationCapture.join();
        return true;
    }

    private static void capturePanel(String name) throws Exception {
        Path image = report.getParent().resolve("frames").resolve(name + ".png");
        Files.createDirectories(image.getParent());
        Files.write(image, graphics().capturePng());
    }

    private static com.atom.chat.chat.ChatMessage sentMessage() {
        return ChatStore.get().snapshot().stream()
                .filter(m -> TOKEN.equals(m.getContentText()) && !m.isSystem())
                .findFirst().orElseThrow(() -> new AssertionError("Production public message absent"));
    }

    private static boolean worldReady() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || client.getConnection() == null
                || client.getSingleplayerServer() == null) return false;
        if (integratedPlayerReady == null || (integratedPlayerReady.isDone() && !integratedPlayerReady.join())) {
            var uuid = client.player.getUUID();
            var server = client.getSingleplayerServer();
            integratedPlayerReady = server.submit(() -> server.getPlayerList().getPlayer(uuid) != null);
        }
        return integratedPlayerReady.getNow(false);
    }

    private static void requireFixtureWorld() {
        var game = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
        if (!game.getFileName().toString().startsWith("automation")
                || !WORLD.matches("atomchat-automation-[0-9]+")
                || !Files.isDirectory(game.resolve("saves").resolve(WORLD))) {
            throw new AssertionError("World-load confirmation refused outside the existing isolated fixture");
        }
    }

    private static String translationKey(net.minecraft.network.chat.Component component) {
        return component.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents contents
                ? contents.getKey() : "";
    }

    private static boolean worldReadyWithFixturePrompts() {
        if (worldReady()) return true;
        var screen = Minecraft.getInstance().gui.screen();
        if (screen == null) return false;
        String title = translationKey(screen.getTitle());
        String description = screen.getClass().getName() + ":" + title;
        if (!description.equals(lastLoadScreen)) {
            System.out.println("[AtomChat automation] World load screen: " + description);
            lastLoadScreen = description;
        }
        String buttonKey;
        if (screen.getClass() == net.minecraft.client.gui.screens.BackupConfirmScreen.class
                && title.equals("selectWorld.backupQuestion.experimental")) {
            buttonKey = "selectWorld.backupJoinConfirmButton";
        } else if (screen.getClass() == net.minecraft.client.gui.screens.ConfirmScreen.class
                && title.equals("selectWorld.warning.experimental.title")) {
            buttonKey = "gui.yes";
        } else return false; // Unknown dialogs are diagnosed, never blindly accepted.
        requireFixtureWorld();
        if (screen == lastConfirmedPrompt) return false;
        for (var child : screen.children()) {
            if (child instanceof net.minecraft.client.gui.components.Button button && button.active
                    && button.visible && translationKey(button.getMessage()).equals(buttonKey)) {
                lastConfirmedPrompt = screen;
                result("fixture-experimental-world-confirmation", "PASS", description + "; actual button=" + buttonKey);
                button.onPress(new net.minecraft.client.input.MouseButtonInfo(1, 0));
                return false;
            }
        }
        return false;
    }

    private static String runtimeState() {
        var client = Minecraft.getInstance();
        if (client == null) return "Minecraft instance not constructed";
        var screen = client.gui == null ? null : client.gui.screen();
        return "screen=" + (screen == null ? "null" : screen.getClass().getName() + ":" + translationKey(screen.getTitle()))
                + ", player=" + (client.player != null) + ", level=" + (client.level != null)
                + ", connection=" + (client.getConnection() != null)
                + ", integratedServer=" + (client.getSingleplayerServer() != null)
                + ", framebuffer=" + client.getWindow().getWidth() + "x" + client.getWindow().getHeight()
                + ", window=" + client.getWindow().getScreenWidth() + "x" + client.getWindow().getScreenHeight();
    }

    private static void tick(ClientTickEvent.Post event) {
        if (finished) return;
        if (index >= STEPS.size()) {
            if (HISTORY_ONLY || PERFORMANCE) {
                finishReport("PASS");
                Minecraft.getInstance().stop();
                return;
            }
            result("multiplayer-broadcast-and-real-private-chat", "SKIP", "Requires a second real client");
            result("OS-file-picker-clipboard-IME-and-audible-sound", "SKIP", "Sound resources and actual playback channel events checked; native interaction and perceived sound need separate evidence");
            result("all-page-visual-oracles", "SKIP", "Nonempty raster, real GPU frames and selected pointer zones checked; complete expected-image comparisons not defined");
            result("real-poke-sound-and-animation", "SKIP", "Requires a second player and sound/frame observations");
            result("remote-avatar-banner-and-resource-pack-sync", "SKIP", "Own profile image upload/clear checked; remote-client and resource-pack paths not covered");
            result("settings-sliders-colour-dialogs-and-destructive-actions", "SKIP", "Every catalog switch clicked, persisted and restored; slider/color/dialog/destructive action gestures not fully covered");
            result("external-host-and-vanilla-client-degradation", "SKIP", "Requires controlled alternate host/server/client fixtures");
            result("emote-panel-item-editing", "SKIP", "PNG/GIF decode, all three crop targets and emote disk add/reopen/remove checked; panel-item edit gestures not covered");
            finishReport(RESULTS.stream().anyMatch(r -> r.get("status").equals("FAIL")) ? "FAIL"
                    : RESULTS.stream().anyMatch(r -> r.get("status").equals("SKIP")) ? "PARTIAL" : "PASS");
            Minecraft.getInstance().stop();
            return;
        }
        Step step = STEPS.get(index);
        try {
            if (!begun) {
                entered = System.nanoTime();
                startingFrames = SkiaGraphics.renderedFrames();
                settledAtFrame = -1;
                begun = true;
                System.out.println("[AtomChat automation] START " + step.name);
                synchronized (RESULTS) { writeReport("RUNNING"); }
                step.start.run();
            }
            if (step.ready.get()) {
                if (step.name.startsWith("panel-root-") || step.name.startsWith("settings-")
                        || step.name.startsWith("notification-command-receive-")
                        || step.name.startsWith("theme-render-")
                        || step.name.startsWith("pointer-open-")
                        || step.name.startsWith("crop-open-")
                        || step.name.equals("open-production-panel") || step.name.equals("open-public-chat")) {
                    capturePanel(step.name);
                }
                result(step.name, "PASS", "Condition satisfied after " + ageMs() + " ms");
                index++;
                begun = false;
            } else if (ageMs() > step.seconds * 1000L) {
                throw new AssertionError("Step timed out after " + step.seconds + " seconds; " + runtimeState());
            }
        } catch (SkipCheck missingFixture) {
            result(step.name, "SKIP", missingFixture.getMessage());
            index++;
            begun = false;
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            result(step.name, "FAIL", failure.toString());
            finishReport("FAIL");
            Minecraft.getInstance().stop();
        }
    }

    private static long ageMs() { return (System.nanoTime() - entered) / 1_000_000; }
    private static void add(String name, int seconds, Action start, Check ready) {
        STEPS.add(new Step(name, Math.max(20, seconds), start, ready));
    }
    private static void result(String name, String status, String detail) {
        synchronized (RESULTS) {
            RESULTS.add(Map.of("name", name, "status", status, "detail", detail,
                    "elapsedMs", (System.nanoTime() - START) / 1_000_000));
            System.out.println("[AtomChat automation] " + status + " " + name + ": " + detail);
            writeReport("RUNNING");
        }
    }
    private static void finishReport(String status) {
        synchronized (RESULTS) { finished = true; writeReport(status); }
    }
    private static void writeReport(String status) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("schema", 1);
            root.put("minecraft", "26.3");
            root.put("mode", PERFORMANCE ? "performance" : HISTORY_ONLY ? "history-only" : "full");
            if (PERFORMANCE) root.put("performance", AutomationPerformance.results());
            root.put("status", status);
            root.put("updatedAt", Instant.now().toString());
            root.put("world", WORLD);
            root.put("messageToken", TOKEN);
            root.put("runtimeState", runtimeState());
            root.put("activeStep", index < STEPS.size() ? STEPS.get(index).name : "complete");
            root.put("javaVersion", System.getProperty("java.version"));
            root.put("renderedFrames", SkiaGraphics.renderedFrames());
            root.put("worldBlurCaptures", PanelBlurRenderer.capturedFrames());
            root.put("framesDirectory", report.getParent().resolve("frames").toString());
            root.put("catalogSwitchesChecked", SWITCH_VERIFIED.size());
            root.put("unrenderedCatalogSwitches", UNRENDERED_SWITCHES);
            root.put("checks", RESULTS);
            Files.createDirectories(report.getParent());
            Path temporary = report.resolveSibling(report.getFileName() + ".tmp");
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(root));
            Files.move(temporary, report, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception error) { System.err.println("AtomChat automation report failed: " + error); }
    }
    private record Step(String name, int seconds, Action start, Check ready) { }
    private static final class SkipCheck extends RuntimeException {
        private SkipCheck(String message) { super(message); }
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    @FunctionalInterface private interface Check { boolean get() throws Exception; }
}
