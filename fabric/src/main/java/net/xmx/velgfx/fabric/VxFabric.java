/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.xmx.velgfx.renderer.VelGFX;

/**
 * Main class for Fabric integration.
 *
 * @author xI-Mx-Ix
 */
public final class VxFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        VelGFX.onClientInit();
    }
}