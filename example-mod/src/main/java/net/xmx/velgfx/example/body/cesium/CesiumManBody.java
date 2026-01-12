/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.example.body.cesium;

import com.github.stephengold.joltjni.*;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import com.github.stephengold.joltjni.enumerate.EOverrideMassProperties;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.xmx.velthoric.network.VxByteBuf;
import net.xmx.velthoric.physics.VxPhysicsLayers;
import net.xmx.velthoric.physics.body.network.synchronization.VxDataSerializers;
import net.xmx.velthoric.physics.body.network.synchronization.VxSynchronizedData;
import net.xmx.velthoric.physics.body.network.synchronization.accessor.VxServerAccessor;
import net.xmx.velthoric.physics.body.registry.VxBodyType;
import net.xmx.velthoric.physics.body.type.VxRigidBody;
import net.xmx.velthoric.physics.body.type.factory.VxRigidBodyFactory;
import net.xmx.velthoric.physics.world.VxPhysicsWorld;

import java.util.UUID;

/**
 * A rigid body representing the "Cesium Man" character.
 * <p>
 * Physically represented by a Box shape to approximate the humanoid volume.
 * Supports dynamic scaling via synchronized data.
 *
 * @author xI-Mx-Ix
 */
public class CesiumManBody extends VxRigidBody {

    /**
     * Synchronized float value controlling the visual and physical scale of the body.
     */
    public static final VxServerAccessor<Float> DATA_SCALE = VxServerAccessor.create(CesiumManBody.class, VxDataSerializers.FLOAT);

    /**
     * Server-side constructor.
     *
     * @param type  The body type definition.
     * @param world The physics world instance.
     * @param id    The unique identifier.
     */
    public CesiumManBody(VxBodyType<CesiumManBody> type, VxPhysicsWorld world, UUID id) {
        super(type, world, id);
    }

    /**
     * Client-side constructor for network replication.
     *
     * @param type The body type definition.
     * @param id   The unique identifier.
     */
    @Environment(EnvType.CLIENT)
    public CesiumManBody(VxBodyType<CesiumManBody> type, UUID id) {
        super(type, id);
    }

    @Override
    protected void defineSyncData(VxSynchronizedData.Builder builder) {
        // Default scale of 1.0
        builder.define(DATA_SCALE, 1.0f);
    }

    /**
     * Sets the uniform scale of the body.
     *
     * @param scale The new scale factor (must be > 0).
     */
    public void setScale(float scale) {
        this.setServerData(DATA_SCALE, scale > 0 ? scale : 1.0f);
    }

    /**
     * Gets the current scale factor.
     *
     * @return The scale.
     */
    public float getScale() {
        return get(DATA_SCALE);
    }

    @Override
    public int createJoltBody(VxRigidBodyFactory factory) {
        float s = getScale();
        
        // Create a Box Shape. 
        // Half-extents: 0.25 width, 0.9 height (1.8m tall total), 0.25 depth
        try (
                ShapeSettings shapeSettings = new BoxShapeSettings(new Vec3(0.25f * s, 0.9f * s, 0.25f * s));
                BodyCreationSettings bcs = new BodyCreationSettings()
        ) {
            // Physical Properties
            bcs.setRestitution(0.2f);
            bcs.setFriction(0.8f);
            
            // Mass override
            MassProperties massProps = new MassProperties();
            massProps.scaleToMass(70.0f * s); // Approx 70kg at scale 1
            bcs.setMassPropertiesOverride(massProps);
            bcs.setOverrideMassProperties(EOverrideMassProperties.MassAndInertiaProvided);

            bcs.setMotionType(EMotionType.Dynamic);
            bcs.setObjectLayer(VxPhysicsLayers.MOVING);

            return factory.create(shapeSettings, bcs);
        }
    }

    @Override
    public void writePersistenceData(VxByteBuf buf) {
        buf.writeFloat(getScale());
    }

    @Override
    public void readPersistenceData(VxByteBuf buf) {
        setScale(buf.readFloat());
    }
}