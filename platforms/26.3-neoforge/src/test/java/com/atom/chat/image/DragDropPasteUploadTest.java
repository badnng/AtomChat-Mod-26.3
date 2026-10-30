package com.atom.chat.image;

import com.atom.chat.platform.Platform;
import com.atom.chat.ui.ActionToast;
import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.atom.chat.screen.AtomChatScreen;
import java.lang.reflect.Field;

import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SDL screen-drop delivery through the actual AtomChatScreen override and D12 uploader.
 * The screen is allocated without running its game/Skia constructor; only its drop-domain
 * field is installed. The exercised onFilesDrop method is production code, not a stub.
 * Native OS delivery itself remains an in-game check. Clipboard decoding is injected
 * while the real worker thread, image validation and CICode append path are retained.
 */
class DragDropPasteUploadTest {
    private static final ActionToast.Translator KEYS = key -> key;

    @TempDir
    static Path platformRoot;

    /**
     * D12's config read goes through {@link Platform} for its paths — the same
     * temporary provider the offscreen render tests install, so this class never
     * depends on the machine's real config nor on another class installing first.
     */
    @BeforeAll
    static void installTemporaryPlatform() {
        Platform.install(new Platform.Provider() {
            @Override
            public Path configDir() {
                return platformRoot.resolve("config");
            }

            @Override
            public Path gameDir() {
                return platformRoot.resolve("game");
            }

            @Override
            public boolean isModLoaded(String modId) {
                return false;
            }
        });
    }

    @TempDir
    Path tmp;

    /** The D12 side of the wiring: the codes it appended, and a signal for the worker thread. */
    private static final class Uploads {
        final List<String> codes = new CopyOnWriteArrayList<>();
        final CountDownLatch landed = new CountDownLatch(1);
    }

    /** D12, with its render hop inline and its external uploader answering a fixed url. */
    private static ImageUploadPipeline uploadsInto(Uploads sink) {
        ImageUploadPipeline.Host host = new ImageUploadPipeline.Host() {
            @Override
            public Minecraft client() {
                throw new AssertionError("D12 hops through its own seam here");
            }

            @Override
            public boolean composerEmpty() {
                return true;
            }

            @Override
            public void appendToComposer(String text) {
                sink.codes.add(text);
                sink.landed.countDown();
            }

            @Override
            public void toast(String key, boolean success) {
            }

            @Override
            public Path pickSavePath(String suggestedName) {
                throw new AssertionError("D13 never saves an image");
            }
        };
        ImageUploadPipeline.Uploader uploader =
                (file, onSuccess, onError) -> onSuccess.accept("https://uguu.se/" + file.getFileName());
        return new ImageUploadPipeline(host, KEYS, uploader, Runnable::run);
    }

    private static final class StubHost implements DragDropPasteUpload.Host {
        final ImageUploadPipeline uploads;
        boolean worldChatPage = true;

        StubHost(ImageUploadPipeline uploads) {
            this.uploads = uploads;
        }

        @Override
        public Minecraft client() {
            throw new AssertionError("SDL delivery must never query the native window");
        }

        @Override
        public boolean worldChatPage() {
            return worldChatPage;
        }

        @Override
        public ImageUploadPipeline uploads() {
            return uploads;
        }
    }

