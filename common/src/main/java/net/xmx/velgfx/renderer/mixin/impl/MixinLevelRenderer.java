/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.mixin.impl;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.xmx.velgfx.renderer.gl.mesh.VxRenderQueue;
import net.xmx.velgfx.renderer.gl.mesh.arena.skinning.VxSkinningBatcher;
import net.xmx.velgfx.renderer.util.VxGlGarbageCollector;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into the Minecraft LevelRenderer to manage the entire lifecycle of the custom rendering system.
 * <p>
 * This class injects hooks at specific points in the frame execution to:
 * <ol>
 *     <li>Reset internal buffers and process garbage collection at the start of the frame.</li>
 *     <li>Execute the GPU Skinning Compute Pass (via {@link VxSkinningBatcher}).</li>
 *     <li>Render Opaque/Cutout geometry into the G-Buffers (or main buffer).</li>
 *     <li>Render Translucent geometry sorted by depth.</li>
 * </ol>
 *
 * @author xI-Mx-Ix
 */
@Mixin(value = LevelRenderer.class, priority = 1500)
public class MixinLevelRenderer {

    /**
     * Injects at the very start of the frame rendering process.
     * <p>
     * Responsible for resetting the render queues to clear data from the previous frame
     * and processing any pending OpenGL resource deletions on the render thread.
     */
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void velgfx_onRenderLevel_Head(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
        // Reset the main render queue (clear meshes and matrices)
        VxRenderQueue.getInstance().reset();

        // Reset the skinning batcher (clear queued models for compute)
        VxSkinningBatcher.getInstance().reset();

        // Process the Garbage Collector queue to free OpenGL resources safely
        VxGlGarbageCollector.getInstance().processQueue();
    }

    /**
     * Injects immediately after the vanilla entity batches (Solid/Cutout) are finished.
     * <p>
     * This specific injection point corresponds to the end of {@code RenderType.entitySmoothCutout}.
     * At this stage, the depth buffer is primed with vanilla opaque geometry.
     * <p>
     * <b>Pipeline Order:</b>
     * <ol>
     *     <li><b>Skinning Batch Flush:</b> Executes all queued skeletal deformations on the GPU.
     *     This must happen <i>before</i> rendering, as the render pass depends on the
     *     output of the Transform Feedback.</li>
     *     <li><b>Opaque Flush:</b> Renders the VelGFX Opaque and Cutout buckets.</li>
     * </ol>
     */
    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch(Lnet/minecraft/client/renderer/RenderType;)V",
                    ordinal = 3, // Targets end of entitySmoothCutout (after solid and cutout phases)
                    shift = At.Shift.AFTER
            )
    )
    private void velgfx_onRenderLevel_AfterEntitiesOpaque(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
        // 1. Execute all pending Skinning Transforms (GPU Compute Pass)
        // This minimizes state changes by processing all skinned models in one contiguous block.
        VxSkinningBatcher.getInstance().flush();

        // 2. Render the Opaque and Cutout meshes using the computed skinning data
        VxRenderQueue.getInstance().flushOpaque(frustumMatrix, projectionMatrix);
    }

    /**
     * Injects after the vanilla Translucent pass is finished.
     * <p>
     * Renders VelGFX Translucent geometry. The queue sorts these objects back-to-front
     * based on the provided camera position to ensure correct alpha blending.
     */
    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch(Lnet/minecraft/client/renderer/RenderType;)V",
                    ordinal = 4, // Targets end of translucent batch (water, stained glass, etc.)
                    shift = At.Shift.AFTER
            )
    )
    private void velgfx_onRenderLevel_AfterTranslucent(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
        Vector3f camPos = camera.getPosition().toVector3f();
        VxRenderQueue.getInstance().flushTranslucent(frustumMatrix, projectionMatrix, camPos);
    }
}