package com.atom.chat.image;

import com.atom.chat.AtomChat;
import com.atom.chat.util.ClipboardImages;
import com.atom.chat.util.ImageFiles;
import net.minecraft.client.Minecraft;

import java.awt.datatransfer.DataFlavor;
import java.nio.file.Path;
import java.util.List;

/** SDL file drops arrive through Screen.onFilesDrop; clipboard decoding stays off the game thread. */
public final class DragDropPasteUpload {
    public interface Host {
        Minecraft client();
        boolean worldChatPage();
        ImageUploadPipeline uploads();
    }

    @FunctionalInterface
    public interface ClipboardReader {
        Path read(DataFlavor flavor);
    }

    private final Host host;
    private final ClipboardReader clipboard;
    private boolean installed;

    public DragDropPasteUpload(Host host) {
        this(host, ClipboardImages::read);
    }

    DragDropPasteUpload(Host host, ClipboardReader clipboard) {
        this.host = host;
        this.clipboard = clipboard;
    }

    /** Screens receive native SDL drops while active; retain the same lifecycle guard. */
    public void installDropCallback() {
        installed = true;
    }

    public void uninstallDropCallback() {
        installed = false;
    }

    public void onFilesDropped(List<Path> files) {
        if (!installed || !host.worldChatPage()) return;
        try {
            for (Path file : files) {
                if (ImageFiles.isImage(file)) {
                    host.uploads().uploadAndAppend(file);
                    return;
                }
            }
            if (!files.isEmpty()) {
                AtomChat.LOGGER.info(com.atom.chat.diagnostics.AtomChatLog.message("resource",
                        "Ignored {} dropped file(s): none was an image"), files.size());
            }
        } catch (Throwable error) {
            AtomChat.LOGGER.warn(com.atom.chat.diagnostics.AtomChatLog.message("resource",
                    "Failed to handle dropped files"), error);
        }
    }

    public void pasteFromClipboard(DataFlavor flavor) {
        Thread worker = new Thread(() -> {
            Path file = clipboard.read(flavor);
            if (file == null) {
                AtomChat.LOGGER.warn(com.atom.chat.diagnostics.AtomChatLog.message("resource",
                        "Clipboard held no usable image; Ctrl+V fell back to a text paste"));
                return;
            }
            host.uploads().uploadAndAppend(file);
        }, "AtomChat-Paste");
        worker.setDaemon(true);
        worker.start();
    }
}
