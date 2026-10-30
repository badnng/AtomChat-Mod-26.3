package com.atom.chat.automation;

import com.atom.chat.chat.ChatMessage;
import com.atom.chat.chat.ChatStore;
import com.atom.chat.chat.PlayerRef;
import com.atom.chat.chat.PrivateChatStore;
import com.atom.chat.config.AtomChatConfig;
import com.atom.chat.config.AtomChatServerConfig;
import com.atom.chat.config.ServerConfigScreen;
import com.atom.chat.image.ImageLoader;
import com.atom.chat.net.MediaCompanionClient;
import com.atom.chat.net.PackSyncClient;
import com.atom.chat.net.PackSyncServer;
import com.atom.chat.net.PokeCompanionClient;
import com.atom.chat.profile.ProfileField;
import com.atom.chat.profile.ProfileRecord;
import com.atom.chat.profile.ProfileSync;
import com.atom.chat.nav.PageTransitionDirector;
import com.atom.chat.reaction.ReactionClient;
import com.atom.chat.screen.AtomChatScreen;
import com.atom.chat.util.CacheDirs;
import com.atom.chat.ui.ComposerInput;
import com.atom.chat.ui.PanelOpenMotion;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import net.neoforged.neoforge.common.NeoForge;
import com.mojang.blaze3d.platform.InputConstants;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Two independent development clients; every received assertion comes from a real connection. */
public final class AtomChatPairAutomation {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<Step> STEPS = new ArrayList<>();
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();
    private static final long START = System.nanoTime();
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==");
    private static final AtomicInteger SOUNDS = new AtomicInteger();
    private static String role;
    private static String other;
    private static String token;
    private static Path control;
    private static Path report;
    private static Path fixture;
    private static PlayerRef target;
    private static AtomChatScreen panel;
    private static int index;
    private static long entered;
    private static boolean begun;
    private static volatile boolean finished;
    private static boolean markerWritten;
    private static UUID lastPoke;
    private static boolean pokeAnimationArmed;
    private static int soundBaseline;
    private static CompletableFuture<Boolean> serverReady;
    private static CompletableFuture<byte[]> downloaded;
    private static String remoteImage;
    private static String initialPackHash;
    private static long commandBaseline;
    private static final Map<String, Object> ARTIFACT = new LinkedHashMap<>();

    private AtomChatPairAutomation() { }

