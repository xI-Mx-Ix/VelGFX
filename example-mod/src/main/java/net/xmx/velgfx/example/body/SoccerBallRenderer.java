/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.example.body;

import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.xmx.velgfx.renderer.model.VxStaticModel;
import net.xmx.velgfx.resources.VxResourceLocation;
import net.xmx.velthoric.physics.body.client.VxRenderState;
import net.xmx.velthoric.physics.body.client.body.renderer.VxRigidBodyRenderer;
import net.xmx.velgfx.renderer.model.VxModelManager;
import org.joml.Quaternionf;

/**
 * Renderer for the {@link SoccerBallRigidBody}.
 * Loads a sphere model from an OBJ file and renders it.
 *
 * @author xI-Mx-Ix
 */
public class SoccerBallRenderer extends VxRigidBodyRenderer<SoccerBallRigidBody> {

    /**
     * The resource location of the sphere model file.
     */
    private static final VxResourceLocation SPHERE_MODEL_LOCATION =
            new VxResourceLocation("example_mod", "models/obj/ball.obj");

    /**
     *  A cached, GPU-resident instance of the sphere model.
     */
    private static VxStaticModel sphereModel = null;

    /**
     * Renders the rigid body by transforming and queuing the pre-loaded sphere model.
     */
    @Override
    public void render(SoccerBallRigidBody body, PoseStack poseStack,
                       MultiBufferSource bufferSource,
                       float partialTicks, int packedLight, VxRenderState renderState) {

        // Lazy initialization: load the model on the first render call
        if (sphereModel == null) {
            // We cast here because we know the implementation extends AbstractRenderableMesh
            sphereModel = VxModelManager.getStaticModel(SPHERE_MODEL_LOCATION).orElse(null);
        }

        // Render the model only if successfully loaded
        if (sphereModel != null) {
            poseStack.pushPose();

            Quat renderRotation = renderState.transform.getRotation();
            poseStack.mulPose(new Quaternionf(
                    renderRotation.getX(),
                    renderRotation.getY(),
                    renderRotation.getZ(),
                    renderRotation.getW()
            ));

            // Scale the unit-sized sphere model to the body's radius
            float radius = body.get(SoccerBallRigidBody.DATA_RADIUS);
            if (radius > 0) {
                poseStack.scale(radius, radius, radius);
            }

            sphereModel.render(poseStack, packedLight);

            poseStack.popPose();
        }
    }
}