/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.example.body;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;
import net.xmx.velgfx.example.body.cesium.CesiumManBody;
import net.xmx.velgfx.example.body.cesium.CesiumManRenderer;
import net.xmx.velgfx.example.body.soccerball.SoccerBallRenderer;
import net.xmx.velgfx.example.body.soccerball.SoccerBallRigidBody;
import net.xmx.velgfx.example.fabric.ExampleMod;
import net.xmx.velthoric.physics.body.registry.VxBodyRegistry;
import net.xmx.velthoric.physics.body.registry.VxBodyType;

/**
 * Central registry for built-in physics body types.
 * <p>
 * Handles the registration of body types, client-side factories, and renderers.
 *
 * @author xI-Mx-Ix
 */
@SuppressWarnings("unchecked")
public class BodyRegistry {

    // --- Body Types ---

    /**
     * A simple rigid body represented by a static sphere model.
     */
    public static final VxBodyType<SoccerBallRigidBody> SOCCER_BALL = VxBodyType.Builder
            .<SoccerBallRigidBody>create(SoccerBallRigidBody::new)
            .build(ResourceLocation.tryBuild(ExampleMod.MODID, "soccer_ball"));

    /**
     * A complex rigid body represented by a skinned character model (Cesium Man).
     */
    public static final VxBodyType<CesiumManBody> CESIUM_MAN = VxBodyType.Builder
            .<CesiumManBody>create(CesiumManBody::new)
            .build(ResourceLocation.tryBuild(ExampleMod.MODID, "cesium_man"));

    /**
     * Registers the body types to the global physics registry.
     */
    public static void register() {
        var registry = VxBodyRegistry.getInstance();
        registry.register(SOCCER_BALL);
        registry.register(CESIUM_MAN);
    }

    /**
     * Registers factories for creating client-side instances of bodies.
     * This is required for networking synchronization.
     */
    @Environment(EnvType.CLIENT)
    public static void registerClientFactories() {
        var registry = VxBodyRegistry.getInstance();

        registry.registerClientFactory(SOCCER_BALL.getTypeId(),
                (type, id) -> new SoccerBallRigidBody((VxBodyType<SoccerBallRigidBody>) type, id));

        registry.registerClientFactory(CESIUM_MAN.getTypeId(),
                (type, id) -> new CesiumManBody((VxBodyType<CesiumManBody>) type, id));
    }

    /**
     * Registers the visual renderers for the bodies.
     */
    @Environment(EnvType.CLIENT)
    public static void registerClientRenderers() {
        var registry = VxBodyRegistry.getInstance();

        registry.registerClientRenderer(SOCCER_BALL.getTypeId(), new SoccerBallRenderer());
        registry.registerClientRenderer(CESIUM_MAN.getTypeId(), new CesiumManRenderer());
    }
}