package com.atom.chat.automation;

import com.atom.chat.screen.AtomChatScreen;
import com.atom.chat.screen.ScreenMetrics;
import com.atom.chat.config.AtomChatConfig;
import com.atom.chat.nav.AppPage;
import com.atom.chat.settings.SettingsSection;
import com.atom.chat.render.PanelBlurRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Real compositor evidence. This probe never advances a production animation clock. */
final class AutomationMiuixRegression {
    private record Frame(String file, long millis, boolean running, long sourceGpuCopies,
                         long sourceReadbackFrames, long sourceReadbackBytes, java.util.Map<String, float[]> chromeBounds) { }
    private static final List<Frame> frames = new ArrayList<>();
    private static final List<CompletableFuture<Void>> pending = new ArrayList<>();
    private static AtomChatScreen panel;
    private static Path directory;
    private static String name;
    private static String mode;
    private static long start;
    private static long lastCapture;
    private static boolean animations;
    private static boolean topBarRestoring;
    private static int topBarSourceFrameIndex;
    private static float pitch;
    private static float yaw;
    private static long worldElapsedMillis;
    private static long startingGpuCopies, startingReadbackFrames, startingReadbackBytes;
    private static long endingGpuCopies, endingReadbackFrames, endingReadbackBytes;
    private static final java.util.Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> originalFloor = new java.util.LinkedHashMap<>();
    private static CompletableFuture<Void> floorUpdate;
    private static net.minecraft.core.BlockPos floorCenter;
    private static long floorClientReady;
    private static float[] bar;
    private static Throwable failure;
    private static String imeCommittedText;
    private static long imeFocusRequestedAt;
    private static boolean imeOsFocusUnavailable;

    private AutomationMiuixRegression() { }

    /** Background Gradle launches need an explicit OS focus precondition for an IME probe. */
    static void prepareImeWindow() {
        long window = Minecraft.getInstance().getWindow().handle();
        imeFocusRequestedAt = System.nanoTime();
        imeOsFocusUnavailable = false;
        System.out.println("[AtomChat automation] IME focus before preparation: " + imeWindowState());
        long flags = org.lwjgl.sdl.SDLVideo.SDL_GetWindowFlags(window);
        // The automation mixin intentionally creates and keeps the main window
        // hidden. OS keyboard/IME focus requires showing that game window first.
        if ((flags & org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIDDEN) != 0)
            traceWindowCall("SDL_ShowWindow", () -> org.lwjgl.sdl.SDLVideo.SDL_ShowWindow(window));
        if ((flags & org.lwjgl.sdl.SDLVideo.SDL_WINDOW_MINIMIZED) != 0)
            traceWindowCall("SDL_RestoreWindow", () -> org.lwjgl.sdl.SDLVideo.SDL_RestoreWindow(window));
        traceWindowCall("SDL_RaiseWindow", () -> org.lwjgl.sdl.SDLVideo.SDL_RaiseWindow(window));
        System.out.println("[AtomChat automation] IME focus after preparation: " + imeWindowState());
    }

    private static void traceWindowCall(String name, java.util.function.BooleanSupplier operation) {
        long started = System.nanoTime();
        System.err.println("[AtomChat window-call] begin " + name + " wallMs=" + System.currentTimeMillis());
        AutomationRenderStallDiagnostics.enter(AutomationRenderStallDiagnostics.Stage.WINDOW_ACTIVATION);
        try {
            boolean ok = operation.getAsBoolean();
            System.err.println("[AtomChat window-call] end " + name + " ok=" + ok
                    + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000.0);
            if (!ok) throw new AssertionError(name + " failed: " + org.lwjgl.sdl.SDLError.SDL_GetError());
        } finally {
            AutomationRenderStallDiagnostics.enter(AutomationRenderStallDiagnostics.Stage.FRAME);
        }
    }

    static boolean imeWindowReady() {
        // SDL_RaiseWindow is asynchronous on Windows; let the normal event pump settle focus.
        if (org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyboardFocus()
                == Minecraft.getInstance().getWindow().handle()) return true;
        if (System.nanoTime() - imeFocusRequestedAt > 5_000_000_000L) {
            if (org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyboardFocus() == 0) {
                imeOsFocusUnavailable = true;
                throw new AtomChatAutomation.SkipCheck("Desktop refused OS keyboard focus; physical IME candidate window needs manual verification. "
                        + "Subsequent checks still validate SDL activation and the real text callbacks. " + imeWindowState());
            }
            throw new AssertionError("IME probe could not acquire its OS focus precondition: " + imeWindowState());
        }
        return false;
    }

