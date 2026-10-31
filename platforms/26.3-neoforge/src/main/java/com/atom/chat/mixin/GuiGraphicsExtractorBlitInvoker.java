package com.atom.chat.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Submits Skia's premultiplied GPU texture through vanilla's GUI composition. */
@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsExtractorBlitInvoker {
    @Invoker("innerBlit")
    void atomchat$blit(RenderPipeline pipeline, GpuTextureView texture, GpuSampler sampler,
                      int x0, int y0, int x1, int y1,
                      float u0, float u1, float v0, float v1, int color);
}