    public static void install() {
        if (!Boolean.getBoolean("atomchat.automation") || !INSTALLED.compareAndSet(false, true)) return;
        role = System.getProperty("atomchat.automation.pairRole", "");
        if (!role.equals("host") && !role.equals("peer")) throw new IllegalArgumentException("pairRole must be host or peer");
        other = host() ? "peer" : "host";
        control = Path.of(System.getProperty("atomchat.automation.controlDir")).toAbsolutePath().normalize();
        report = Path.of(System.getProperty("atomchat.automation.report")).toAbsolutePath().normalize();
        token = "pair-" + control.getFileName();
        buildSteps();
        NeoForge.EVENT_BUS.addListener(AtomChatPairAutomation::tick);
        NeoForge.EVENT_BUS.addListener((PlaySoundSourceEvent event) -> {
            var id = event.getSound().getIdentifier();
            if (id.getNamespace().equals("atomchat") && id.getPath().equals("notification")
                    && Math.abs(event.getSound().getPitch() - 1.25F) < 0.001F) SOUNDS.incrementAndGet();
        });
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(Long.getLong("atomchat.automation.timeoutSeconds", 420L) * 1000L); }
            catch (InterruptedException ignored) { return; }
            synchronized (RESULTS) {
                if (finished) return;
                result("process-watchdog", "FAIL", "Pair client global deadline exceeded");
                finish("FAIL");
            }
            Runtime.getRuntime().halt(2);
        }, "atomchat-pair-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        result("pair-installed", "PASS", "Role " + role + "; real Minecraft TCP and production companion payloads");
    }

    private static boolean host() { return role.equals("host"); }

    private static void buildSteps() {
        add("title-ready", 90, () -> { }, () -> Minecraft.getInstance().isGameLoadFinished());
        add("artifact-runtime-origin-and-nested-dependency-check", 10, AtomChatPairAutomation::verifyArtifact, () -> true);
        add("isolated-hidden-client", 5, () -> {
            var mc = Minecraft.getInstance();
            Path dir = mc.gameDirectory.toPath().toAbsolutePath().normalize();
            if (!dir.getFileName().toString().startsWith("automation")) throw new AssertionError("Unsafe gameDir: " + dir);
            if (!Boolean.getBoolean("atomchat.automation.windowCreatedHidden")) throw new AssertionError("Hidden-window mixin absent");
            mc.options.pauseOnLostFocus = false;
            mc.options.renderDistance().set(2);
            mc.options.simulationDistance().set(2);
            AtomChatConfig.get().notificationSoundEnabled = true;
            AtomChatConfig.get().notifyVolume = 0.01F;
            AtomChatConfig.get().serverPacksEnabled = true;
            AtomChatConfig.get().debug = true;
            AtomChatConfig.get().retainDraftOnClose = true;
            if (!AtomChatConfig.saveChecked(AtomChatConfig.get())) throw new AssertionError("Fixture client config save failed");
            fixture = dir.resolve("automation-pair.png");
            Files.write(fixture, PNG);
            Files.createDirectories(control);
            if (host()) {
                var config = AtomChatServerConfig.get();
                config.hostingEnabled = true;
                config.uploadCooldownMs = 0;
                config.packEnabled = true;
                config.packName = "AtomChat Pair Fixture";
                config.phrases = new ArrayList<>(List.of(token + "-phrase"));
                AtomChatServerConfig.save(config);
                Files.createDirectories(CacheDirs.serverEmotesDir());
                Files.write(CacheDirs.serverEmotesDir().resolve("pair.png"), PNG);
                PackSyncServer.invalidate();
            }
        }, () -> true);
        if (host()) {
            add("create-isolated-flat-world", 150, () -> Minecraft.getInstance().createWorldOpenFlows().createFreshLevel(
                    "automation-pair-world", new LevelSettings("automation-pair-world", GameType.CREATIVE,
                            new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT),
                    new WorldOptions(263L, false, false), WorldPresets::createTestWorldDimensions, new TitleScreen()),
                    AtomChatPairAutomation::hostWorldReady);
            add("publish-loopback-offline-development-server", 10, () -> {
                var server = Minecraft.getInstance().getSingleplayerServer();
                int port;
                try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
                if (!server.publishServer(MinecraftServer.MultiplayerScope.LAN, false, port)) throw new AssertionError("LAN publication failed");
                // publishServer binds wildcard. Replace its listener before allowing offline guests.
                server.getConnection().stopTcpServerListener();
                server.getConnection().startTcpServerListener(InetAddress.getByName("127.0.0.1"), port);
                server.setUsesAuthentication(false);
                server.setPreventProxyConnections(false);
                write(control.resolve("server.json"), Map.of("address", "127.0.0.1:" + port,
                        "uuid", Minecraft.getInstance().player.getUUID().toString(),
                        "name", Minecraft.getInstance().player.getGameProfile().name()));
            }, () -> true);
        } else {
            add("await-loopback-host", 220, () -> { }, () -> Files.isRegularFile(control.resolve("server.json")));
            add("connect-real-multiplayer-peer", 75, () -> {
                JsonObject server = JSON.fromJson(Files.readString(control.resolve("server.json")), JsonObject.class);
                String address = server.get("address").getAsString();
                if (!address.startsWith("127.0.0.1:")) throw new AssertionError("Non-loopback address refused");
                ConnectScreen.startConnecting(new TitleScreen(), Minecraft.getInstance(), ServerAddress.parseString(address),
                        new ServerData("AtomChat automation host", address, ServerData.Type.LAN), false, null);
            }, () -> Minecraft.getInstance().player != null && Minecraft.getInstance().level != null);
        }
        add("publish-client-identity", 5, () -> write(control.resolve(role + "-identity.json"), Map.of(
                "uuid", Minecraft.getInstance().player.getUUID().toString(), "name", Minecraft.getInstance().player.getGameProfile().name())), () -> true);
        add("two-distinct-real-online-players", 75, () -> { }, () -> {
            Path identity = control.resolve(other + "-identity.json");
            if (!Files.isRegularFile(identity)) return false;
            var data = JSON.fromJson(Files.readString(identity), JsonObject.class);
            target = PlayerRef.of(UUID.fromString(data.get("uuid").getAsString()), data.get("name").getAsString());
            if (target.uuid().equals(Minecraft.getInstance().player.getUUID())) throw new AssertionError("Clients share UUID");
            return Minecraft.getInstance().getConnection().getOnlinePlayers().stream().anyMatch(p -> p.getProfile().id().equals(target.uuid()));
        });
        add("open-production-panel", 60, () -> {
            panel = new AtomChatScreen("");
            Minecraft.getInstance().setScreenAndShow(panel);
            panel.openWorldChat();
        }, AtomChatPairAutomation::panelSettled);
        barrier("panels-ready");
        add("bidirectional-production-public-chat", 20, () -> send(token + "-public-" + role), () ->
                publicMessage(role) != null && publicMessage(other) != null);
        barrier("public-received");
        add("bidirectional-production-private-chat", 20, () -> {
            panel.openPrivateChat(target);
            send(token + "-private-" + role);
        }, () -> PrivateChatStore.messages(target).stream().anyMatch(m -> m.isOwn() && m.getRawText().contains(token + "-private-" + role))
                && PrivateChatStore.messages(target).stream().anyMatch(m -> !m.isOwn() && m.getRawText().contains(token + "-private-" + other)));
        add("private-does-not-leak-into-public-feed", 5, () -> { }, () -> {
            if (ChatStore.get().snapshot().stream().anyMatch(m -> m.getRawText().contains(token + "-private-"))) {
                throw new AssertionError("Private payload leaked into public ChatStore");
            }
            return true;
        });
        barrier("private-received");
        add("private-draft-page-settled", 60, () -> panel.openPrivateChat(target), AtomChatPairAutomation::panelSettled);
        add("private-draft-real-Escape-close", 45, () -> {
            composer().setText(token + "-sensitive-private-draft-" + role);
            composer().focus();
            // Physical SDL escape is 41; its logical keycode is ASCII ESC.
            panel.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 27, 0));
        }, () -> Minecraft.getInstance().gui.screen() == null);
        add("private-draft-native-store-isolation-and-real-T-entry", 60, () -> {
            var mc = Minecraft.getInstance();
            var nativeDraft = (ChatComponent.Draft) field(mc.gui.hud.getChat(), "latestDraft");
            if (nativeDraft != null && nativeDraft.text().contains("sensitive-private-draft")) {
                throw new AssertionError("Private draft leaked into vanilla public draft store");
            }
            mc.gui.openChatScreen(ChatComponent.ChatMethod.MESSAGE);
        }, () -> {
            if (!(Minecraft.getInstance().gui.screen() instanceof AtomChatScreen current)) return false;
            panel = current;
            if (composer().text().contains("sensitive-private-draft")) throw new AssertionError("T public composer exposed private draft");
            return panelSettled();
        });
        add("private-draft-survives-Escape-T-public-open", 5, () -> panel.openPrivateChat(target), () -> {
            if (!composer().text().equals(token + "-sensitive-private-draft-" + role)) {
                throw new AssertionError("Retained private draft was not restored to its own conversation");
            }
            composer().setText("");
            return true;
        });
        add("restored-native-draft-single-Backspace", 60, () -> {
            panel = new AtomChatScreen(token + "-editable", true, AtomChatScreen.AtomChatOpenMode.DIRECT_WORLD);
            Minecraft.getInstance().setScreenAndShow(panel);
        }, () -> {
            if (!panelSettled()) return false;
            composer().focus();
            composer().caretToEnd();
            String before = composer().text();
            if (!before.equals(token + "-editable")) throw new AssertionError("Restored fixture text absent: " + before);
            panel.keyPressed(new KeyEvent(InputConstants.KEY_BACKSPACE, InputConstants.KEYCODE_BACKSPACE, 0));
            if (!composer().text().equals(before.substring(0, before.length() - 1))) {
                throw new AssertionError("Backspace erased more than one character: " + composer().text());
            }
            composer().setText("");
            return true;
        });
        barrier("draft-privacy-regressions-observed");
        reactionStep("reaction-host-add-broadcast", "host", 1);
        reactionStep("reaction-peer-add-broadcast", "peer", 2);
        reactionStep("reaction-host-remove-broadcast", "host", 1);
        reactionStep("reaction-peer-remove-broadcast", "peer", 0);
        add("poke-install-production-route-observer", 60, () -> {
            panel.openWorldChat();
            Field slot = PokeCompanionClient.class.getDeclaredField("onPoked");
            slot.setAccessible(true);
            @SuppressWarnings("unchecked") Consumer<UUID> original = (Consumer<UUID>) slot.get(null);
            if (original == null) throw new AssertionError("Screen did not install production poke handler");
            PokeCompanionClient.setHandler(from -> {
                original.accept(from);
                lastPoke = from;
                try {
                    Object view = field(panel, "messageListView");
                    pokeAnimationArmed = from.equals(field(view, "pokeSenderUuid")) && (long) field(view, "pokeStartTime") > 0L;
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
            soundBaseline = SOUNDS.get();
        }, AtomChatPairAutomation::panelSettled);
        barrier("poke-observers-ready");
        add("bidirectional-real-poke-payload-audio-and-animation-state", 15, () -> {
            if (!PokeCompanionClient.send(target.uuid())) throw new AssertionError("Poke channel unavailable");
        }, () -> target.uuid().equals(lastPoke) && pokeAnimationArmed && SOUNDS.get() > soundBaseline);
        barrier("poke-received");
        add("profile-companion-ready", 15, () -> { }, () -> ProfileSync.available() && MediaCompanionClient.hostingStateKnown()
                && MediaCompanionClient.hostingEnabled() && !ProfileSync.syncing());
        add("bidirectional-profile-signature-broadcast", 20, () -> ProfileSync.signatureEdited(token + "-signature-" + role), () ->
                recordOwn().signature().equals(token + "-signature-" + role)
                        && ProfileSync.record(target.uuid()).signature().equals(token + "-signature-" + other));
        barrier("signatures-received");
        for (ProfileField imageField : List.of(ProfileField.AVATAR, ProfileField.BANNER)) {
            String name = imageField.name().toLowerCase(java.util.Locale.ROOT);
            add("bidirectional-profile-" + name + "-publication-and-invalidation", 30,
                    () -> ProfileSync.imageEdited(imageField, fixture), () -> {
                        if (ProfileSync.unsynced()) throw new AssertionError("Profile image unsynced");
                        String own = image(recordOwn(), imageField);
                        remoteImage = image(ProfileSync.record(target.uuid()), imageField);
                        return !ProfileSync.syncing() && own.startsWith("atomchat-media:") && remoteImage.startsWith("atomchat-media:");
                    });
            add("remote-" + name + "-production-image-loader-pixels", 20, () -> { }, () -> {
                var loaded = ProfileSync.image(target.uuid(), imageField);
                if (loaded == null) return false;
                var pending = (Map<?, ?>) field(ImageLoader.get(), "pending");
                if (pending.containsKey(remoteImage)) return false;
                try (var expected = ImageLoader.decodeStatic(PNG, 1)) {
                    if (loaded.getWidth() != expected.getWidth() || loaded.getHeight() != expected.getHeight()
                            || firstPixel(loaded) != firstPixel(expected)) {
                        throw new AssertionError("Remote profile decoded pixels differ from fixture");
                    }
                }
                return true;
            });
            add("remote-" + name + "-bytes-real-companion-download", 20, () -> {
                final String url = remoteImage;
                downloaded = CompletableFuture.supplyAsync(() -> {
                    try {
                        Method fetch = MediaCompanionClient.class.getDeclaredMethod("fetchBlocking", String.class);
                        fetch.setAccessible(true);
                        return (byte[]) fetch.invoke(null, url);
                    } catch (Exception error) { throw new IllegalStateException(error); }
                });
            }, () -> {
                if (!downloaded.isDone()) return false;
                if (!Arrays.equals(PNG, downloaded.join())) throw new AssertionError("Remote profile bytes changed");
                return true;
            });
            barrier(name + "-downloaded");
            add("bidirectional-profile-" + name + "-clear-broadcast", 20, () -> ProfileSync.imageCleared(imageField), () ->
                    !ProfileSync.syncing() && image(recordOwn(), imageField).isEmpty()
                            && image(ProfileSync.record(target.uuid()), imageField).isEmpty());
            barrier(name + "-cleared");
        }
        add("server-emote-initial-join-handshake-completed", 30, () -> { }, () -> !packSyncActive() && PackSyncClient.installed() != null);
        add("server-emote-manifest-chunks-hash-and-phrase-sync", 30, PackSyncClient::resync, () -> {
            if (packSyncActive()) return false;
            var pack = PackSyncClient.installed();
            if (pack == null || !pack.serverName().equals("AtomChat Pair Fixture") || !pack.phrases().contains(token + "-phrase")) return false;
            if (pack.files().stream().noneMatch(f -> f.name().equals("pair.png"))) return false;
            Path file = CacheDirs.packDir(PackSyncClient.currentKey()).resolve("emotes/pair.png");
            if (!Files.isRegularFile(file) || !Arrays.equals(PNG, Files.readAllBytes(file))) return false;
            initialPackHash = pack.packHash();
            return true;
        });
        barrier("pack-downloaded");
        add("update-server-pack-fixture", 5, () -> {
            if (host()) {
                AtomChatServerConfig.get().phrases.add(token + "-updated");
                AtomChatServerConfig.save(AtomChatServerConfig.get());
                Files.write(CacheDirs.serverEmotesDir().resolve("pair-update.png"), PNG);
                PackSyncServer.invalidate();
            }
        }, () -> true);
        barrier("pack-updated");
        add("server-emote-incremental-refresh-real-network", 30, PackSyncClient::resync, () -> {
            if (packSyncActive()) return false;
            var pack = PackSyncClient.installed();
            if (pack == null || pack.packHash().equals(initialPackHash) || !pack.phrases().contains(token + "-updated")
                    || pack.files().stream().noneMatch(f -> f.name().equals("pair-update.png"))) return false;
            Path file = CacheDirs.packDir(PackSyncClient.currentKey()).resolve("emotes/pair-update.png");
            return Files.isRegularFile(file) && Arrays.equals(PNG, Files.readAllBytes(file));
        });
        barrier("pack-incremental-completed");
        add("profile-server-role-host-operator-peer-unprivileged", 15, () -> { }, () -> {
            Boolean operator = ProfileSync.operator(Minecraft.getInstance().player.getUUID());
            if (operator == null) {
                ProfileSync.record(Minecraft.getInstance().player.getUUID());
                return false;
            }
            if (operator != host()) throw new AssertionError("Expected " + role + " operator=" + host() + ", got " + operator);
            return true;
        });
        add(host() ? "host-real-config-command-opens-production-screen" : "guest-real-config-command-denied", 15, () -> {
            commandBaseline = ChatStore.get().snapshot().stream().mapToLong(ChatMessage::getId).max().orElse(0L);
            Minecraft.getInstance().getConnection().sendCommand("atomchat gui");
        }, () -> {
            boolean open = Minecraft.getInstance().gui.screen() instanceof ServerConfigScreen;
            if (host()) return open;
            if (open) throw new AssertionError("Unprivileged LAN guest opened server configuration");
            return ChatStore.get().snapshot().stream().anyMatch(m -> m.getId() > commandBaseline &&
                    (hasTranslation(m.getComponent(), "command.unknown.argument")
                            || hasTranslation(m.getComponent(), "command.unknown.command")
                            || hasTranslation(m.getComponent(), "commands.generic.permission")));
        });
        add("close-production-config-screen", 10, () -> {
            if (host()) Minecraft.getInstance().gui.screen().onClose();
        }, () -> !(Minecraft.getInstance().gui.screen() instanceof ServerConfigScreen));
        barrier("all-network-assertions-complete");
        if (!host()) add("peer-real-disconnect", 20, () -> Minecraft.getInstance().disconnectWithSavingScreen(),
                () -> Minecraft.getInstance().player == null && Minecraft.getInstance().level == null);
        else {
            add("host-observes-peer-disconnect", 30, () -> { }, () -> Minecraft.getInstance().getConnection().getOnlinePlayers().size() == 1);
            add("host-save-and-disconnect", 30, () -> Minecraft.getInstance().disconnectWithSavingScreen(),
                    () -> Minecraft.getInstance().player == null && Minecraft.getInstance().level == null);
        }
    }

    private static String image(ProfileRecord record, ProfileField which) { return which == ProfileField.AVATAR ? record.avatar() : record.banner(); }
    private static ComposerInput composer() throws Exception { return (ComposerInput) field(panel, "composer"); }
    private static boolean panelSettled() throws Exception {
        var director = (PageTransitionDirector) field(panel, "director");
        return PanelOpenMotion.settled((float) field(panel, "panelProgress"), (boolean) field(panel, "closing"))
                && !director.pageNavActive() && !director.rootTransitionActive();
    }
    private static boolean packSyncActive() throws Exception {
        Field field = PackSyncClient.class.getDeclaredField("active");
        field.setAccessible(true);
        return field.getBoolean(null);
    }
    private static int firstPixel(io.github.humbleui.skija.Image image) {
        try (var bitmap = new io.github.humbleui.skija.Bitmap()) {
            bitmap.allocN32Pixels(image.getWidth(), image.getHeight());
            if (!image.readPixels(bitmap)) throw new AssertionError("Remote profile pixel read failed");
            return bitmap.getColor(0, 0);
        }
    }
    private static boolean hasTranslation(Component text, String key) {
        if (text.getContents() instanceof TranslatableContents translated) {
            if (translated.getKey().equals(key)) return true;
            for (Object argument : translated.getArgs()) {
                if (argument instanceof Component nested && hasTranslation(nested, key)) return true;
            }
        }
        return text.getSiblings().stream().anyMatch(sibling -> hasTranslation(sibling, key));
    }
    private static void verifyArtifact() throws Exception {
        String mode = System.getProperty("atomchat.automation.artifactMode", "development-classes");
        ARTIFACT.put("artifactMode", mode);
        Path loaded = net.neoforged.fml.ModList.get().getModFileById("atomchat").getFile().getFilePath();
        ARTIFACT.put("loadedModPath", loaded.toString());
        if (!mode.equals("published-jar")) return;
        Path manifest = Path.of(System.getProperty("atomchat.automation.artifactManifest"));
        JsonObject evidence = JSON.fromJson(Files.readString(manifest), JsonObject.class);
        Path expected = Path.of(evidence.get("modJar").getAsString());
        if (!Files.isSameFile(loaded, expected)) throw new AssertionError("FML loaded " + loaded + " instead of release JAR " + expected);
        String actualHash = sha256(loaded);
        if (!actualHash.equals(evidence.get("sha256").getAsString())) throw new AssertionError("Loaded release JAR hash changed");
        ARTIFACT.put("sha256", actualHash);
        ARTIFACT.put("nestedDependencies", evidence.getAsJsonObject("nestedDependencies"));
        Map<String, String> origins = new LinkedHashMap<>();
        ARTIFACT.put("runtimeDependencyOrigins", origins);
        JsonObject dependencies = evidence.getAsJsonObject("nestedDependencies");
        for (String className : List.of("io.github.humbleui.skija.Image", "io.github.humbleui.types.Rect", "com.atom.chat.shaded.flatlaf.FlatLaf")) {
            Class<?> type = Class.forName(className, false, AtomChatPairAutomation.class.getClassLoader());
            Path origin = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            String filename = origin.getFileName().toString();
            if (!dependencies.has(filename) || !sha256(origin).equals(dependencies.get(filename).getAsString())) {
                throw new AssertionError("Dependency did not load from packaged JarJar bytes: " + className + " -> " + origin);
            }
            origins.put(className, origin.toString());
        }
        try {
            Class.forName("com.formdev.flatlaf.FlatLaf", false, AtomChatPairAutomation.class.getClassLoader());
            throw new AssertionError("Published mode exposed the unrelocated public FlatLaf package");
        } catch (ClassNotFoundException expectedHiddenPackage) {
            ARTIFACT.put("unrelocatedFlatLafHidden", true);
        }
    }
    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private static ProfileRecord recordOwn() { return ProfileSync.record(Minecraft.getInstance().player.getUUID()); }
    private static ChatMessage publicMessage(String sender) {
        return ChatStore.get().snapshot().stream().filter(m -> !m.isSystem()
                && m.getRawText().contains(token + "-public-" + sender)).findFirst().orElse(null);
    }
    private static void send(String text) throws Exception {
        Method method = AtomChatScreen.class.getDeclaredMethod("sendMessage", String.class);
        method.setAccessible(true);
        method.invoke(panel, text);
    }
    private static Object field(Object instance, String name) throws Exception {
        Field f = instance.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(instance);
    }
    private static void reactionStep(String name, String sender, int expected) {
        add(name, 20, () -> {
            if (!ReactionClient.enabled()) throw new AssertionError("Reaction channel unavailable");
            if (role.equals(sender)) ReactionClient.toggle(publicMessage("host"), "👍");
        }, () -> {
            var entries = ReactionClient.entries(publicMessage("host"));
            int count = entries.stream().filter(e -> e.emoji().equals("👍")).mapToInt(e -> e.members().size()).sum();
            if (count != expected) return false;
            if (expected == 2 && entries.stream().filter(e -> e.emoji().equals("👍")).noneMatch(ReactionClient::own)) {
                throw new AssertionError("Reaction membership lost local player");
            }
            return true;
        });
        barrier(name + "-observed");
    }
    private static void barrier(String name) {
        add("barrier-" + name, 45, () -> markerWritten = false, () -> {
            if (!markerWritten) {
                write(control.resolve(role + "-" + name + ".json"), Map.of("role", role, "at", Instant.now().toString()));
                markerWritten = true;
            }
            return Files.isRegularFile(control.resolve(other + "-" + name + ".json"));
        });
    }
    private static boolean hostWorldReady() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return false;
        if (serverReady == null || serverReady.isDone() && !serverReady.join()) {
            UUID id = mc.player.getUUID();
            var server = mc.getSingleplayerServer();
            serverReady = server.submit(() -> server.getPlayerList().getPlayer(id) != null);
        }
        return serverReady.getNow(false);
    }
    private static void tick(ClientTickEvent.Post event) {
        if (finished) return;
        if (index == STEPS.size()) {
            result("automatic-server-pack-push", "SKIP", "Production protocol offers join/resync pull; no unsolicited pack broadcast exists");
            result("poke-rendered-motion-and-perceived-sound", "SKIP", "Real payload, production wobble state and audio channel observed; no pixel trajectory or human hearing oracle");
            result("OS-native-and-external-host-interactions", "SKIP", "Pair harness covers controlled local network only");
            finish("PARTIAL");
            Minecraft.getInstance().stop();
            return;
        }
        Step step = STEPS.get(index);
        try {
            if (!begun) {
                begun = true;
                entered = System.nanoTime();
                System.out.println("[AtomChat pair " + role + "] START " + step.name);
                writeReport("RUNNING");
                step.start.run();
            }
            Path failure = control.resolve(other + "-failed.json");
            if (Files.isRegularFile(failure)) throw new AssertionError("Other client failed: " + Files.readString(failure));
            if (step.ready.get()) {
                result(step.name, "PASS", "Condition satisfied after " + ageMs() + " ms");
                index++;
                begun = false;
            } else if (ageMs() > step.seconds * 1000L) {
                String detail = "Timed out after " + step.seconds + "s";
                if (step.name.contains("server-emote")) {
                    var pack = PackSyncClient.installed();
                    detail += "; packActive=" + packSyncActive() + "; detail=" + PackSyncClient.detail()
                            + "; installed=" + (pack == null ? "null" : pack.packHash() + " " + pack.files());
                }
                throw new AssertionError(detail);
            }
        } catch (Throwable error) {
            error.printStackTrace();
            result(step.name, "FAIL", error.toString());
            finish("FAIL");
            Minecraft.getInstance().stop();
        }
    }
    private static long ageMs() { return (System.nanoTime() - entered) / 1_000_000L; }
    private static void add(String name, int seconds, Action start, Check ready) { STEPS.add(new Step(name, seconds, start, ready)); }
    private static void result(String name, String status, String detail) {
        synchronized (RESULTS) {
            RESULTS.add(Map.of("name", name, "status", status, "detail", detail, "elapsedMs", (System.nanoTime() - START) / 1_000_000L));
            System.out.println("[AtomChat pair " + role + "] " + status + " " + name + ": " + detail);
            writeReport("RUNNING");
        }
    }
    private static void finish(String status) {
        synchronized (RESULTS) {
            finished = true;
            writeReport(status);
            if (status.equals("FAIL")) {
                try { write(control.resolve(role + "-failed.json"), Map.of("role", role, "step", STEPS.get(index).name)); }
                catch (Exception error) { error.printStackTrace(); }
            }
        }
    }
    private static void writeReport(String status) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("schema", 1);
            data.put("minecraft", "26.3");
            data.put("role", role);
            data.put("status", status);
            data.put("updatedAt", Instant.now().toString());
            data.put("activeStep", index < STEPS.size() ? STEPS.get(index).name : "complete");
            data.put("artifact", ARTIFACT);
            data.put("checks", RESULTS);
            write(report, data);
        } catch (Exception error) { System.err.println("Pair report failed: " + error); }
    }
    private static void write(Path path, Object value) throws Exception {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, JSON.toJson(value));
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
    }
    private record Step(String name, int seconds, Action start, Check ready) { }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    @FunctionalInterface private interface Check { boolean get() throws Exception; }
}
