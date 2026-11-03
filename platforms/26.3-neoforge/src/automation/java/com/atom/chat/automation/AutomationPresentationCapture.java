package com.atom.chat.automation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** One-shot real render-target readback, invoked after GameRenderer.render. */
public final class AutomationPresentationCapture {
    private static volatile Request pending;

    private AutomationPresentationCapture() { }

    static CompletableFuture<Void> request(Path path) {
        if (pending != null) throw new IllegalStateException("Screenshot request already pending");
        var completion = new CompletableFuture<Void>();
        pending = new Request(path, completion);
        return completion;
    }

    public static void afterRender() {
        AutomationBlurMotion.afterRender();
        AutomationMiuixRegression.afterRender();
        var request = pending;
        if (request == null) return;
        pending = null;
        try {
            Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget(), 1, pixels -> {
                // Screenshot already converts the backend's bottom-up readback to top-down PNG rows.
                try (pixels) {
                    pixels.writeToFile(request.path());
                    request.completion().complete(null);
                } catch (Throwable error) {
                    request.completion().completeExceptionally(error);
                }
            });
        } catch (Throwable error) {
            request.completion().completeExceptionally(error);
        }
    }

    private record Request(Path path, CompletableFuture<Void> completion) { }
}
