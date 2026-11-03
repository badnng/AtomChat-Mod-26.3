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
    private static final Map<String, Object> STARTUP_GRAPHICS = new LinkedHashMap<>();
    private static final long START = System.nanoTime();
    private static final boolean HISTORY_ONLY = System.getenv("ATOMCHAT_AUTOMATION_HISTORY_WORLD") != null;
    private static final boolean PERFORMANCE = Boolean.getBoolean("atomchat.automation.performance");
    private static final boolean NATIVE_PARITY = Boolean.getBoolean("atomchat.automation.nativeParity");
    private static final boolean ABOUT_PREVIEW = Boolean.getBoolean("atomchat.automation.aboutPreview");
    private static final boolean BLUR_MOTION = Boolean.getBoolean("atomchat.automation.blurMotion");
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
    private static AtomChatConfig.UiStyle originalUiStyle;
    private static float originalStyleRadius;
    private static Map<String, Object> styleAppearance;
    private static AtomChatConfig.MiuixNavigationStyle originalNavigationStyle;
    private static boolean originalNativeBlur;
    private static long nativeScrollAt;
    private static final java.util.UUID NATIVE_FIXTURE_PLAYER = java.util.UUID.fromString("6d697569-782d-4174-8f6d-436861744669");
    private static com.atom.chat.chat.ChatMessage nativeFixtureMessage;
    private static float nativeOriginalContentScale;
    private static int nativeOriginalOtherBubble;
    private static boolean nativeOriginalAnimations;
    private static boolean nativeOriginalPoke;
    private static float nativeTextHeight;
    private static long nativePokeBefore;
    private static boolean nativeFocusDiagnostic;
    private static float nativeLifecycleScale;
    private static int nativeLifecycleWidth;
    private static int nativeLifecycleHeight;
    private static Path nativeLifecycleImage;
    private static List<com.atom.chat.chat.ChatMessage> nativeScrollOriginal;
    private static final List<com.atom.chat.chat.ChatMessage> nativeScrollFixture = new ArrayList<>();
    private static float nativeScrollOldestY;
    private static boolean nativeRegressionAnimations;
    private static AtomChatConfig.MiuixNavigationStyle nativeRegressionNavigation;

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
        add("startup-gpu-diagnostics", 10, () -> { }, AtomChatAutomation::verifyStartupGpuDiagnostics);
        add("isolated-directory", 2, () -> {
            Path game = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
            if (!game.getFileName().toString().startsWith("automation")) {
                throw new IllegalStateException("Refusing to mutate non-automation game directory: " + game);
            }
            Minecraft.getInstance().options.pauseOnLostFocus = false;
            Minecraft.getInstance().options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
            boolean visible = !Boolean.parseBoolean(System.getProperty("atomchat.automation.hideWindow", "true"));
            int fps = PERFORMANCE && !visible ? 260 : 60;
            Minecraft.getInstance().options.framerateLimit().set(fps);
            Minecraft.getInstance().getFramerateLimitTracker().setFramerateLimit(fps);
            if (visible) Minecraft.getInstance().options.enableVsync().set(true);
            else if (PERFORMANCE) Minecraft.getInstance().options.enableVsync().set(false);
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
        if (PERFORMANCE || BLUR_MOTION) {
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
        if (BLUR_MOTION) {
            add("blur-motion-world-warmup", 30, () -> Minecraft.getInstance().setScreenAndShow(null),
                    () -> ageMs() >= 2000);
            for (String cycle : List.of("first", "repeat", "half-scale", "resize")) {
                add("blur-motion-open-" + cycle, 30, () -> {
                    if (cycle.equals("first")) {
                        originalCaptureScale = AtomChatConfig.get().uiScale;
                        originalCaptureHeight = AtomChatConfig.get().panelHeight;
                    }
                    panel = new AtomChatScreen("");
                    // The constructor reloads persisted config; set fixture scale afterward.
                    if (cycle.equals("half-scale")) {
                        AtomChatConfig.get().uiScale = 0.5F;
                        AtomChatConfig.get().panelHeight = Math.min(originalCaptureHeight, 400.0F);
                    } else {
                        AtomChatConfig.get().uiScale = originalCaptureScale;
                        AtomChatConfig.get().panelHeight = originalCaptureHeight;
                    }
                    Minecraft.getInstance().setScreenAndShow(panel);
                    panel.openWorldChat();
                    AutomationBlurMotion.begin("open-" + cycle, panel, report.getParent().resolve("frames"));
                }, AutomationBlurMotion::held);
                if (cycle.equals("resize")) {
                    add("blur-motion-window-resize", 30, () -> {
                        AutomationBlurMotion.resize();
                        Minecraft.getInstance().getWindow().setWindowed(1777, 997);
                    }, AutomationBlurMotion::resizeHeld);
                }
                add("blur-motion-close-" + cycle, 30, AutomationBlurMotion::close, AutomationBlurMotion::closed);
                add("blur-motion-capture-complete-" + cycle, 30, () -> { }, AutomationBlurMotion::complete);
                add("blur-motion-closed-hold-" + cycle, 30, () -> { }, () -> ageMs() >= 600);
                if (cycle.equals("resize")) {
                    add("blur-motion-restore-window", 30, () -> Minecraft.getInstance().getWindow().setWindowed(
                            Integer.getInteger("atomchat.automation.width", 1920),
                            Integer.getInteger("atomchat.automation.height", 1080)),
                            () -> Minecraft.getInstance().getWindow().getWidth() == Integer.getInteger("atomchat.automation.width", 1920)
                                    && Minecraft.getInstance().getWindow().getHeight() == Integer.getInteger("atomchat.automation.height", 1080));
                }
            }
            add("blur-motion-animation-oracle", 30, () -> {
                AtomChatConfig.get().uiScale = originalCaptureScale;
                AtomChatConfig.get().panelHeight = originalCaptureHeight;
                AutomationBlurMotion.verify();
            }, () -> true);
            add("blur-motion-disconnect", 30, () -> Minecraft.getInstance().disconnectWithSavingScreen(),
                    () -> Minecraft.getInstance().player == null && Minecraft.getInstance().level == null);
            return;
        }
        if (PERFORMANCE) {
            add("performance-fixture-config", 5, () -> {
                var config = AtomChatConfig.get();
                config.uiStyle = AutomationPerformance.uiStyle();
                config.blurEnabled = true;
                if (!AtomChatConfig.saveChecked(config)) throw new AssertionError("Performance config save failed");
            }, () -> true);
            add("performance-world-warmup", 30, () -> Minecraft.getInstance().setScreenAndShow(null),
                    () -> ageMs() >= AutomationPerformance.warmupMs());
            add("performance-no-panel", AutomationPerformance.timeoutSeconds(),
                    () -> AutomationPerformance.begin("no-panel"), AutomationPerformance::complete);
            add("performance-open-chat", 30, () -> {
                panel = new AtomChatScreen("");
                // Constructor reloads the saved fixture; assert the measured style survived.
                if (AtomChatConfig.get().effectiveUiStyle() != AutomationPerformance.uiStyle()) {
                    throw new AssertionError("Performance panel loaded the wrong UI style");
                }
                Minecraft.getInstance().setScreenAndShow(panel);
                panel.openWorldChat();
            }, AtomChatAutomation::panelFrameReady);
            add("performance-chat-warmup", 30, () -> { },
                    () -> ageMs() >= AutomationPerformance.warmupMs());
            add("performance-stable-chat", AutomationPerformance.timeoutSeconds(),
                    () -> AutomationPerformance.begin("stable-chat", graphics(), panel), AutomationPerformance::complete);
            add("performance-chat-evidence", 20, () -> capturePanel("performance-stable-chat"), () -> true);
            if (AutomationPerformance.profilePage()) {
                add("performance-open-profile", 30, () -> panel.switchRoot(AppPage.PROFILE),
                        () -> panel.topPage() == AppPage.PROFILE && panelFrameReady());
                add("performance-profile-warmup", 30, () -> { },
                        () -> ageMs() >= AutomationPerformance.warmupMs());
                add("performance-stable-profile", AutomationPerformance.timeoutSeconds(),
                        () -> AutomationPerformance.begin("stable-profile", graphics(), panel), AutomationPerformance::complete);
                add("performance-profile-evidence", 20, () -> capturePanel("performance-stable-profile"), () -> true);
            }
            add("performance-disconnect", 30, () -> Minecraft.getInstance().disconnectWithSavingScreen(),
                    () -> Minecraft.getInstance().player == null && Minecraft.getInstance().level == null);
            return;
        }
        if (ABOUT_PREVIEW) {
            if (!Boolean.parseBoolean(System.getProperty("atomchat.automation.hideWindow", "true"))) {
                add("about-preview-foreground", 45, () -> {
                    System.out.println("[AtomChat automation] Waiting for the visible game window to gain foreground focus");
                    AutomationMiuixRegression.prepareImeWindow();
                }, () -> Minecraft.getInstance().isWindowActive());
            }
            add("about-preview-open", 30, () -> {
                var config = AtomChatConfig.get();
                config.uiStyle = AtomChatConfig.UiStyle.MIUIX;
                config.blurEnabled = true;
                config.animationEnabled = true;
                if (!AtomChatConfig.saveChecked(config)) throw new AssertionError("About preview config save failed");
                panel = new AtomChatScreen("");
                Minecraft.getInstance().setScreenAndShow(panel);
                panel.switchRoot(AppPage.SETTINGS);
                panel.openSettingsSection(SettingsSection.ABOUT);
            }, () -> nativeFrameReady("about-wordmark"));
            add("about-preview-atomchat-layout", 20,
                    () -> AutomationMiuixRegression.about(panel, report.getParent().resolve("frames")),
                    AutomationMiuixRegression::aboutReady);
            int previewSeconds = Math.max(5, Integer.getInteger("atomchat.automation.previewSeconds", 60));
            add("about-preview-visible-inspection", previewSeconds + 20,
                    () -> System.out.println("[AtomChat automation] About page ready for visible inspection for " + previewSeconds + " seconds"),
                    () -> ageMs() >= previewSeconds * 1000L);
            addAboutPreviewNavigationChecks();
            add("about-preview-disconnect", 30, () -> Minecraft.getInstance().disconnectWithSavingScreen(),
                    () -> Minecraft.getInstance().player == null && Minecraft.getInstance().level == null);
            return;
        }
        if (NATIVE_PARITY) {
            add("native-parity-open-chat", 30, () -> {
                var config = AtomChatConfig.get();
                config.uiStyle = AtomChatConfig.UiStyle.MIUIX;
                config.blurEnabled = true;
                config.closeAfterSend = false;
                if (!AtomChatConfig.saveChecked(config)) throw new AssertionError("Native parity config save failed");
                panel = new AtomChatScreen("");
                Minecraft.getInstance().setScreenAndShow(panel);
                panel.openWorldChat();
            }, () -> nativeFrameReady("chat-composer"));
            addNativeParitySteps();
            add("native-parity-disconnect", 30, () -> Minecraft.getInstance().disconnectWithSavingScreen(),
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
        addUiStyleSteps();
        addMiuixNavigationSteps();
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

    /** Uses the actual action row and mouse chain; never invokes the action handler directly. */
    private static void addUiStyleSteps() {
        add("compose-style-remember-preferences", 5, () -> {
            originalUiStyle = AtomChatConfig.get().effectiveUiStyle();
            originalStyleRadius = AtomChatConfig.get().cornerRadius;
        }, () -> true);
        for (float radius : new float[]{0.0F, 20.0F}) {
            String suffix = "-radius-" + (int) radius;
            add("compose-style-legacy-prepare" + suffix, 10, () -> {
                var config = AtomChatConfig.get();
                config.uiStyle = AtomChatConfig.UiStyle.ATOMCHAT;
                config.cornerRadius = radius;
                if (!AtomChatConfig.saveChecked(config)) throw new AssertionError("Style fixture save failed");
                AtomChatConfig.reload();
                styleAppearance = styleAppearanceSnapshot();
                panel.openSettingsSection(SettingsSection.APPEARANCE);
                settingsPage().selectChip(SettingsSection.APPEARANCE, 0);
            }, () -> {
                scrollStyleSelectorIntoView();
                return panelFrameReady();
            });
            add("compose-style-legacy-pointer-enter" + suffix, 20, () -> {
                clickRect(styleSelectorRectangle());
                verifyUiStyle(AtomChatConfig.UiStyle.MIUIX);
            }, () -> nativeFrameReady(null));
            addNativeCapture("compose-settings" + suffix, false);
            add("compose-style-selector-visible" + suffix, 30, () -> nativeScrollAt = 0,
                    () -> nativeScrollUntilVisible("setting-ui_style"));
            add("compose-style-dropdown-open" + suffix, 10, () -> clickNativeTag("setting-ui_style"),
                    () -> nativeTextReady("atomchat.settings.appearance.ui_style.atomchat"));
            add("compose-style-dropdown-choose-atomchat" + suffix, 15, () -> {
                clickNativeText("atomchat.settings.appearance.ui_style.atomchat");
                verifyUiStyle(AtomChatConfig.UiStyle.ATOMCHAT);
            }, AtomChatAutomation::panelFrameReady);
        }
        add("compose-style-restore-radius", 8, () -> {
            AtomChatConfig.get().uiStyle = AtomChatConfig.UiStyle.ATOMCHAT;
            AtomChatConfig.get().cornerRadius = originalStyleRadius;
            if (!AtomChatConfig.saveChecked(AtomChatConfig.get())) throw new AssertionError("Style restore save failed");
            AtomChatConfig.reload();
        }, AtomChatAutomation::panelFrameReady);
    }

    private static void verifyUiStyle(AtomChatConfig.UiStyle expected) throws Exception {
        for (int reload = 0; reload < 2; reload++) {
            AtomChatConfig.reload();
            if (AtomChatConfig.get().effectiveUiStyle() != expected) {
                throw new AssertionError("Real UI selection did not persist " + expected + " on reload " + reload);
            }
            if (!styleAppearance.equals(styleAppearanceSnapshot())) {
                throw new AssertionError("UI selection overwrote theme, colors or corner radius");
            }
        }
    }

    private static Map<String, Object> styleAppearanceSnapshot() throws IllegalAccessException {
        Map<String, Object> values = new LinkedHashMap<>();
        for (var field : AtomChatConfig.class.getFields()) {
            String name = field.getName();
            if (name.endsWith("Color") || name.equals("themeName") || name.equals("cornerRadius")
                    || name.equals("panelOpacity") || name.equals("blurEnabled")) {
                values.put(name, field.get(AtomChatConfig.get()));
            }
        }
        return values;
    }

    private static void addMiuixNavigationSteps() {
        add("compose-navigation-fixture", 20, () -> {
            originalNavigationStyle = AtomChatConfig.get().effectiveMiuixNavigationStyle();
            originalNativeBlur = AtomChatConfig.get().blurEnabled;
            AtomChatConfig.get().uiStyle = AtomChatConfig.UiStyle.MIUIX;
            AtomChatConfig.get().blurEnabled = true;
            if (!AtomChatConfig.saveChecked(AtomChatConfig.get())) throw new AssertionError("Compose fixture save failed");
            AtomChatConfig.reload();
            styleAppearance = styleAppearanceSnapshot();
            panel.switchRoot(AppPage.SETTINGS);
        }, () -> nativeFrameReady("settings-section-appearance"));
        for (var expected : AtomChatConfig.MiuixNavigationStyle.values()) {
            String mode = expected.name().toLowerCase(java.util.Locale.ROOT);
            add("compose-navigation-appearance-" + mode, 30, () -> {
                clickNativeTag("settings-section-appearance");
                nativeScrollAt = 0;
            }, () -> nativeScrollUntilVisible("setting-miuix_navigation_style"));
            add("compose-navigation-dropdown-" + mode, 10,
                    () -> clickNativeTag("setting-miuix_navigation_style"),
                    () -> nativeTextReady("atomchat.settings.appearance.miuix_navigation_style." + mode));
            add("compose-navigation-choice-" + mode, 12, () -> {
                clickNativeText("atomchat.settings.appearance.miuix_navigation_style." + mode);
                for (int reload = 0; reload < 2; reload++) {
                    AtomChatConfig.reload();
                    if (AtomChatConfig.get().effectiveMiuixNavigationStyle() != expected
                            || AtomChatConfig.get().effectiveUiStyle() != AtomChatConfig.UiStyle.MIUIX
                            || !styleAppearance.equals(styleAppearanceSnapshot())) {
                        throw new AssertionError("Compose dropdown did not persist independent navigation preference " + expected);
                    }
                }
            }, () -> nativeFrameReady("navigate-back"));
            add("compose-navigation-back-" + mode, 12, () -> clickNativeTag("navigate-back"),
                    () -> nativeFrameReady("nav-settings"));
            for (AppPage target : List.of(AppPage.CHAT_LIST, AppPage.PROFILE, AppPage.SETTINGS)) {
                String tag = target == AppPage.CHAT_LIST ? "nav-chats"
                        : target == AppPage.PROFILE ? "nav-profile" : "nav-settings";
                add("compose-navigation-pointer-" + mode + "-" + target.id(), 12,
                        () -> clickNativeTag(tag),
                        () -> panel.topPage() == target && nativeFrameReady(tag));
                addNativeCapture("compose-navigation-" + mode + "-" + target.id(), false);
            }
        }
        add("compose-chat-open", 20, () -> {
            composer().setText("");
            panel.openWorldChat();
        },
                () -> nativeFrameReady("chat-composer"));
        add("compose-chat-focus", 10, () -> {
            clickNativeTag("chat-composer");
            AutomationMiuixRegression.prepareImeWindow();
        }, () -> nativeFrameReady("chat-composer") && AutomationMiuixRegression.imeWindowReady());
        add("compose-chat-ime-sdl-candidate-commit", 10,
                () -> AutomationMiuixRegression.imeCommit(panel, composeCjkText()),
                () -> nativeFrameReady("chat-send") && AutomationMiuixRegression.imeCommitReady(panel));
        add("compose-chat-ime-sdl-blur", 10,
                () -> AutomationMiuixRegression.imeLeaveComposer(panel),
                () -> ageMs() > 700 && AutomationMiuixRegression.imeBlurReady(panel));
        add("compose-chat-ime-sdl-refocus", 10,
                () -> AutomationMiuixRegression.imeReturnToComposer(panel),
                () -> ageMs() > 700 && AutomationMiuixRegression.imeRefocusReady(panel));
        addNativeCapture("compose-cjk-composer", true);
        add("compose-chat-enter-production-send", 20, () -> {
            var key = new net.minecraft.client.input.KeyEvent(
                    com.mojang.blaze3d.platform.InputConstants.KEY_RETURN,
                    com.mojang.blaze3d.platform.InputConstants.KEYCODE_RETURN, 0);
            panel.keyPressed(key);
            panel.keyReleased(key);
        }, () -> {
            long copies = ChatStore.get().snapshot().stream()
                    .filter(message -> message.getRawText().contains(composeCjkText())).count();
            if (copies > 1) throw new AssertionError("Committed CJK message was duplicated: " + copies + " copies");
            return copies == 1 && composer().text().isEmpty() && nativeFrameReady("chat-composer");
        });
        addNativeCapture("compose-cjk-sent", true);
        addNativeParitySteps();
        add("compose-navigation-restore-atomchat", 12, () -> {
            AtomChatConfig.get().uiStyle = AtomChatConfig.UiStyle.ATOMCHAT;
            AtomChatConfig.get().miuixNavigationStyle = originalNavigationStyle;
            AtomChatConfig.get().blurEnabled = originalNativeBlur;
            if (!AtomChatConfig.saveChecked(AtomChatConfig.get())) throw new AssertionError("Compose fixture restore failed");
            AtomChatConfig.reload();
            if (AtomChatConfig.get().effectiveUiStyle() != AtomChatConfig.UiStyle.ATOMCHAT
                    || AtomChatConfig.get().effectiveMiuixNavigationStyle() != originalNavigationStyle) {
                throw new AssertionError("Legacy UI fixture was not restored");
            }
            panel.switchRoot(AppPage.SETTINGS);
        }, AtomChatAutomation::panelFrameReady);
    }

    /** Same visible run also exercises the real screen adapter's rapid Escape timing windows. */
    private static void addAboutPreviewNavigationChecks() {
        for (var style : AtomChatConfig.PageNavStyle.values()) {
            String prefix = "about-preview-escape-" + style.name().toLowerCase(java.util.Locale.ROOT);
            add(prefix + "-root", 20, () -> {
                AtomChatConfig.get().pageNavStyle = style;
                panel = new AtomChatScreen("");
                Minecraft.getInstance().setScreenAndShow(panel);
                panel.switchRoot(AppPage.CHAT_LIST);
            }, () -> previewRootReady());
            add(prefix + "-before-first-render", 20, () -> {
                panel.openWorldChat();
                for (int press = 0; press < 4; press++) previewEscape(false);
            }, () -> previewRootReady());
            boolean[] escaped = {false};
            add(prefix + "-during-push", 20, () -> panel.openWorldChat(), () -> {
                if (!escaped[0] && ageMs() >= 80) {
                    previewEscape(true);
                    escaped[0] = true;
                }
                return escaped[0] && previewRootReady();
            });
            add(prefix + "-reenter-operable", 20, () -> panel.openWorldChat(),
                    () -> nativeFrameReady("chat-composer"));
            add(prefix + "-repeated-during-pop", 20, () -> {
                for (int press = 0; press < 4; press++) previewEscape(false);
            }, () -> previewRootReady());
            add(prefix + "-fresh-root-escape-closes", 20, () -> previewEscape(false),
                    () -> Minecraft.getInstance().gui.screen() != panel);
        }
    }

    private static void previewEscape(boolean repeatedDown) {
        var key = new net.minecraft.client.input.KeyEvent(
                com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE, 0, 0);
        panel.keyPressed(key);
        if (repeatedDown) for (int repeat = 0; repeat < 4; repeat++) panel.keyPressed(key);
        panel.keyReleased(key);
    }

    private static boolean previewRootReady() throws Exception {
        if (Minecraft.getInstance().gui.screen() != panel)
            throw new AssertionError("Rapid Escape unexpectedly closed the outer panel");
        return panel.topPage() == AppPage.CHAT_LIST && nativeFrameReady("conversation-PUBLIC");
    }

    private static boolean nativeFrameReady(String tag) throws Exception {
        return AtomChatConfig.get().effectiveUiStyle() == AtomChatConfig.UiStyle.MIUIX
                && navigationSettled() && ageMs() > 500 && SkiaGraphics.renderedFrames() >= startingFrames + 3
                && !panel.nativeMiuixTags().isEmpty() && (tag == null || panel.nativeMiuixBounds(tag) != null);
    }

    private static String composeCjkText() {
        return "原生中文输入🌏 " + TOKEN + "-compose";
    }

    private static String nativeRichText() {
        return "富文本交互 " + TOKEN + "-rich";
    }

    /** Real pointer routes with isolated feed fixtures; no second-player delivery claim. */
    private static void addNativeParitySteps() {
        add("compose-parity-fixture", 15, () -> {
            var config = AtomChatConfig.get();
            nativeOriginalContentScale = config.contentScale;
            nativeOriginalOtherBubble = config.otherBubbleColor;
            nativeOriginalAnimations = config.animationEnabled;
            nativeOriginalPoke = config.avatarPokeEnabled;
            config.contentScale = 1.0F;
            config.otherBubbleColor = 0xFF542A7E;
            config.animationEnabled = true;
            config.avatarPokeEnabled = true;
            if (!AtomChatConfig.saveChecked(config)) throw new AssertionError("Parity fixture config save failed");
            var component = net.minecraft.network.chat.Component.literal(nativeRichText()).withStyle(style -> style
                    .withColor(0x36D98B).withClickEvent(new net.minecraft.network.chat.ClickEvent.SuggestCommand("/say " + TOKEN + "-rich")));
            nativeFixtureMessage = new com.atom.chat.chat.ChatMessage(component, false, false, null, null,
                    NATIVE_FIXTURE_PLAYER, "MiuixFixture", "MiuixFixture", nativeRichText());
            ChatStore.get().add(nativeFixtureMessage);
        }, () -> nativeFrameReady("message-avatar-" + nativeFixtureMessage.getId()));
        add("compose-toolbar-reference-image-order", 8, () -> {
            float[] previous = null;
            float[] composerBounds = panel.nativeMiuixBounds("chat-composer");
            for (String tag : List.of("chat-emoji", "chat-phrases", "chat-image")) {
                float[] bounds = panel.nativeMiuixBounds(tag);
                if (bounds == null || composerBounds == null || bounds[1] < composerBounds[3] - 1
                        || previous != null && bounds[0] < previous[2] - 1) {
                    throw new AssertionError("Reference screenshot composer tools must be emoji/phrases/image below the input: " + tag);
                }
                previous = bounds;
            }
            if (panel.nativeMiuixBounds("chat-stickers") != null) {
                throw new AssertionError("Stickers must be a tab inside the emoji popup, not a separate composer tool");
            }
            float[] send = panel.nativeMiuixBounds("chat-send");
            if (send == null || composerBounds == null || send[0] < composerBounds[2] - 1
                    || Math.min(send[3], composerBounds[3]) <= Math.max(send[1], composerBounds[1])) {
                throw new AssertionError("Reference screenshot send control must sit to the right of the input on the same row");
            }
        }, () -> true);
        add("compose-toolbar-emoji-open", 8, () -> clickNativeTag("chat-emoji"),
                () -> nativeFrameReady("composer-emoji-page"));
        addNativeCapture("compose-emoji-popup", false);
        add("compose-toolbar-kaomoji-tab", 8, () -> clickNativeText("atomchat.emoji.tab.kaomoji"),
                () -> nativeFrameReady("composer-kaomoji-page"));
        addNativeCapture("compose-kaomoji-popup", false);
        add("compose-toolbar-stickers-tab", 8, () -> clickNativeText("atomchat.emoji.tab.emote"),
                () -> nativeFrameReady("composer-stickers-page") && nativeFrameReady("sticker-add"));
        addNativeCapture("compose-stickers-popup", false);
        add("compose-sticker-add-hover", 8, () -> hoverNativeTag("sticker-add"),
                () -> nativeFrameReady("sticker-add-hovered"));
        addNativeCapture("compose-sticker-add-hover", false);
        add("compose-toolbar-emoji-close", 8, () -> clickNativeTag("chat-emoji"),
                () -> nativeFrameReady("chat-composer")
                        && panel.nativeMiuixBounds("composer-emoji-tabs") == null
                        && panel.nativeMiuixBounds("composer-stickers-page") == null);
        add("compose-toolbar-phrases-open", 8, () -> clickNativeTag("chat-phrases"),
                () -> nativeTextReady("atomchat.quick.add"));
        add("compose-toolbar-phrase-editor-open", 8, () -> clickNativeText("atomchat.quick.add"),
                () -> nativeTextReady("atomchat.config.save") && nativeTextReady("atomchat.config.cancel"));
        addNativeCapture("compose-phrase-editor", false);
        add("compose-toolbar-phrase-editor-cancel-refocus", 10, () -> {
            nativeFocusDiagnostic = false;
            clickNativeText("atomchat.config.cancel");
        }, () -> {
            boolean ready = nativeFrameReady("chat-composer") && nativeComposerHasTextFocus()
                    && panel.nativeMiuixTextBounds(net.minecraft.network.chat.Component.translatable("atomchat.quick.add").getString()) == null;
            if (!ready && !nativeFocusDiagnostic && ageMs() > 2000) {
                nativeFocusDiagnostic = true;
                capturePanel("compose-phrase-cancel-diagnostic");
                System.out.println("[AtomChat automation] phrase cancel diagnostic: composerFocus=" + nativeComposerHasTextFocus()
                        + "; addBounds=" + java.util.Arrays.toString(panel.nativeMiuixTextBounds(
                                net.minecraft.network.chat.Component.translatable("atomchat.quick.add").getString()))
                        + "; cancelBounds=" + java.util.Arrays.toString(panel.nativeMiuixTextBounds(
                                net.minecraft.network.chat.Component.translatable("atomchat.config.cancel").getString())));
            }
            return ready;
        });
        add("compose-toolbar-phrase-editor-refocused-input", 8, () -> {
            if (!nativeComposerHasTextFocus()) throw new AssertionError("Cancelled phrase editor did not restore composer focus");
            "焦点恢复".codePoints().forEach(codepoint -> panel.charTyped(new net.minecraft.client.input.CharacterEvent(codepoint)));
        }, () -> {
            if (!composer().text().equals("焦点恢复")) return false;
            composer().setText("");
            return true;
        });
        add("compose-avatar-single-delay-profile", 12, () -> {
            clickNativeTag("message-avatar-" + nativeFixtureMessage.getId());
            var wiring = (com.atom.chat.settings.AvatarClickWiring) panelField("avatarWiring");
            if (!wiring.hasPendingAvatarClick() || panel.topPage() != AppPage.WORLD_CHAT) {
                throw new AssertionError("Single avatar click bypassed original 300ms arbitration");
            }
        }, () -> {
            if (ageMs() < 250 && panel.topPage() != AppPage.WORLD_CHAT) {
                throw new AssertionError("Avatar profile opened before the click competition window");
            }
            return ageMs() >= 300 && panel.topPage() == AppPage.PROFILE_DETAIL && nativeFrameReady("navigate-back")
                    && ((com.atom.chat.page.ProfilePage) panelField("profilePage")).subject().uuid().equals(NATIVE_FIXTURE_PLAYER);
        });
        addNativeCapture("compose-avatar-other-profile", false);
        add("compose-avatar-profile-back", 10, () -> clickNativeTag("navigate-back"),
                () -> panel.topPage() == AppPage.WORLD_CHAT && nativeFrameReady("message-avatar-" + nativeFixtureMessage.getId()));
        add("compose-avatar-double-original-poke-arbitration", 10, () -> {
            nativePokeBefore = nativeFixturePokeTime();
            clickNativeTag("message-avatar-" + nativeFixtureMessage.getId());
            clickNativeTag("message-avatar-" + nativeFixtureMessage.getId());
            if (((com.atom.chat.settings.AvatarClickWiring) panelField("avatarWiring")).hasPendingAvatarClick()) {
                throw new AssertionError("Double avatar click left the delayed profile action armed");
            }
        }, () -> {
            if (panel.topPage() != AppPage.WORLD_CHAT) throw new AssertionError("Double avatar click also opened a profile");
            return ageMs() > 600 && nativeFixturePokeTime() > nativePokeBefore && nativeFrameReady("chat-composer");
        });
        add("compose-message-secondary-menu", 10, () -> {
            var bounds = panel.nativeMiuixBounds("message-" + nativeFixtureMessage.getId());
            clickNativeBounds(bounds, "fixture secondary message", 3);
        }, () -> nativeTextReady("atomchat.context.quote") && composer().text().isEmpty());
        addNativeCapture("compose-message-context-menu", false);
        add("compose-message-menu-reply-production-slot", 10, () -> clickNativeText("atomchat.context.quote"),
                () -> ((com.atom.chat.chat.ReplySlotStore) panelField("replySlots")).replyTarget() == nativeFixtureMessage
                        && nativeFrameReady("chat-composer"));
        add("compose-message-rich-single-click", 10, () -> {
            ((com.atom.chat.chat.ReplySlotStore) panelField("replySlots")).disarm();
            float[] bounds = panel.nativeMiuixTextBounds(nativeRichText());
            clickNativeBounds(bounds, "rich SuggestCommand text");
        }, () -> composer().text().equals("/say " + TOKEN + "-rich") && nativeFrameReady("chat-composer"));
        addNativeCapture("compose-rich-color-and-font-base", true);
        add("compose-config-color-visible-and-font-baseline", 8, () -> {
            Path imagePath = report.getParent().resolve("frames/compose-rich-color-and-font-base-composited.png");
            var image = javax.imageio.ImageIO.read(imagePath.toFile());
            if (image == null || nativeColorPixels(image, 0x542A7E) < 100 || nativeColorPixels(image, 0x36D98B) < 3) {
                throw new AssertionError("Configured bubble color or original rich span color absent from actual GPU frame");
            }
            float[] text = panel.nativeMiuixTextBounds(nativeRichText());
            if (text == null) throw new AssertionError("Rendered rich text has no measured bounds");
            nativeTextHeight = text[3] - text[1];
            AtomChatConfig.get().contentScale = 1.35F;
            if (!AtomChatConfig.saveChecked(AtomChatConfig.get())) throw new AssertionError("Content scale fixture save failed");
        }, () -> nativeFrameReady("chat-composer"));
        add("compose-config-font-scale-visible", 12, () -> { }, () -> {
            float[] text = panel.nativeMiuixTextBounds(nativeRichText());
            return text != null && text[3] - text[1] > nativeTextHeight * 1.1F && nativeFrameReady("chat-composer");
        });
        addNativeCapture("compose-rich-font-scaled", true);
        add("compose-parity-restore-fixture", 12, () -> {
            var config = AtomChatConfig.get();
            config.contentScale = nativeOriginalContentScale;
            config.otherBubbleColor = nativeOriginalOtherBubble;
            config.animationEnabled = nativeOriginalAnimations;
            config.avatarPokeEnabled = nativeOriginalPoke;
            if (!AtomChatConfig.saveChecked(config)) throw new AssertionError("Parity config restore failed");
            composer().setText("");
            ((com.atom.chat.chat.ReplySlotStore) panelField("replySlots")).disarm();
            var field = ChatStore.class.getDeclaredField("messages");
            field.setAccessible(true);
            @SuppressWarnings("unchecked") var messages = (List<com.atom.chat.chat.ChatMessage>) field.get(ChatStore.get());
            synchronized (ChatStore.get()) { messages.removeIf(message -> message.getId() == nativeFixtureMessage.getId()); }
        }, () -> nativeFrameReady("chat-composer"));
        addNativeLifecycleSteps();
        addNativeMiuixRegressionSteps();
    }

    private static void addNativeMiuixRegressionSteps() {
        add("compose-ime-open-world", 20, () -> {
            composer().setText("");
            panel.openWorldChat();
        }, () -> nativeFrameReady("chat-composer"));
        add("compose-ime-focus-carrier", 10, () -> {
            clickNativeTag("chat-composer");
            AutomationMiuixRegression.prepareImeWindow();
        }, () -> nativeFrameReady("chat-composer") && nativeComposerHasTextFocus()
                && AutomationMiuixRegression.imeWindowReady());
        add("compose-ime-sdl-candidate-commit", 10,
                () -> AutomationMiuixRegression.imeCommit(panel, composeCjkText()),
                () -> nativeFrameReady("chat-send") && AutomationMiuixRegression.imeCommitReady(panel));
        add("compose-ime-sdl-blur", 10,
                () -> AutomationMiuixRegression.imeLeaveComposer(panel),
                () -> ageMs() > 700 && AutomationMiuixRegression.imeBlurReady(panel));
        add("compose-ime-sdl-refocus", 10,
                () -> AutomationMiuixRegression.imeReturnToComposer(panel),
                () -> ageMs() > 700 && AutomationMiuixRegression.imeRefocusReady(panel));
        add("compose-ime-clear-fixture", 10, () -> composer().setText(""),
                () -> nativeFrameReady("chat-composer") && composer().text().isEmpty());
        int chatHoldSeconds = Math.min(120, Math.max(0,
                Integer.getInteger("atomchat.automation.chatHoldSeconds", 0)));
        if (chatHoldSeconds > 0) {
            addNativeCapture("compose-chat-hold-before", false);
            add("compose-chat-hold", chatHoldSeconds + 15,
                    () -> System.out.println("[AtomChat automation] Holding focused chat for "
                            + chatHoldSeconds + " seconds"),
                    () -> ageMs() >= chatHoldSeconds * 1000L && nativeFrameReady("chat-composer"));
            addNativeCapture("compose-chat-hold-after", false);
        }
        add("compose-about-atomchat-layout", 20,
                () -> AutomationMiuixRegression.about(panel, report.getParent().resolve("frames")),
                AutomationMiuixRegression::aboutReady);
        add("compose-topbar-open-settings", 20, () -> panel.switchRoot(AppPage.SETTINGS),
                () -> ageMs() > 700 && nativeFrameReady("settings-section-appearance"));
        add("compose-topbar-open-appearance", 20, () -> clickNativeTag("settings-section-appearance"),
                () -> ageMs() > 700 && panel.topPage() == AppPage.SETTINGS_SECTION && nativeFrameReady("miuix-top-app-bar"));
        add("compose-topbar-expanded-gpu-baseline", 20,
                () -> AutomationMiuixRegression.baseline("compose-topbar-wheel", panel, report.getParent().resolve("frames")),
                AutomationMiuixRegression::baselineReady);
        add("compose-topbar-collapse-real-wheel", 20, () -> {
            nativeScrollAt = 0;
            AutomationMiuixRegression.topBarScroll();
        }, () -> {
            nativeWheel(-4);
            return AutomationMiuixRegression.topBarScrollReady();
        });
        add("compose-topbar-restore-real-wheel", 20, () -> {
            nativeScrollAt = 0;
            AutomationMiuixRegression.topBarRestore();
        }, () -> {
            nativeWheel(12);
            return AutomationMiuixRegression.topBarScrollReady();
        });
        addNativeResizeSteps();
        add("compose-scroll-history-fixture", 20, () -> {
            nativeScrollOriginal = ChatStore.get().snapshot();
            nativeScrollFixture.clear();
            var field = ChatStore.class.getDeclaredField("messages");
            field.setAccessible(true);
            @SuppressWarnings("unchecked") var messages = (List<com.atom.chat.chat.ChatMessage>) field.get(ChatStore.get());
            synchronized (ChatStore.get()) {
                messages.clear();
                for (int i = 0; i < 90; i++) {
                    String text = "Wheel boundary regression " + TOKEN + " row " + i;
                    var message = new com.atom.chat.chat.ChatMessage(net.minecraft.network.chat.Component.literal(text),
                            false, false, null, null, NATIVE_FIXTURE_PLAYER, "ScrollFixture", "ScrollFixture", text);
                    nativeScrollFixture.add(message);
                    messages.add(message);
                }
            }
            ((com.atom.chat.history.HistoryFoldStore) panelField("historyFold")).resetHistoryWindows();
            panel.switchRoot(AppPage.CHAT_LIST);
            panel.openWorldChat();
            nativeScrollAt = 0;
        }, () -> nativeFrameReady("message-" + nativeScrollFixture.getLast().getId()));
        add("compose-scroll-up-to-oldest-visible", 30, () -> nativeScrollAt = 0, () -> {
            if (!nativeFrameReady(null)) return false;
            var oldest = panel.nativeMiuixBounds("message-" + nativeScrollFixture.getFirst().getId());
            if (oldest != null && oldest[3] - oldest[1] > 15 && ageMs() > 600) {
                nativeScrollOldestY = oldest[1];
                return true;
            }
            nativeWheel(25);
            return false;
        });
        add("compose-scroll-up-past-boundary", 20, () -> nativeScrollAt = 0, () -> {
            nativeWheel(35);
            return ageMs() > 900;
        });
        add("compose-scroll-down-recovers-visible-rows", 25, () -> nativeScrollAt = 0, () -> {
            if (ageMs() > 600) {
                var oldest = panel.nativeMiuixBounds("message-" + nativeScrollFixture.getFirst().getId());
                var later = panel.nativeMiuixBounds("message-" + nativeScrollFixture.get(12).getId());
                float[] composerBounds = panel.nativeMiuixBounds("chat-composer");
                if (later != null && composerBounds != null && later[3] > 50 && later[1] < composerBounds[1]
                        && (oldest == null || oldest[1] < nativeScrollOldestY - 20)) return true;
            }
            nativeWheel(-4);
            return false;
        });
        addNativeCapture("compose-scroll-down-recovered", false);
        add("compose-scroll-restore-history", 20, () -> {
            var field = ChatStore.class.getDeclaredField("messages"); field.setAccessible(true);
            @SuppressWarnings("unchecked") var messages = (List<com.atom.chat.chat.ChatMessage>) field.get(ChatStore.get());
            synchronized (ChatStore.get()) { messages.clear(); messages.addAll(nativeScrollOriginal); }
            ((com.atom.chat.history.HistoryFoldStore) panelField("historyFold")).resetHistoryWindows();
            nativeRegressionAnimations = AtomChatConfig.get().animationEnabled;
            nativeRegressionNavigation = AtomChatConfig.get().effectiveMiuixNavigationStyle();
            AtomChatConfig.get().miuixNavigationStyle = AtomChatConfig.MiuixNavigationStyle.FLOATING;
            if (!AtomChatConfig.saveChecked(AtomChatConfig.get())) throw new AssertionError("Regression config save failed");
            panel.switchRoot(AppPage.CHAT_LIST);
        }, () -> nativeFrameReady("nav-chats") && panel.nativeMiuixBounds("floating-navigation-bar") != null);
        add("compose-dynamic-world-checker-fixture", 25, () -> {
            requireFixtureWorld();
            AutomationMiuixRegression.prepareCheckerFloor();
        }, AutomationMiuixRegression::checkerFloorReady);
        add("compose-dynamic-world-floating-visible-pixels", 25,
                () -> AutomationMiuixRegression.dynamicWorld(panel, report.getParent().resolve("frames")),
                AutomationMiuixRegression::dynamicWorldReady);
        add("compose-dynamic-world-checker-restore", 25, () -> {
            AutomationMiuixRegression.restoreCheckerFloor();
            panel.switchRoot(AppPage.PROFILE);
        }, () -> AutomationMiuixRegression.checkerFloorRestored() && nativeFrameReady("nav-profile"));
        for (boolean enabled : List.of(true, false)) {
            String suffix = enabled ? "on" : "off";
            add("compose-motion-config-" + suffix, 20, () -> {
                AtomChatConfig.get().animationEnabled = enabled;
                if (!AtomChatConfig.saveChecked(AtomChatConfig.get())) throw new AssertionError("Animation config save failed");
            }, () -> nativeFrameReady("nav-profile"));
            addNativeMotion("root-" + suffix, enabled, () -> clickNativeTag("nav-settings"), AppPage.SETTINGS);
            addNativeMotion("push-" + suffix, enabled, () -> clickNativeTag("settings-section-appearance"), AppPage.SETTINGS_SECTION);
            addNativeMotion("pop-" + suffix, enabled, () -> clickNativeTag("navigate-back"), AppPage.SETTINGS);
            add("compose-motion-return-profile-" + suffix, 20, () -> clickNativeTag("nav-profile"),
                    () -> panel.topPage() == AppPage.PROFILE && nativeFrameReady("nav-profile"));
        }
        add("compose-regression-restore", 20, () -> {
            AtomChatConfig.get().animationEnabled = nativeRegressionAnimations;
            AtomChatConfig.get().miuixNavigationStyle = nativeRegressionNavigation;
            if (!AtomChatConfig.saveChecked(AtomChatConfig.get())) throw new AssertionError("Regression config restore failed");
            panel.openWorldChat();
        }, () -> nativeFrameReady("chat-composer"));
    }

    private static void addNativeMotion(String name, boolean enabled, Action action, AppPage target) {
        add("compose-motion-" + name + "-baseline", 20,
                () -> AutomationMiuixRegression.baseline("compose-motion-" + name, panel, report.getParent().resolve("frames")),
                AutomationMiuixRegression::baselineReady);
        add("compose-motion-" + name + "-gpu-intermediates", 20, () -> {
            AutomationMiuixRegression.navigation(enabled);
            action.run();
        }, () -> panel.topPage() == target && AutomationMiuixRegression.navigationReady());
    }

    private static void nativeWheel(double amount) {
        if (ageMs() - nativeScrollAt < 160) return;
        var window = Minecraft.getInstance().getWindow();
        float[] composerBounds = panel.nativeMiuixBounds("chat-composer");
        double y = composerBounds == null ? window.getGuiScaledHeight() / 2.0 : composerBounds[1] / 2.0;
        panel.mouseScrolled(window.getGuiScaledWidth() / 2.0, y, 0, amount);
        nativeScrollAt = ageMs();
    }

    private static void addNativeResizeSteps() {
        add("compose-resize-fixture", 10, AutomationMiuixResize::begin,
                () -> nativeFrameReady("miuix-top-app-bar"));
        for (String id : List.of("width", "scale", "contentscale")) {
            for (boolean maximum : new boolean[]{true, false}) {
                String suffix = id + (maximum ? "-max" : "-min");
                add("compose-resize-reveal-" + suffix, 20, () -> {},
                        () -> nativeFrameReady(null) && AutomationMiuixResize.reveal(panel, id));
                add("compose-resize-drag-" + suffix, 20,
                        () -> AutomationMiuixResize.drag(panel, id, maximum),
                        () -> ageMs() > 500 && nativeFrameReady("miuix-top-app-bar") && AutomationMiuixResize.settled(panel));
                addNativeCapture("compose-resize-" + suffix, false);
            }
        }
        add("compose-resize-restore-fixture", 20, AutomationMiuixResize::restore,
                () -> ageMs() > 500 && nativeFrameReady("miuix-top-app-bar"));
        add("compose-resize-back-hit-test", 20, () -> clickNativeTag("navigate-back"),
                () -> panel.topPage() == AppPage.SETTINGS && nativeFrameReady("settings-section-appearance"));
    }

    private static void addNativeLifecycleSteps() {
        add("compose-gpu-lifecycle-half-scale", 20, () -> {
            var window = Minecraft.getInstance().getWindow();
            nativeLifecycleWidth = window.getWidth();
            nativeLifecycleHeight = window.getHeight();
            nativeLifecycleScale = AtomChatConfig.get().uiScale;
            AtomChatConfig.get().uiScale = 0.5F;
            if (!AtomChatConfig.saveChecked(AtomChatConfig.get())) throw new AssertionError("Half-scale fixture save failed");
        }, () -> nativeFrameReady("chat-emoji") && panel.nativeMiuixBounds("chat-composer") != null);
        add("compose-gpu-lifecycle-half-scale-operable", 20, () -> {
            clickNativeTag("chat-emoji");
            requestNativeLifecycleImage("compose-gpu-half-scale-operable");
        }, () -> nativeTextReady("atomchat.emoji.tab.emote") && nativeLifecycleImageReady());
        add("compose-gpu-lifecycle-restore-scale", 20, () -> {
            clickNativeTag("chat-emoji");
            AtomChatConfig.get().uiScale = nativeLifecycleScale;
            if (!AtomChatConfig.saveChecked(AtomChatConfig.get())) throw new AssertionError("Original scale restore failed");
        }, () -> AtomChatConfig.get().uiScale == nativeLifecycleScale && nativeFrameReady("chat-composer"));
        add("compose-gpu-lifecycle-window-resize", 25,
                () -> Minecraft.getInstance().getWindow().setWindowed(1777, 997),
                () -> Minecraft.getInstance().getWindow().getWidth() == 1777
                        && Minecraft.getInstance().getWindow().getHeight() == 997
                        && nativeFrameReady("chat-phrases") && panel.nativeMiuixBounds("chat-composer") != null);
        add("compose-gpu-lifecycle-resized-operable", 20, () -> {
            clickNativeTag("chat-phrases");
            requestNativeLifecycleImage("compose-gpu-resized-operable");
        }, () -> nativeTextReady("atomchat.quick.add") && nativeLifecycleImageReady());
        add("compose-gpu-lifecycle-restore-window", 25, () -> {
            clickNativeTag("chat-phrases");
            Minecraft.getInstance().getWindow().setWindowed(nativeLifecycleWidth, nativeLifecycleHeight);
        }, () -> Minecraft.getInstance().getWindow().getWidth() == nativeLifecycleWidth
                && Minecraft.getInstance().getWindow().getHeight() == nativeLifecycleHeight
                && AtomChatConfig.get().uiScale == nativeLifecycleScale && nativeFrameReady("chat-composer"));
    }

    private static void requestNativeLifecycleImage(String name) throws Exception {
        nativeLifecycleImage = report.getParent().resolve("frames").resolve(name + "-composited.png");
        Files.createDirectories(nativeLifecycleImage.getParent());
        presentationCapture = AutomationPresentationCapture.request(nativeLifecycleImage);
    }

    private static boolean nativeLifecycleImageReady() throws Exception {
        if (!presentationReady()) return false;
        var image = javax.imageio.ImageIO.read(nativeLifecycleImage.toFile());
        var window = Minecraft.getInstance().getWindow();
        if (image == null || image.getWidth() != window.getWidth() || image.getHeight() != window.getHeight()) {
            throw new AssertionError("Rebuilt Compose target did not produce a full-resolution GPU screenshot");
        }
        var colors = new java.util.HashSet<Integer>();
        for (int y = 0; y < image.getHeight(); y += 7) for (int x = 0; x < image.getWidth(); x += 7) {
            colors.add(image.getRGB(x, y));
        }
        if (colors.size() < 16) throw new AssertionError("Rebuilt Compose GPU screenshot is empty or flat");
        return true;
    }

    private static long nativeFixturePokeTime() throws Exception {
        @SuppressWarnings("unchecked") var pokes = (Map<java.util.UUID, Long>) panelField("miuixPokes");
        return pokes.getOrDefault(NATIVE_FIXTURE_PLAYER, 0L);
    }

    private static boolean nativeComposerHasTextFocus() throws Exception {
        var bridge = panelField("miuixBridge");
        if (bridge == null) return false;
        var hasTextFocus = bridge.getClass().getDeclaredMethod("hasTextFocus");
        hasTextFocus.setAccessible(true);
        var host = panelField("miuixHost");
        var composerFocus = host.getClass().getDeclaredField("composerFocus");
        composerFocus.setAccessible(true);
        return (boolean) hasTextFocus.invoke(bridge) && composerFocus.getBoolean(host);
    }

    private static int nativeColorPixels(java.awt.image.BufferedImage image, int rgb) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int pixel = image.getRGB(x, y);
            if (Math.abs((pixel >> 16 & 255) - (rgb >> 16 & 255)) <= 4
                    && Math.abs((pixel >> 8 & 255) - (rgb >> 8 & 255)) <= 4
                    && Math.abs((pixel & 255) - (rgb & 255)) <= 4) count++;
        }
        return count;
    }

    private static boolean nativeTextReady(String key) throws Exception {
        return nativeFrameReady(null)
                && panel.nativeMiuixTextBounds(net.minecraft.network.chat.Component.translatable(key).getString()) != null;
    }

    private static boolean nativeScrollUntilVisible(String tag) throws Exception {
        if (!nativeFrameReady(null)) return false;
        float[] bounds = panel.nativeMiuixBounds(tag);
        if (bounds != null && bounds[3] - bounds[1] >= 20) return true;
        if (ageMs() - nativeScrollAt > 250) {
            var window = Minecraft.getInstance().getWindow();
            panel.mouseScrolled(window.getGuiScaledWidth() / 2.0, window.getGuiScaledHeight() / 2.0, 0, -3);
            nativeScrollAt = ageMs();
        }
        return false;
    }

    private static void clickNativeTag(String tag) throws Exception {
        clickNativeBounds(panel.nativeMiuixBounds(tag), tag);
    }

    private static void clickNativeText(String key) throws Exception {
        String label = net.minecraft.network.chat.Component.translatable(key).getString();
        clickNativeBounds(panel.nativeMiuixTextBounds(label), label);
    }

    private static void clickNativeBounds(float[] bounds, String description) throws Exception {
        clickNativeBounds(bounds, description, 1);
    }

    private static void clickNativeBounds(float[] bounds, String description, int sdlButton) throws Exception {
        if (!navigationSettled()) throw new AssertionError("Native pointer attempted during navigation");
        if (bounds == null || bounds.length != 4 || bounds[2] <= bounds[0] || bounds[3] <= bounds[1]) {
            throw new AssertionError("Missing visible Compose semantics: " + description + "; tags=" + panel.nativeMiuixTags());
        }
        // Native semantic coordinates are GUI pixels, unlike the legacy virtual layout rectangles.
        var event = new net.minecraft.client.input.MouseButtonEvent((bounds[0] + bounds[2]) / 2.0,
                (bounds[1] + bounds[3]) / 2.0, new net.minecraft.client.input.MouseButtonInfo(sdlButton, 0));
        panel.mouseClicked(event, false);
        panel.mouseReleased(event);
    }

    private static void hoverNativeTag(String tag) throws Exception {
        if (!navigationSettled()) throw new AssertionError("Native pointer attempted during navigation");
        float[] bounds = panel.nativeMiuixBounds(tag);
        if (bounds == null || bounds.length != 4 || bounds[2] <= bounds[0] || bounds[3] <= bounds[1]) {
            throw new AssertionError("Missing visible Compose semantics: " + tag + "; tags=" + panel.nativeMiuixTags());
        }
        var client = Minecraft.getInstance();
        var window = client.getWindow();
        int screenWidth = window.getScreenWidth(), screenHeight = window.getScreenHeight();
        int guiWidth = window.getGuiScaledWidth(), guiHeight = window.getGuiScaledHeight();
        if (screenWidth <= 0 || screenHeight <= 0 || guiWidth <= 0 || guiHeight <= 0) {
            throw new AssertionError("Cannot move the automation cursor with an empty window viewport");
        }
        double guiX = (bounds[0] + bounds[2]) / 2.0;
        double guiY = (bounds[1] + bounds[3]) / 2.0;
        double screenX = guiX * screenWidth / guiWidth;
        double screenY = guiY * screenHeight / guiHeight;
        // Use Minecraft's own cursor callback so every later render poll keeps the hover at this tag.
        client.mouseHandler.onMove(window.handle(), screenX, screenY, 0.0, 0.0);
    }

    private static void addNativeCapture(String name, boolean worldRequired) {
        add(name + "-gpu-capture", 20, () -> {
            Path frames = report.getParent().resolve("frames");
            Files.createDirectories(frames);
            Map<String, Object> scene = new LinkedHashMap<>();
            scene.put("navigationStyle", AtomChatConfig.get().effectiveMiuixNavigationStyle().name());
            scene.put("page", panel.topPage().id());
            scene.put("minecraftWindowActive", Minecraft.getInstance().isWindowActive());
            Map<String, float[]> bounds = new LinkedHashMap<>();
            for (String tag : panel.nativeMiuixTags()) {
                float[] rectangle = panel.nativeMiuixBounds(tag);
                if (rectangle != null) bounds.put(tag, rectangle);
            }
            scene.put("visibleSemanticBoundsInGuiCoordinates", bounds);
            Files.writeString(frames.resolve(name + "-semantics.json"), new GsonBuilder().setPrettyPrinting().create().toJson(scene));
            presentationCapture = AutomationPresentationCapture.request(frames.resolve(name + "-composited.png"));
            var world = PanelBlurRenderer.worldFrame();
            if (worldRequired && world == null) {
                var bridge = panelField("miuixBridge");
                var snapshot = bridge.getClass().getDeclaredMethod("performanceSnapshot");
                snapshot.setAccessible(true);
                @SuppressWarnings("unchecked") var stats = (Map<String, Object>) snapshot.invoke(bridge);
                if (!Boolean.TRUE.equals(stats.get("gpuActive"))
                        || !(stats.get("worldGpuFrames") instanceof Number copies) || copies.longValue() < 1
                        || !(stats.get("worldImports") instanceof Number imports) || imports.longValue() < 1) {
                    throw new AssertionError("Native blur has neither CPU world source nor an imported actual GPU world copy: " + stats);
                }
                scene.put("gpuWorldSource", stats);
                Files.writeString(frames.resolve(name + "-semantics.json"), new GsonBuilder().setPrettyPrinting().create().toJson(scene));
                result(name + "-world-gpu-source", "PASS", "Full-resolution world texture imported by native renderer; copies="
                        + copies + "; imports=" + imports + "; composited PNG provides pixel evidence");
            }
            if (world != null) {
                var window = Minecraft.getInstance().getWindow();
                int viewportWidth = window.getScreenWidth(), viewportHeight = window.getScreenHeight();
                if (world.width() <= 0 || world.height() <= 0
                        || world.rgba().length != (long) world.width() * world.height() * 4
                        || world.originX() < 0 || world.originY() < 0
                        || (long) world.originX() + world.width() > viewportWidth
                        || (long) world.originY() + world.height() > viewportHeight) {
                    throw new AssertionError("Native world region must contain original-resolution RGBA pixels inside the framebuffer");
                }
                float[] sample = new float[4];
                String[] fields = {"nativeCropX", "nativeCropY", "nativeCropWidth", "nativeCropHeight"};
                for (int i = 0; i < fields.length; i++) {
                    var field = PanelBlurRenderer.class.getDeclaredField(fields[i]);
                    field.setAccessible(true);
                    sample[i] = field.getFloat(null);
                }
                float sampleLeft = Math.max(0, sample[0]), sampleTop = Math.max(0, sample[1]);
                float sampleRight = Math.min(viewportWidth, sample[0] + sample[2]);
                float sampleBottom = Math.min(viewportHeight, sample[1] + sample[3]);
                if (sample[2] <= 0 || sample[3] <= 0 || world.originX() > sampleLeft + 1
                        || world.originY() > sampleTop + 1 || world.originX() + world.width() < sampleRight - 1
                        || world.originY() + world.height() < sampleBottom - 1) {
                    throw new AssertionError("Native world region does not cover the visible panel sample: region="
                            + world.originX() + "," + world.originY() + "," + world.width() + "," + world.height()
                            + "; sample=" + java.util.Arrays.toString(sample));
                }
                scene.put("worldRegion", Map.of("originX", world.originX(), "originY", world.originY(),
                        "width", world.width(), "height", world.height(), "sequence", world.sequence(),
                        "viewportWidth", viewportWidth, "viewportHeight", viewportHeight,
                        "panelSampleBoundsFramebufferXYWH", sample));
                Files.writeString(frames.resolve(name + "-semantics.json"), new GsonBuilder().setPrettyPrinting().create().toJson(scene));
                var image = new java.awt.image.BufferedImage(world.width(), world.height(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                byte[] rgba = world.rgba();
                for (int y = 0; y < world.height(); y++) for (int x = 0; x < world.width(); x++) {
                    int offset = (y * world.width() + x) * 4;
                    image.setRGB(x, y, (rgba[offset + 3] & 255) << 24 | (rgba[offset] & 255) << 16
                            | (rgba[offset + 1] & 255) << 8 | rgba[offset + 2] & 255);
                }
                if (!javax.imageio.ImageIO.write(image, "png", frames.resolve(name + "-world-region.png").toFile())) {
                    throw new AssertionError("No PNG encoder for native world artifact");
                }
                result(name + "-world-readback", "PASS", "Actual unblurred original-resolution region " + world.width() + "x" + world.height()
                        + " at " + world.originX() + "," + world.originY() + "; sequence=" + world.sequence());
            }
        }, AtomChatAutomation::presentationReady);
    }

    private static UiLayout.Rect styleSelectorRectangle() throws Exception {
        return settingsActionRectangle("ui_style");
    }

    private static UiLayout.Rect settingsActionRectangle(String action) throws Exception {
        var page = settingsPage();
        var rows = page.rows(SettingsSection.APPEARANCE);
        int index = -1;
        for (int row = 0; row < rows.size(); row++) {
            if (action.equals(rows.get(row).actionId())) { index = row; break; }
        }
        if (index < 0) throw new AssertionError("Appearance theme chip lost action " + action);
        var method = SettingsSectionPage.class.getDeclaredMethod("rowRect", SettingsSection.class,
                List.class, int.class, float.class, UiLayout.class);
        method.setAccessible(true);
        var scroll = ((com.atom.chat.ui.ScrollSyncService) panelField("scrollSync")).detailScroll();
        return (UiLayout.Rect) method.invoke(page, SettingsSection.APPEARANCE, rows, index, scroll.getScrollY(),
                ScreenMetrics.detailLayout(Minecraft.getInstance().getWindow()));
    }

    private static void scrollStyleSelectorIntoView() throws Exception {
        scrollSettingsActionIntoView("ui_style");
    }

    private static void scrollSettingsActionIntoView(String action) throws Exception {
        var detail = ScreenMetrics.detailLayout(Minecraft.getInstance().getWindow());
        var scroll = ((com.atom.chat.ui.ScrollSyncService) panelField("scrollSync")).detailScroll();
        scroll.setContent(settingsPage().measureContent(detail, SettingsSection.APPEARANCE), detail.list.h());
        var rectangle = settingsActionRectangle(action);
        float top = detail.list.y() + settingsPage().chipBarHeight(SettingsSection.APPEARANCE);
        if (rectangle.h() <= 1) throw new AssertionError("Style selector is folded away");
        if (rectangle.y() < top || rectangle.bottom() > detail.list.bottom()) {
            scroll.scrollTo(scroll.getScrollY() + rectangle.y() - top, false);
        }
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
                && (AtomChatConfig.get().effectiveUiStyle() != AtomChatConfig.UiStyle.MIUIX
                    || !panel.nativeMiuixTags().contains("miuix-navigation-running"))
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

    private static boolean verifyStartupGpuDiagnostics() throws Exception {
        if (!AtomChatConfig.get().debug) throw new AssertionError("Debug was not enabled before startup");
        var device = com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo();
        String requested = System.getenv().getOrDefault("ATOMCHAT_AUTOMATION_GRAPHICS_API", "unspecified");
        String expected = requested.equals("prefer-vulkan") ? "vulkan" : "opengl";
        STARTUP_GRAPHICS.put("requestedGraphicsApi", requested);
        STARTUP_GRAPHICS.put("activeGraphicsBackend", device.backendName());
        STARTUP_GRAPHICS.put("graphicsDevice", device.name());
        STARTUP_GRAPHICS.put("graphicsDriver", device.driverInfo());
        if (!requested.equals("unspecified")
                && !device.backendName().toLowerCase(java.util.Locale.ROOT).contains(expected)) {
            throw new AssertionError("Requested " + requested + " but active backend is " + device.backendName());
        }
        Path log = Minecraft.getInstance().gameDirectory.toPath().resolve("logs/latest.log");
        if (!Files.isRegularFile(log)) return false;
        // Log4j may use the native Windows charset; unrelated timestamp text
        // must not prevent the ASCII diagnostic row from being inspected.
        var charset = java.nio.charset.Charset.forName(System.getProperty("native.encoding", "UTF-8"));
        String gpu = new String(Files.readAllBytes(log), charset).lines()
                .filter(line -> line.matches("\\[AtomChat\\]\\[startup\\]\\s+gpu\\s+.*"))
                .findFirst().orElse(null);
        if (gpu == null) return false;
        for (String value : List.of(device.backendName(), device.name(), device.driverInfo())) {
            if (value.isBlank() || !gpu.contains(value.trim())) {
                throw new AssertionError("Startup GPU row missing active device metadata " + value + ": " + gpu);
            }
        }
        STARTUP_GRAPHICS.put("loggedGpuRow", gpu.trim());
        return true;
    }

    private static String runtimeState() {
        var client = Minecraft.getInstance();
        if (client == null) return "Minecraft instance not constructed";
        var screen = client.gui == null ? null : client.gui.screen();
        return "screen=" + (screen == null ? "null" : screen.getClass().getName() + ":" + translationKey(screen.getTitle()))
                + ", player=" + (client.player != null) + ", level=" + (client.level != null)
                + ", connection=" + (client.getConnection() != null)
                + ", integratedServer=" + (client.getSingleplayerServer() != null)
                + ", windowActive=" + client.isWindowActive()
                + ", framebuffer=" + client.getWindow().getWidth() + "x" + client.getWindow().getHeight()
                + ", window=" + client.getWindow().getScreenWidth() + "x" + client.getWindow().getScreenHeight();
    }

    private static void tick(ClientTickEvent.Post event) {
        if (finished) return;
        if (index >= STEPS.size()) {
            if (HISTORY_ONLY || PERFORMANCE || BLUR_MOTION || NATIVE_PARITY || ABOUT_PREVIEW) {
                finishReport(RESULTS.stream().anyMatch(r -> r.get("status").equals("SKIP")) ? "PARTIAL" : "PASS");
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
            root.put("mode", ABOUT_PREVIEW ? "about-preview" : NATIVE_PARITY ? "native-parity" : BLUR_MOTION ? "blur-motion" : PERFORMANCE ? "performance" : HISTORY_ONLY ? "history-only" : "full");
            if (BLUR_MOTION) root.put("blurMotionFrames", AutomationBlurMotion.frames());
            if (PERFORMANCE) root.put("performance", AutomationPerformance.results());
            if (!STARTUP_GRAPHICS.isEmpty()) root.put("startupGraphics", STARTUP_GRAPHICS);
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
    static final class SkipCheck extends RuntimeException {
        SkipCheck(String message) { super(message); }
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    @FunctionalInterface private interface Check { boolean get() throws Exception; }
}
