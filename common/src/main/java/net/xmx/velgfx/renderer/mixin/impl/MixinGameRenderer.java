/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.mixin.impl;

import net.minecraft.client.renderer.GameRenderer;
import net.xmx.velgfx.renderer.VelGFX;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin hook into the Minecraft GameRenderer.
 * <p>
 * This class is responsible for triggering the initialization of VelGFX's
 * OpenGL resources (Shaders, Buffers) at the correct point in the
 * startup lifecycle (when the GL Context is active).
 *
 * @author xI-Mx-Ix
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer {

    /**
     * Injects logic at the end of the GameRenderer constructor.
     * <p>
     * At this point, the window and OpenGL context are guaranteed to be created,
     * allowing for safe compilation of shaders.
     *
     * @param ci The callback info provided by SpongeMixin.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void onGameRendererInit(CallbackInfo ci) {
        VelGFX.initOpenGL();
    }
}