    /** Exercise the real Minecraft text route and the active SDL game-window session together. */
    static void imeCommit(AtomChatScreen screen, String committedText) throws Exception {
        if (!screen.nativeMiuixTags().contains("miuix-font-noto-variable"))
            throw new AssertionError("Game classloader did not load the shared variable Noto font");
        verifyImeWindow(true);
        var composer = composer(screen);
        if (!composer.text().isEmpty()) throw new AssertionError("IME fixture requires an empty composer");
        var page = screen.topPage();
        var client = Minecraft.getInstance();
        long window = client.getWindow().handle();
        String preedit = "nihao";
        client.keyboardHandler.textEditing(window,
                new net.minecraft.client.input.PreeditEvent(preedit, 3, List.of(preedit), 0));
        for (int scancode : new int[]{
                com.mojang.blaze3d.platform.InputConstants.KEY_LEFT,
                com.mojang.blaze3d.platform.InputConstants.KEY_RIGHT,
                com.mojang.blaze3d.platform.InputConstants.KEY_UP,
                com.mojang.blaze3d.platform.InputConstants.KEY_DOWN,
                com.mojang.blaze3d.platform.InputConstants.KEY_BACKSPACE,
                com.mojang.blaze3d.platform.InputConstants.KEY_SPACE,
                com.mojang.blaze3d.platform.InputConstants.KEY_RETURN,
                com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE}) {
            var key = new net.minecraft.client.input.KeyEvent(scancode, 0, 0);
            screen.keyPressed(key);
            screen.keyReleased(key);
        }
        if (screen.topPage() != page || !composer.text().equals(preedit))
            throw new AssertionError("Native IME candidate keys sent, dismissed or changed the uncommitted composer");
        // Production clears preedit and emits every committed Unicode codepoint in this one call.
        client.keyboardHandler.textInput(window, committedText);
        imeCommittedText = committedText;
        if (!composer.text().equals(committedText))
            throw new AssertionError("Same-batch SDL commit duplicated or lost CJK/Unicode text: " + composer.text());
        verifyImeWindow(true);
    }

    static boolean imeCommitReady(AtomChatScreen screen) throws Exception {
        if (!screen.nativeMiuixTags().contains("miuix-navigation-settled")) return false;
        if (!composer(screen).text().equals(imeCommittedText))
            throw new AssertionError("Composer snapshot overwrote the committed IME text");
        verifyImeWindow(true);
        return true;
    }

    static void imeLeaveComposer(AtomChatScreen screen) {
        screen.switchRoot(AppPage.SETTINGS);
    }

    static boolean imeBlurReady(AtomChatScreen screen) {
        if (!screen.nativeMiuixTags().contains("miuix-navigation-settled")
                || screen.nativeMiuixBounds("settings-section-appearance") == null) return false;
        verifyImeWindow(false);
        return true;
    }

    static void imeReturnToComposer(AtomChatScreen screen) {
        screen.openWorldChat();
    }

    static boolean imeRefocusReady(AtomChatScreen screen) throws Exception {
        if (!screen.nativeMiuixTags().contains("miuix-navigation-settled")
                || screen.nativeMiuixBounds("chat-composer") == null) return false;
        verifyImeWindow(true);
        if (!composer(screen).text().equals(imeCommittedText))
            throw new AssertionError("Leaving and refocusing the native field lost the committed CJK draft");
        return true;
    }

    private static void verifyImeWindow(boolean expectedActive) {
        long window = Minecraft.getInstance().getWindow().handle();
        long focused = org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyboardFocus();
        if (focused != window && !(focused == 0 && imeOsFocusUnavailable))
            throw new AssertionError("SDL keyboard focus is outside the game: " + imeWindowState());
        boolean active = org.lwjgl.sdl.SDLKeyboard.SDL_TextInputActive(window);
        if (active != expectedActive)
            throw new AssertionError("Game SDL text input active=" + active + "; expected=" + expectedActive
                    + "; " + imeWindowState());
    }

    private static String imeWindowState() {
        long window = Minecraft.getInstance().getWindow().handle();
        long focused = org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyboardFocus();
        long glWindow = org.lwjgl.sdl.SDLVideo.SDL_GL_GetCurrentWindow();
        return "game={" + describeWindow(window) + "}, keyboardFocus={" + describeWindow(focused)
                + "}, currentGL={" + describeWindow(glWindow) + "}, gameTextInputActive="
                + org.lwjgl.sdl.SDLKeyboard.SDL_TextInputActive(window)
                + ", minecraftWindowActive=" + Minecraft.getInstance().isWindowActive()
                + ", carrier={" + imeCarrierState() + "}";
    }

