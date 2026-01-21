/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.example.body.cube;

import com.github.stephengold.joltjni.*;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import com.github.stephengold.joltjni.enumerate.EOverrideMassProperties;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.xmx.velthoric.network.VxByteBuf;
import net.xmx.velthoric.physics.VxPhysicsLayers;
import net.xmx.velthoric.physics.body.network.synchronization.VxSynchronizedData;
import net.xmx.velthoric.physics.body.registry.VxBodyType;
import net.xmx.velthoric.physics.body.type.VxRigidBody;
import net.xmx.velthoric.physics.body.type.factory.VxRigidBodyFactory;
import net.xmx.velthoric.physics.world.VxPhysicsWorld;

import java.util.UUID;

/**
 * A rigid body representing the "Animated Morph Cube" sample model.
 * <p>
 * Physically represented by a 1x1x1 Box shape.
 * Visuals utilize Morph Targets to deform the cube.
 *
 * @author xI-Mx-Ix
 */
public class AnimatedCubeBody extends VxRigidBody {

    /**
     * Server-side constructor.
     *
     * @param type  The body type definition.
     * @param world The physics world instance.
     * @param id    The unique identifier.
     */
    public AnimatedCubeBody(VxBodyType<AnimatedCubeBody> type, VxPhysicsWorld world, UUID id) {
        super(type, world, id);
    }

    /**
     * Client-side constructor for network replication.
     *
     * @param type The body type definition.
     * @param id   The unique identifier.
     */
    @Environment(EnvType.CLIENT)
    public AnimatedCubeBody(VxBodyType<AnimatedCubeBody> type, UUID id) {
        super(type, id);
    }

    @Override
    protected void defineSyncData(VxSynchronizedData.Builder builder) {
    }

    @Override
    public int createJoltBody(VxRigidBodyFactory factory) {
        // The glTF Sample Model is a 1x1x1 cube (from -0.5 to 0.5).
        // Jolt BoxShape takes half-extents, so 0.5f.
        try (
                ShapeSettings shapeSettings = new BoxShapeSettings(new Vec3(0.5f, 0.5f, 0.5f));
                BodyCreationSettings bcs = new BodyCreationSettings()
        ) {
            // Physical Properties
            bcs.setRestitution(0.5f); // Bouncy
            bcs.setFriction(0.6f);

            // Mass override (e.g. 10kg)
            MassProperties massProps = new MassProperties();
            massProps.scaleToMass(10.0f);
            bcs.setMassPropertiesOverride(massProps);
            bcs.setOverrideMassProperties(EOverrideMassProperties.MassAndInertiaProvided);

            bcs.setMotionType(EMotionType.Dynamic);
            bcs.setObjectLayer(VxPhysicsLayers.MOVING);

            return factory.create(shapeSettings, bcs);
        }
    }

    @Override
    public void writePersistenceData(VxByteBuf buf) {
        // Nothing to save
    }

    @Override
    public void readPersistenceData(VxByteBuf buf) {
        // Nothing to read
    }
}