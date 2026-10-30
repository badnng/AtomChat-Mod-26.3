package com.atom.chat.image;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageLoaderLifecycleTest {
    @Test
    void completedImageFetchWorkersMustNotKeepTheGameJvmAlive() throws Exception {
        // Exercise the actual singleton's executor: the original unnamed pool
        // left three idle, non-daemon threads after Minecraft had saved and
        // closed its module, preventing the game process from terminating.
        var field = ImageLoader.class.getDeclaredField("executor");
        field.setAccessible(true);
        Executor executor = (Executor) field.get(ImageLoader.get());
        for (int i = 0; i < 3; i++) {
            FutureTask<Thread> work = new FutureTask<>(Thread::currentThread);
            executor.execute(work);
            Thread worker = work.get(5, TimeUnit.SECONDS);
            assertTrue(worker.isDaemon(),
                    "An idle image fetch worker must not block normal JVM shutdown: " + worker.getName());
        }
    }
}
