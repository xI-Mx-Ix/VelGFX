/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.example.body.soccerball;

import com.github.stephengold.joltjni.Quat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.xmx.velgfx.renderer.model.VxModelManager;
import net.xmx.velgfx.renderer.model.VxStaticModel;
import net.xmx.velgfx.resources.VxResourceLocation;
import net.xmx.velthoric.physics.body.client.VxRenderState;
import net.xmx.velthoric.physics.body.client.body.renderer.VxRigidBodyRenderer;
import org.joml.Quaternionf;

/**
 * Renderer for the {@link SoccerBallRigidBody}.
 * <p>
 * Uses a cached {@link VxStaticModel} (Sphere) for rendering.
 * Since the ball is a single rigid mesh with no moving parts, we use a shared
 * singleton instance for all balls to save memory.
 *
 * @author xI-Mx-Ix
 */
public class SoccerBallRenderer extends VxRigidBodyRenderer<SoccerBallRigidBody> {

    /**
     * The resource location of the sphere model file.
     */
    private static final VxResourceLocation SPHERE_MODEL_LOCATION =
            new VxResourceLocation("example_mod", "models/obj/soccer_ball.obj");

    /**
     * A cached, GPU-resident instance of the sphere model.
     * Shared across all soccer ball entities.
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
            sphereModel = VxModelManager.getStaticModel(SPHERE_MODEL_LOCATION).orElse(null);
        }

        // Render the model only if successfully loaded
        if (sphereModel != null) {
            poseStack.pushPose();

            // Apply Physics Rotation
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