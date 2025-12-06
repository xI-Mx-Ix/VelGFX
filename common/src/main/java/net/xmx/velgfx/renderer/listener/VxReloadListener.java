/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.listener;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.xmx.velgfx.renderer.model.VxModelManager;

/**
 * A resource reload listener that clears the {@link VxModelManager}.
 * This ensures that models are re-parsed and re-uploaded to the GPU when
 * resource packs are changed, preventing stale data from being rendered.
 *
 * @author xI-Mx-Ix
 */
public class VxReloadListener implements ResourceManagerReloadListener {

    /**
     * Called when the resource manager is reloaded. This method triggers
     * the clearing of the GPU model cache.
     * @param resourceManager The resource manager that was reloaded.
     */
    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        VxModelManager.clear();
    }
}