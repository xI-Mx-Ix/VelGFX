/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.example.body.soccerball;

import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.MassProperties;
import com.github.stephengold.joltjni.ShapeSettings;
import com.github.stephengold.joltjni.SphereShapeSettings;
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
 * A rigid body with a spherical shape.
 *
 * @author xI-Mx-Ix
 */
public class SoccerBallRigidBody extends VxRigidBody {

    public static final VxServerAccessor<Float> DATA_RADIUS = VxServerAccessor.create(SoccerBallRigidBody.class, VxDataSerializers.FLOAT);

    /**
     * Server-side constructor.
     */
    public SoccerBallRigidBody(VxBodyType<SoccerBallRigidBody> type, VxPhysicsWorld world, UUID id) {
        super(type, world, id);
    }

    /**
     * Client-side constructor.
     */
    @Environment(EnvType.CLIENT)
    public SoccerBallRigidBody(VxBodyType<SoccerBallRigidBody> type, UUID id) {
        super(type, id);
    }

    @Override
    protected void defineSyncData(VxSynchronizedData.Builder builder) {
        builder.define(DATA_RADIUS, 0.35f);
    }

    public void setRadius(float radius) {
        this.setServerData(DATA_RADIUS, radius > 0 ? radius : 0.35f);
    }

    public float getRadius() {
        return get(DATA_RADIUS);
    }

    @Override
    public int createJoltBody(VxRigidBodyFactory factory) {
        try (
                ShapeSettings shapeSettings = new SphereShapeSettings(this.getRadius());
                BodyCreationSettings bcs =
                        new BodyCreationSettings()
                                .setRestitution(0.6f)
                                .setFriction(0.4f)

        ) {
            MassProperties massProps = new MassProperties();
            massProps.scaleToMass(0.43f);
            bcs.setMassPropertiesOverride(massProps);
            bcs.setOverrideMassProperties(EOverrideMassProperties.MassAndInertiaProvided);

            bcs.setMotionType(EMotionType.Dynamic);
            bcs.setObjectLayer(VxPhysicsLayers.MOVING);
            return factory.create(shapeSettings, bcs);
        }
    }

    @Override
    public void writePersistenceData(VxByteBuf buf) {
        buf.writeFloat(getRadius());
    }

    @Override
    public void readPersistenceData(VxByteBuf buf) {
        setRadius(buf.readFloat());
    }
}