    private static String imeCarrierState() {
        try {
            var client = Minecraft.getInstance();
            if (!(client.gui.screen() instanceof AtomChatScreen screen)) return "no AtomChat screen";
            var inputField = net.minecraft.client.gui.screens.ChatScreen.class.getDeclaredField("input");
            inputField.setAccessible(true);
            var input = (net.minecraft.client.gui.components.EditBox) inputField.get(screen);
            var hostField = AtomChatScreen.class.getDeclaredField("miuixHost");
            hostField.setAccessible(true);
            var host = hostField.get(screen);
            var textFocus = host.getClass().getDeclaredField("textFocus");
            textFocus.setAccessible(true);
            var bridgeField = AtomChatScreen.class.getDeclaredField("miuixBridge");
            bridgeField.setAccessible(true);
            var bridge = bridgeField.get(screen);
            boolean session = false;
            if (bridge != null) {
                var hasTextFocus = bridge.getClass().getDeclaredMethod("hasTextFocus");
                hasTextFocus.setAccessible(true);
                session = (boolean) hasTextFocus.invoke(bridge);
            }
            var manager = client.textInputManager();
            var owner = manager.getClass().getDeclaredField("owner");
            owner.setAccessible(true);
            var currentOwner = owner.get(manager);
            return "inputFocused=" + (input != null && input.isFocused())
                    + ", screenFocusIsInput=" + (screen.getFocused() == input)
                    + ", hostTextFocus=" + textFocus.getBoolean(host)
                    + ", composeSession=" + session
                    + ", managerOwnerIsInput=" + (currentOwner == input)
                    + ", managerOwner=" + (currentOwner == null ? "null" : currentOwner.getClass().getName());
        } catch (ReflectiveOperationException error) {
            return "diagnostics unavailable: " + error;
        }
    }

    private static String describeWindow(long window) {
        if (window == 0) return "pointer=0 (no SDL window owns OS keyboard focus)";
        return "pointer=0x" + Long.toHexString(window)
                + ", id=" + org.lwjgl.sdl.SDLVideo.SDL_GetWindowID(window)
                + ", title=" + org.lwjgl.sdl.SDLVideo.SDL_GetWindowTitle(window)
                + ", flags=0x" + Long.toHexString(org.lwjgl.sdl.SDLVideo.SDL_GetWindowFlags(window));
    }

    private static com.atom.chat.ui.ComposerInput composer(AtomChatScreen screen) throws Exception {
        var field = AtomChatScreen.class.getDeclaredField("composer");
        field.setAccessible(true);
        return (com.atom.chat.ui.ComposerInput) field.get(screen);
    }

