/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.example.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.xmx.velgfx.example.body.BodyRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Main class of the Fabric example mod.
 *
 * @author xI-Mx-Ix
 */
public final class ExampleMod implements ModInitializer, ClientModInitializer {
    public static final String MODID = "example_mod";
    public static final Logger LOGGER = LogManager.getLogger("VelGFX Example Mod");

    @Override
    public void onInitialize() {
        BodyRegistry.register();
    }

    @Override
    public void onInitializeClient() {
        BodyRegistry.registerClientFactories();
        BodyRegistry.registerClientRenderers();
    }
}