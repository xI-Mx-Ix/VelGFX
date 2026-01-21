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
import net.xmx.velgfx.renderer.util.VxGlGarbageCollector;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into LevelRenderer to manage the entire lifecycle of the custom rendering system.
 * <p>
 * 1. HEAD: Resets the queue and prepares buffers.
 * 2. AFTER_ENTITIES: Flushes the OPAQUE and CUTOUT queues into the G-Buffers.
 * 3. AFTER_TRANSLUCENT: Flushes the TRANSLUCENT queue sorted back-to-front.
 *
 * @author xI-Mx-Ix
 */
@Mixin(value = LevelRenderer.class, priority = 1500)
public class MixinLevelRenderer {

    /**
     * Injects at the start of the frame to reset internal buffers.
     */
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void velgfx_onRenderLevel_Head(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
        VxRenderQueue.getInstance().reset();

        // Process OpenGL Garbage Collection
        VxGlGarbageCollector.getInstance().processQueue();
    }

    /**
     * Injects immediately after the vanilla entity batches (Solid/Cutout) are finished.
     * <p>
     * Renders Opaque and Cutout geometry. This ensures depth is written correctly for
     * subsequent transparent objects and G-Buffer generation in shaderpacks.
     */
    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch(Lnet/minecraft/client/renderer/RenderType;)V",
                    ordinal = 3, // Targets end of entitySmoothCutout (after solid and cutout)
                    shift = At.Shift.AFTER
            )
    )
    private void velgfx_onRenderLevel_AfterEntitiesOpaque(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
        VxRenderQueue.getInstance().flushOpaque(frustumMatrix, projectionMatrix);
    }

    /**
     * Injects after the vanilla Translucent pass is finished.
     * <p>
     * Renders Translucent geometry, sorted back-to-front.
     */
    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch(Lnet/minecraft/client/renderer/RenderType;)V",
                    ordinal = 4, // Targets end of translucent (water, stained glass, etc.)
                    shift = At.Shift.AFTER
            )
    )
    private void velgfx_onRenderLevel_AfterTranslucent(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
        Vector3f camPos = camera.getPosition().toVector3f();
        VxRenderQueue.getInstance().flushTranslucent(frustumMatrix, projectionMatrix, camPos);
    }
}