    /** Caller must first verify that this is the automation-owned disposable world. */
    static void prepareCheckerFloor() {
        var client = Minecraft.getInstance();
        var server = client.getSingleplayerServer();
        if (server == null || client.level == null || client.player == null) throw new AssertionError("Checker fixture needs the isolated integrated world");
        floorCenter = client.player.blockPosition().below().immutable();
        var dimension = client.level.dimension();
        floorClientReady = 0;
        originalFloor.clear();
        floorUpdate = server.submit(() -> {
            var level = server.getLevel(dimension);
            if (level == null) throw new AssertionError("Checker fixture server dimension unavailable");
            for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) {
                var position = floorCenter.offset(x, 0, z).immutable();
                originalFloor.put(position, level.getBlockState(position));
                level.setBlock(position, checker(x, z).defaultBlockState(), 3);
            }
            return null;
        });
    }

    static boolean checkerFloorReady() {
        if (!floorUpdate.isDone()) return false;
        floorUpdate.join();
        var level = Minecraft.getInstance().level;
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) {
            if (!level.getBlockState(floorCenter.offset(x, 0, z)).is(checker(x, z))) {
                floorClientReady = 0;
                return false;
            }
        }
        if (floorClientReady == 0) floorClientReady = System.nanoTime();
        return System.nanoTime() - floorClientReady >= 1_500_000_000L;
    }

    static void restoreCheckerFloor() {
        var client = Minecraft.getInstance();
        var dimension = client.level.dimension();
        floorUpdate = client.getSingleplayerServer().submit(() -> {
            var level = client.getSingleplayerServer().getLevel(dimension);
            originalFloor.forEach((position, state) -> level.setBlock(position, state, 3));
            return null;
        });
    }

    static boolean checkerFloorRestored() {
        if (!floorUpdate.isDone()) return false;
        floorUpdate.join();
        var level = Minecraft.getInstance().level;
        return originalFloor.entrySet().stream().allMatch(entry -> level.getBlockState(entry.getKey()).equals(entry.getValue()));
    }

    private static net.minecraft.world.level.block.Block checker(int x, int z) {
        return ((x + z) & 1) == 0 ? net.minecraft.world.level.block.Blocks.CONCRETE.white() : net.minecraft.world.level.block.Blocks.CONCRETE.black();
    }

    static void baseline(String label, AtomChatScreen screen, Path output) throws Exception {
        Files.createDirectories(output);
        panel = screen;
        directory = output;
        name = label;
        frames.clear(); pending.clear(); failure = null;
        mode = "baseline";
        start = System.nanoTime(); lastCapture = 0;
    }

    static boolean baselineReady() {
        if (!"baseline-done".equals(mode) || !drained()) return false;
        verifyTopClock(frames.getLast().chromeBounds());
        return true;
    }

    /** Uses the production navigation API and host snapshot, never a synthetic Compose route. */
    static void about(AtomChatScreen screen, Path output) throws Exception {
        baseline("compose-about-atomchat-layout", screen, output);
        screen.switchRoot(AppPage.SETTINGS);
        screen.openSettingsSection(SettingsSection.ABOUT);
        mode = "about";
    }

    static boolean aboutReady() throws Exception {
        if (!"about-done".equals(mode) || !drained()) return false;
        if (panel.topPage() != AppPage.SETTINGS_SECTION) throw new AssertionError("About probe left the real settings section route");
        var geometry = frames.getLast().chromeBounds();
        float[] logo = requiredBounds(geometry, "about-logo");
        float[] appName = requiredBounds(geometry, "about-name");
        requiredBounds(geometry, "about-wordmark");
        float[] version = requiredBounds(geometry, "about-version");
        float[] topBar = requiredBounds(geometry, "miuix-top-app-bar");
        float dp = guiPixelsPerDp();
        if (Math.abs((logo[2] - logo[0]) / dp - 68.75F) > 1)
            throw new AssertionError("AtomChat About must retain the original 55-unit icon at its 1.25 UI scale");
        if (Math.abs((logo[0] + logo[2] - topBar[0] - topBar[2]) / (2 * dp)) > 1)
            throw new AssertionError("About logo must remain horizontally centered in the native panel");
        if (appName[1] < logo[3] || version[1] < appName[3])
            throw new AssertionError("About name/version no longer stack below the app logo");
        if (appName[2] - appName[0] <= logo[2] - logo[0])
            throw new AssertionError("About page must display the original wide AtomChat wordmark asset");
        String expectedVersion = com.atom.chat.AtomChat.version();
        if (panel.nativeMiuixTextBounds(expectedVersion) == null)
            throw new AssertionError("About page did not display loader version " + expectedVersion);
        if (!panel.nativeMiuixTags().contains("miuix-navigation-settled"))
            throw new AssertionError("About evidence was captured before native navigation settled");
        verifyTopClock(geometry);
        var measurements = new java.util.LinkedHashMap<String, Object>();
        measurements.put("page", panel.topPage().id());
        measurements.put("section", "about");
        measurements.put("expectedLoaderVersion", expectedVersion);
        measurements.put("frames", frames);
        measurements.put("nativeSceneTags", panel.nativeMiuixTags());
        measurements.put("guiPixelsPerDp", dp);
        measurements.put("layoutReference", "Original AtomChat brand icon, wordmark and unprefixed loader-version badge; author/group/action behavior is covered by AboutPageRuntimeTest");
        measurements.put("backgroundScope", "Layout, native scene and real metadata are asserted here; full dynamic-shader visual parity is outside these semantic checks");
        Files.writeString(directory.resolve(name + "-measurements.json"),
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(measurements));
        mode = null;
        return true;
    }

    static void navigation(boolean enabled) {
        animations = enabled;
        mode = "navigation";
        start = System.nanoTime(); lastCapture = 0;
    }

    static boolean navigationReady() throws Exception {
        if (!"navigation-done".equals(mode) || !drained()) return false;
        Files.writeString(directory.resolve(name + "-measurements.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(
                java.util.Map.of("animationsEnabled", animations, "frames", frames)));
        var first = read(frames.getFirst());
        var last = read(frames.getLast());
        if (different(first, last, null, 12) < 30) throw new AssertionError(name + ": navigation endpoint did not change actual content pixels");
        if (name.startsWith("compose-motion-root-")) verifyStationaryRootChrome();
        var motion = frames.stream().filter(Frame::running).toList();
        if (animations) {
            int distinct = 0;
            BufferedImage previous = first;
            boolean rootCrossfade = name.startsWith("compose-motion-root-");
            var evidence = new java.util.ArrayList<java.util.Map<String, Object>>();
            for (Frame frame : motion) {
                var image = read(frame);
                // A 160ms eased crossfade has genuine near-endpoint frames whose
                // changes are smaller than an absolute 12/255 threshold. Measure
                // their fractional movement only on pixels changed by the route.
                int intermediatePixels = rootCrossfade
                        ? endpointIntermediatePixels(first, last, image, previous)
                        : Math.min(different(first, image, null, 12), Math.min(
                                different(last, image, null, 12), different(previous, image, null, 12)));
                evidence.add(java.util.Map.of("millis", frame.millis(), "intermediatePixels", intermediatePixels));
                if (intermediatePixels >= 30) { distinct++; previous = image; }
            }
            Files.writeString(directory.resolve(name + "-measurements.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(
                    java.util.Map.of("animationsEnabled", animations, "frames", frames,
                            "pixelEvidenceMethod", rootCrossfade ? "endpoint-mask-24-relative-interior-and-distinct-2-percent-min-1" : "absolute-difference-12",
                            "intermediatePixelEvidence", evidence, "distinctIntermediateFrames", distinct)));
            if (distinct < 2) throw new AssertionError(name + ": fewer than two distinct GPU intermediate content frames: " + motion.size());
        } else {
            // Zero-duration transitions may keep their running semantics for a
            // bookkeeping frame after the new page pixels are already final.
            // Verify the rendered content, including captures without that tag.
            var evidence = new java.util.ArrayList<java.util.Map<String, Object>>();
            int maximumIntermediatePixels = 0;
            for (int i = 1; i < frames.size() - 1; i++) {
                Frame frame = frames.get(i);
                int intermediatePixels = endpointIntermediatePixels(first, last, read(frame), first);
                maximumIntermediatePixels = Math.max(maximumIntermediatePixels, intermediatePixels);
                evidence.add(java.util.Map.of("millis", frame.millis(), "runningMetadata", frame.running(),
                        "intermediatePixels", intermediatePixels));
            }
            Files.writeString(directory.resolve(name + "-measurements.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(
                    java.util.Map.of("animationsEnabled", false, "frames", frames,
                            "pixelEvidenceMethod", "endpoint-mask-24-relative-interior-2-percent-min-1",
                            "intermediatePixelEvidence", evidence)));
            if (maximumIntermediatePixels >= 30)
                throw new AssertionError(name + ": animation disabled but rendered actual intermediate content pixels: " + maximumIntermediatePixels);
        }
        mode = null;
        return true;
    }

    /** Call after baselineReady, then drive genuine wheel input while this probe settles. */
    static void topBarScroll() {
        topBarRestoring = false;
        topBarSourceFrameIndex = frames.size() - 1;
        mode = "topbar";
        start = System.nanoTime();
        lastCapture = 0;
    }

    static void topBarRestore() {
        topBarRestoring = true;
        topBarSourceFrameIndex = frames.size() - 1;
        mode = "topbar";
        start = System.nanoTime();
        lastCapture = 0;
    }

    static boolean topBarScrollReady() throws Exception {
        if (!"topbar-done".equals(mode) || !drained()) return false;
        var before = frames.getFirst();
        var after = frames.getLast();
        float[] expanded = before.chromeBounds().get("miuix-top-app-bar");
        float[] collapsed = after.chromeBounds().get("miuix-top-app-bar");
        verifyTopClock(after.chromeBounds());
        if (expanded == null || collapsed == null) throw new AssertionError("Top app bar has no measurable GPU-frame semantics");
        float expandedHeight = expanded[3] - expanded[1];
        float collapsedHeight = collapsed[3] - collapsed[1];
        var window = Minecraft.getInstance().getWindow();
        float scale = (float) window.getWidth() / window.getGuiScaledWidth();
        float[] roi = {expanded[0] * scale, expanded[1] * scale, expanded[2] * scale, expanded[3] * scale};
        int changedPixels = different(read(frames.get(topBarSourceFrameIndex)), read(after), roi, 8);
        Files.writeString(directory.resolve(name + (topBarRestoring ? "-restored" : "-collapsed") + "-measurements.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(
                java.util.Map.of("frames", frames, "expandedHeightGui", expandedHeight,
                        "collapsedHeightGui", collapsedHeight, "topBarChangedPixels", changedPixels,
                        "topBarPixelRoi", roi, "sourceFrameIndex", topBarSourceFrameIndex,
                        "destinationFrameIndex", frames.size() - 1, "pixelScope", "Top bar and large-title footprint, including progressive glass and moving title/content")));
        if (topBarRestoring) {
            if (Math.abs(collapsedHeight - expandedHeight) > 1) throw new AssertionError("Reverse wheel input did not restore the expanded top app bar");
        } else if (collapsedHeight >= expandedHeight - 10) throw new AssertionError("Real wheel input did not collapse the top app bar");
        if (changedPixels < 30) throw new AssertionError("Collapsing top app bar did not change rendered GPU pixels");
        mode = null;
        return true;
    }

    static void dynamicWorld(AtomChatScreen screen, Path output) throws Exception {
        baseline("compose-dynamic-world-floating", screen, output);
        mode = "world";
        pitch = Minecraft.getInstance().player.getXRot();
        yaw = Minecraft.getInstance().player.getYRot();
        worldElapsedMillis = 0;
        startingGpuCopies = PanelBlurRenderer.worldGpuFrames();
        startingReadbackFrames = PanelBlurRenderer.worldReadbackFrames();
        startingReadbackBytes = PanelBlurRenderer.worldReadbackBytes();
        bar = panel.nativeMiuixBounds("floating-navigation-bar");
        if (bar == null) throw new AssertionError("Floating capsule has no visible semantic bounds");
        verifyFloatingWidth(chromeBounds());
    }

    static boolean dynamicWorldReady() throws Exception {
        if ("world".equals(mode)) {
            long elapsed = elapsed();
            if (elapsed < 3000) return false;
            Minecraft.getInstance().player.setXRot(pitch);
            Minecraft.getInstance().player.setYRot(yaw);
            worldElapsedMillis = elapsed;
            endingGpuCopies = PanelBlurRenderer.worldGpuFrames();
            endingReadbackFrames = PanelBlurRenderer.worldReadbackFrames();
            endingReadbackBytes = PanelBlurRenderer.worldReadbackBytes();
            mode = "world-done";
        }
        if (!drained()) return false;
        var window = Minecraft.getInstance().getWindow();
        float scale = (float) window.getWidth() / window.getGuiScaledWidth();
        // The library's semantic layout also includes its bottom inset. Restrict
        // evidence to the painted pill interior, never moving world in that inset.
        float barWidth = bar[2] - bar[0], barHeight = bar[3] - bar[1];
        float[] capsule = {(bar[0] + barWidth * .08F) * scale, (bar[1] + barHeight * .08F) * scale,
                (bar[2] - barWidth * .08F) * scale, (bar[1] + barHeight * .48F) * scale};
        int worldChanges = 0, capsuleChanges = 0;
        long lastChange = -1, maximumGap = 0;
        for (int i = 1; i < frames.size(); i++) {
            var before = read(frames.get(i - 1)); var after = read(frames.get(i));
            float[] outside = {0, after.getHeight() * .2F, after.getWidth() * .08F, after.getHeight() * .8F};
            if (different(before, after, outside, 8) > 30) {
                worldChanges++;
            }
            if (different(before, after, capsule, 4) > 12) {
                capsuleChanges++;
                if (lastChange >= 0) maximumGap = Math.max(maximumGap, frames.get(i).millis - lastChange);
                lastChange = frames.get(i).millis;
            }
        }
        var measurements = new java.util.LinkedHashMap<String, Object>();
        measurements.put("frames", frames);
        measurements.put("worldOutsidePanelChanges", worldChanges);
        measurements.put("capsulePixelChanges", capsuleChanges);
        measurements.put("capsuleMaximumChangeGapMs", maximumGap);
        measurements.put("capsulePixelRoi", capsule);
        measurements.put("capsuleSemanticBoundsGui", bar);
        measurements.put("floatingWidthGeometry", verifyFloatingWidth(frames.getLast().chromeBounds()));
        measurements.put("capsuleRoiScope", "Inset pill interior in top48 percent of semantic bounds; excludes library bottom inset and rounded outer corners");
        measurements.put("phaseElapsedMillis", worldElapsedMillis);
        measurements.put("worldFixture", "17x17 black/white concrete checker at original player floor, client block receipt plus 1500ms chunk warmup");
        measurements.put("worldFixtureCenter", java.util.List.of(floorCenter.getX(), floorCenter.getY(), floorCenter.getZ()));
        measurements.put("cameraDrive", "Render-tail continuous yaw and sinusoidal pitch from System.nanoTime; PNG requests capped below 15Hz");
        measurements.put("sourceGpuCopyDelta", endingGpuCopies - startingGpuCopies);
        measurements.put("sourceReadbackFrameDelta", endingReadbackFrames - startingReadbackFrames);
        measurements.put("sourceReadbackByteDelta", endingReadbackBytes - startingReadbackBytes);
        measurements.put("sourceGpuCopiesPerSecond", (endingGpuCopies - startingGpuCopies) * 1000.0 / worldElapsedMillis);
        measurements.put("sourceReadbacksPerSecond", (endingReadbackFrames - startingReadbackFrames) * 1000.0 / worldElapsedMillis);
        measurements.put("sampledCompositorFramesPerSecond", frames.size() * 1000.0 / worldElapsedMillis);
        measurements.put("frequencyScope", "Source-copy/readback and screenshot sampling rates during this probe; these are not display FPS");
        Files.writeString(directory.resolve(name + "-measurements.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(measurements));
        if (worldChanges < 5) throw new AssertionError("Camera fixture did not visibly move the rendered world: " + worldChanges);
        if (capsuleChanges < 10 || maximumGap >= 450) throw new AssertionError("Floating capsule backdrop did not visibly refresh faster than the old 500ms gate: changes=" + capsuleChanges + ", maxGapMs=" + maximumGap);
        mode = null;
        return true;
    }

    static void afterRender() {
        if (mode == null || failure != null || Minecraft.getInstance().gui.screen() != panel) return;
        try {
            long elapsed = elapsed();
            if ("world".equals(mode)) {
                // This runs every rendered frame; it drives the next world view independently of PNG sampling.
                double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
                Minecraft.getInstance().player.setYRot(yaw + (float) (seconds * 170.0));
                Minecraft.getInstance().player.setXRot(35F + (float) (5.0 * Math.sin(seconds * Math.PI * 2.0)));
            }
            boolean running = panel.nativeMiuixTags().contains("miuix-navigation-running")
                    || panel.nativeMiuixTags().contains("miuix-root-tabs-running");
            boolean capture = "baseline".equals(mode)
                    || "about".equals(mode) && elapsed >= 700 && !running
                    && panel.nativeMiuixTags().contains("miuix-navigation-settled")
                    && panel.nativeMiuixBounds("about-version") != null
                    || "navigation".equals(mode) && (running && elapsed - lastCapture >= 16 && frames.size() < 9
                    || !running && elapsed >= 500 && panel.nativeMiuixTags().contains("miuix-navigation-settled"))
                    || "topbar".equals(mode) && elapsed >= 1800
                    || "world".equals(mode) && elapsed - lastCapture >= 70;
            if (!capture) return;
            lastCapture = elapsed;
            Path file = directory.resolve(name + "-" + frames.size() + ".png");
            frames.add(new Frame(file.toString(), elapsed, running, PanelBlurRenderer.worldGpuFrames(),
                    PanelBlurRenderer.worldReadbackFrames(), PanelBlurRenderer.worldReadbackBytes(), chromeBounds()));
            var done = new CompletableFuture<Void>(); pending.add(done);
            Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget(), 1, pixels -> {
                try (pixels) { pixels.writeToFile(file); done.complete(null); }
                catch (Throwable error) { done.completeExceptionally(error); }
            });
            if ("baseline".equals(mode)) mode = "baseline-done";
            else if ("about".equals(mode)) mode = "about-done";
            else if ("navigation".equals(mode) && !running) mode = "navigation-done";
            else if ("topbar".equals(mode)) mode = "topbar-done";
        } catch (Throwable error) { failure = error; }
    }

    /** Record actual semantic geometry from the same GPU frames used for pixel evidence. */
    private static java.util.Map<String, float[]> chromeBounds() {
        var bounds = new java.util.LinkedHashMap<String, float[]>();
        for (String tag : List.of("miuix-top-app-bar", "miuix-root-navigation-bar",
                "nav-chats", "nav-profile", "nav-settings", "floating-navigation-bar", "miuix-top-clock",
                "about-logo", "about-name", "about-wordmark", "about-version", "about-hero", "about-logo-spacer", "about-authors",
                "about-author-e33epus", "about-author-nowordz", "about-author-image-e33epus", "about-author-image-nowordz",
                "about-group-modinfo", "about-group-thirdparty", "about-group-advanced")) {
            float[] rectangle = panel.nativeMiuixBounds(tag);
            if (rectangle != null) bounds.put(tag, rectangle.clone());
        }
        return bounds;
    }

    private static float[] requiredBounds(java.util.Map<String, float[]> geometry, String tag) {
        float[] rectangle = geometry.get(tag);
        if (rectangle == null || rectangle.length != 4 || rectangle[2] <= rectangle[0] || rectangle[3] <= rectangle[1])
            throw new AssertionError("Native GPU scene has no visible semantic bounds for " + tag);
        return rectangle;
    }

    private static float guiPixelsPerDp() {
        var window = Minecraft.getInstance().getWindow();
        return ScreenMetrics.uiDensity(window) / (float) window.getGuiScale();
    }

    private static void verifyTopClock(java.util.Map<String, float[]> geometry) {
        float[] clock = requiredBounds(geometry, "miuix-top-clock");
        float[] top = requiredBounds(geometry, "miuix-top-app-bar");
        float dp = guiPixelsPerDp();
        if (clock[1] < top[1] - dp || clock[3] > top[1] + 21 * dp)
            throw new AssertionError("Clock must stay inside the top 20dp safe area in expanded and collapsed bars");
        if (Math.abs((clock[0] + clock[2] - top[0] - top[2]) / 2) > dp)
            throw new AssertionError("Clock must stay centered across the full panel width");
    }

    private static java.util.Map<String, Object> verifyFloatingWidth(java.util.Map<String, float[]> geometry) {
        float[] capsule = requiredBounds(geometry, "floating-navigation-bar");
        float[] root = requiredBounds(geometry, "miuix-root-navigation-bar");
        float[] top = requiredBounds(geometry, "miuix-top-app-bar");
        float dp = guiPixelsPerDp();
        float radius = Math.max(0, AtomChatConfig.get().cornerRadius);
        float leftInset = (capsule[0] - top[0]) / dp;
        float rightInset = (top[2] - capsule[2]) / dp;
        if (Math.abs(leftInset - radius - .75F) > 2 || Math.abs(rightInset - radius - .75F) > 2)
            throw new AssertionError("Floating capsule must follow the configured panel corner inset: radius="
                    + radius + "dp, left=" + leftInset + "dp, right=" + rightInset + "dp");
        if (Math.abs(capsule[0] - root[0]) > 2 * dp || Math.abs(capsule[2] - root[2]) > 2 * dp)
            throw new AssertionError("Floating capsule must fill its inset root width");
        return java.util.Map.of("panelCornerRadiusDp", radius, "leftInsetDp", leftInset,
                "rightInsetDp", rightInset, "capsuleBoundsGui", capsule, "rootBoundsGui", root,
                "toleranceDp", 2, "scope", "Width and horizontal corner alignment; upstream bottom inset is unchanged");
    }

    private static void verifyStationaryRootChrome() {
        var baseline = frames.getFirst().chromeBounds();
        // The native selected indicator and item labels animate inside these fixed bars.
        for (String tag : List.of("miuix-top-app-bar", "miuix-root-navigation-bar")) {
            float[] expected = baseline.get(tag);
            if (expected == null) throw new AssertionError(name + ": baseline root chrome missing " + tag);
            for (Frame frame : frames) {
                float[] actual = frame.chromeBounds().get(tag);
                if (actual == null) throw new AssertionError(name + ": root chrome disappeared: " + tag + " at " + frame.millis());
                for (int edge = 0; edge < 4; edge++) {
                    if (Math.abs(actual[edge] - expected[edge]) > .25F)
                        throw new AssertionError(name + ": root chrome moved: " + tag + " edge=" + edge + " at " + frame.millis()
                                + "ms expected=" + expected[edge] + " actual=" + actual[edge]);
                }
            }
        }
    }

    private static long elapsed() { return (System.nanoTime() - start) / 1_000_000; }
    private static boolean drained() {
        if (failure != null) throw new AssertionError("Miuix compositor capture failed", failure);
        if (pending.stream().anyMatch(f -> !f.isDone())) return false;
        pending.forEach(CompletableFuture::join);
        return true;
    }
    private static BufferedImage read(Frame frame) throws Exception { return javax.imageio.ImageIO.read(Path.of(frame.file).toFile()); }
    /** Crossfade evidence excludes stationary pixels, endpoint copies and repeated captures. */
    private static int endpointIntermediatePixels(BufferedImage first, BufferedImage last,
                                                  BufferedImage image, BufferedImage previous) {
        int count = 0;
        for (int y = first.getHeight() / 4; y < first.getHeight() * 3 / 4; y += 2) {
            for (int x = first.getWidth() / 4; x < first.getWidth() * 3 / 4; x += 2) {
                int start = first.getRGB(x, y), end = last.getRGB(x, y);
                int shift = 0, delta = 0;
                for (int channel = 16; channel >= 0; channel -= 8) {
                    int change = Math.abs((start >> channel & 255) - (end >> channel & 255));
                    if (change > delta) { delta = change; shift = channel; }
                }
                if (delta < 24) continue;
                int a = start >> shift & 255, b = end >> shift & 255;
                int value = image.getRGB(x, y) >> shift & 255;
                int prior = previous.getRGB(x, y) >> shift & 255;
                float margin = Math.max(1f, delta * .02f);
                if (value > Math.min(a, b) + margin && value < Math.max(a, b) - margin
                        && Math.abs(value - prior) > margin) count++;
            }
        }
        return count;
    }
    private static int different(BufferedImage a, BufferedImage b, float[] roi, int threshold) {
        int left = roi == null ? a.getWidth() / 4 : Math.max(0, (int) roi[0]);
        int top = roi == null ? a.getHeight() / 4 : Math.max(0, (int) roi[1]);
        int right = roi == null ? a.getWidth() * 3 / 4 : Math.min(a.getWidth(), (int) roi[2]);
        int bottom = roi == null ? a.getHeight() * 3 / 4 : Math.min(a.getHeight(), (int) roi[3]);
        int count = 0;
        for (int y = top; y < bottom; y += 2) for (int x = left; x < right; x += 2) {
            int p = a.getRGB(x, y), q = b.getRGB(x, y);
            if (Math.max(Math.abs((p >> 16 & 255) - (q >> 16 & 255)), Math.max(Math.abs((p >> 8 & 255) - (q >> 8 & 255)), Math.abs((p & 255) - (q & 255)))) > threshold) count++;
        }
        return count;
    }
}
