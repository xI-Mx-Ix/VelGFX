/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.example.body;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;
import net.xmx.velgfx.example.fabric.ExampleMod;
import net.xmx.velthoric.physics.body.registry.VxBodyRegistry;
import net.xmx.velthoric.physics.body.registry.VxBodyType;

/**
 * Registry for built-in physics body types.
 *
 * @author xI-Mx-Ix
 */
@SuppressWarnings("unchecked")
public class BodyRegistry {

    public static final VxBodyType<SoccerBallRigidBody> SOCCER_BALL = VxBodyType.Builder
            .<SoccerBallRigidBody>create(SoccerBallRigidBody::new)
            .build(ResourceLocation.tryBuild(ExampleMod.MODID, "soccer_ball"));

    public static void register() {
        var registry = VxBodyRegistry.getInstance();
        registry.register(SOCCER_BALL);
    }

    @Environment(EnvType.CLIENT)
    public static void registerClientFactories() {
        var registry = VxBodyRegistry.getInstance();
        registry.registerClientFactory(SOCCER_BALL.getTypeId(), (type, id) -> new SoccerBallRigidBody((VxBodyType<SoccerBallRigidBody>) type, id));
    }

    @Environment(EnvType.CLIENT)
    public static void registerClientRenderers() {
        var registry = VxBodyRegistry.getInstance();
        registry.registerClientRenderer(SOCCER_BALL.getTypeId(), new SoccerBallRenderer());
    }
}