    /** Allocate only the shell and inject the production drop domain, without a game window. */
    private static AtomChatScreen screenFor(DragDropPasteUpload upload) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeType.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        AtomChatScreen screen = (AtomChatScreen) unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, AtomChatScreen.class);
        Field wiring = AtomChatScreen.class.getDeclaredField("dragDropPaste");
        wiring.setAccessible(true);
        wiring.set(screen, upload);
        return screen;
    }

    private static DragDropPasteUpload activeUpload(StubHost host) {
        DragDropPasteUpload upload = new DragDropPasteUpload(host, flavor -> null);
        upload.installDropCallback();
        return upload;
    }

    private Path file(String name) throws Exception {
        Path path = tmp.resolve(name);
        if (name.endsWith(".png")) {
            ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", path.toFile());
            return path;
        }
        return Files.createFile(path);
    }

    @Test
    void screenOnFilesDropReachesTheProductionUploaderAfterResizeReentry() throws Exception {
        Uploads sink = new Uploads();
        DragDropPasteUpload upload = activeUpload(new StubHost(uploadsInto(sink)));
        AtomChatScreen screen = screenFor(upload);
        upload.installDropCallback();
        upload.installDropCallback();

        screen.onFilesDrop(List.of(file("screen-drop.png")));

        assertEquals(1, sink.codes.size(), "resize re-entry must not duplicate a delivered drop");
        assertTrue(sink.codes.get(0).contains("name=screen-drop.png"), sink.codes.toString());
    }

    @Test
    void screenDropsBeforeActivationAreIgnored() throws Exception {
        Uploads sink = new Uploads();
        DragDropPasteUpload upload = new DragDropPasteUpload(new StubHost(uploadsInto(sink)));

        screenFor(upload).onFilesDrop(List.of(file("inactive.png")));

        assertTrue(sink.codes.isEmpty());
    }

    @Test
    void removalStopsScreenDropsAndRepeatedRemovalIsHarmless() throws Exception {
        Uploads sink = new Uploads();
        DragDropPasteUpload upload = activeUpload(new StubHost(uploadsInto(sink)));
        AtomChatScreen screen = screenFor(upload);
        upload.uninstallDropCallback();
        upload.uninstallDropCallback();

        screen.onFilesDrop(List.of(file("after-close.png")));

        assertTrue(sink.codes.isEmpty(), "a removed screen must not upload a late drop");
    }

    @Test
    void removalBeforeActivationDoesNotPreventLaterScreenDrops() throws Exception {
        Uploads sink = new Uploads();
        DragDropPasteUpload upload = new DragDropPasteUpload(new StubHost(uploadsInto(sink)));
        upload.uninstallDropCallback();
        upload.installDropCallback();

        screenFor(upload).onFilesDrop(List.of(file("activated.png")));

        assertEquals(1, sink.codes.size());
    }

    @Test
    void reactivationAfterRemovalReceivesExactlyOneDrop() throws Exception {
        Uploads sink = new Uploads();
        DragDropPasteUpload upload = activeUpload(new StubHost(uploadsInto(sink)));
        AtomChatScreen screen = screenFor(upload);
        upload.uninstallDropCallback();
        screen.onFilesDrop(List.of(file("closed.png")));
        upload.installDropCallback();
        screen.onFilesDrop(List.of(file("reopened.png")));

        assertEquals(1, sink.codes.size());
        assertTrue(sink.codes.get(0).contains("name=reopened.png"), sink.codes.toString());
    }

    @Test
    void anInvalidDropDoesNotBreakTheNextDeliveredDrop() throws Exception {
        Uploads sink = new Uploads();
        DragDropPasteUpload upload = activeUpload(new StubHost(uploadsInto(sink)));
        AtomChatScreen screen = screenFor(upload);

        Path invalid = tmp.resolve("invalid.png");
        Files.writeString(invalid, "not an image");
        screen.onFilesDrop(List.of(invalid));
        screen.onFilesDrop(List.of(file("valid.png")));

        assertEquals(1, sink.codes.size());
        assertTrue(sink.codes.get(0).contains("name=valid.png"), sink.codes.toString());
    }

    @Test
    void aDropOnAnyOtherPageIsIgnored() throws Exception {
        Uploads sink = new Uploads();
        StubHost host = new StubHost(uploadsInto(sink));
        host.worldChatPage = false;
        DragDropPasteUpload upload = activeUpload(host);

        screenFor(upload).onFilesDrop(List.of(file("dropped.png")));

        assertTrue(sink.codes.isEmpty(), "only the composer pages take a drop");
    }

    @Test
    void theFirstImageIsUploadedAndTheRestOfTheDropIsIgnored() throws Exception {
        Uploads sink = new Uploads();
        DragDropPasteUpload upload =
                activeUpload(new StubHost(uploadsInto(sink)));
        Path first = file("first.png");

        screenFor(upload).onFilesDrop(List.of(first, file("second.png"), file("third.png")));

        assertEquals(1, sink.codes.size(), "a multi-file drop appends one code: " + sink.codes);
        assertTrue(sink.codes.get(0).contains("name=first.png"), sink.codes.toString());
        assertFalse(sink.codes.get(0).contains("second"), sink.codes.toString());
    }

    @Test
    void aDropWithoutAnImageUploadsNothing() throws Exception {
        Uploads sink = new Uploads();
        DragDropPasteUpload upload =
                activeUpload(new StubHost(uploadsInto(sink)));

        screenFor(upload).onFilesDrop(List.of(file("notes.txt"), file("archive.zip")));

        assertTrue(sink.codes.isEmpty());
    }

    @Test
    void pasteUploadsWhatTheClipboardYields() throws Exception {
        Uploads sink = new Uploads();
        Path pasted = file("pasted.png");
        DragDropPasteUpload upload =
                new DragDropPasteUpload(new StubHost(uploadsInto(sink)), flavor -> pasted);

        upload.pasteFromClipboard(DataFlavor.imageFlavor);

        // The paste runs on its own worker thread; the CICode landing is the signal.
        assertTrue(sink.landed.await(5, TimeUnit.SECONDS), "the clipboard image must reach D12");
        assertTrue(sink.codes.get(0).contains("name=pasted.png"), sink.codes.toString());
    }

    @Test
    void pasteStopsWhenTheClipboardHoldsNoImage() throws Exception {
        Uploads sink = new Uploads();
        CountDownLatch read = new CountDownLatch(1);
        DragDropPasteUpload upload = new DragDropPasteUpload(new StubHost(uploadsInto(sink)),
                flavor -> {
                    read.countDown();
                    return null;
                });

        upload.pasteFromClipboard(DataFlavor.imageFlavor);

        assertTrue(read.await(5, TimeUnit.SECONDS), "the worker must read the clipboard");
        assertTrue(sink.codes.isEmpty(), "no image means no upload");
    }
}
