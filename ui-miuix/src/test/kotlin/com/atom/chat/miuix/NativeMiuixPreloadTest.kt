package com.atom.chat.miuix

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NativeMiuixPreloadTest {
    @Test
    fun `headless startup warmup leaves real scenes on their own thread and state`() {
        val setup = Executors.newSingleThreadExecutor()
        try {
            // There is no Minecraft window or current GL context on this thread.
            setup.submit { NativeMiuixRuntime.preloadResources() }.get(30, TimeUnit.SECONDS)
            setup.submit { NativeMiuixRuntime.preloadResources() }.get(30, TimeUnit.SECONDS)

            val host = NativeMiuixRuntimeTest.FixtureHost().apply { page = "settings" }
            NativeMiuixRuntime(host).use { runtime ->
                val frame = runtime.render(480, 800, 1f, 2_000_000_000L)
                assertNotNull(frame)
                assertTrue(runtime.getTags().contains("settings-section-appearance"))
                assertFalse(runtime.getTags().contains("chat-composer"), "Preload navigation must not leak")
                assertTrue(host.actions.isEmpty(), "Warmup must not dispatch commands to the real host")
                setup.submit {
                    assertThrows(IllegalStateException::class.java) {
                        runtime.prepareFrame(480, 800, 1f, 2_016_666_667L)
                    }
                }.get(30, TimeUnit.SECONDS)
            }
        } finally {
            setup.shutdownNow()
        }
    }
}
