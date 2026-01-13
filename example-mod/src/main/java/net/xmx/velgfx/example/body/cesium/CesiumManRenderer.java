/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.example.body.cesium;

import com.github.stephengold.joltjni.Quat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.xmx.velgfx.renderer.model.VxModelManager;
import net.xmx.velgfx.renderer.model.VxSkinnedModel;
import net.xmx.velgfx.resources.VxResourceLocation;
import net.xmx.velthoric.physics.body.client.VxRenderState;
import net.xmx.velthoric.physics.body.client.body.renderer.VxRigidBodyRenderer;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Renderer for the Cesium Man character.
 * <p>
 * This class handles loading the model, managing animations, and applying the correct
 * transformations to render the physics body in the world.
 *
 * @author xI-Mx-Ix
 */
public class CesiumManRenderer extends VxRigidBodyRenderer<CesiumManBody> {

    // Path to the model file in the resources
    private static final VxResourceLocation MODEL_LOCATION =
            new VxResourceLocation("example_mod", "models/glb/cesium_man.glb");

    // We keep a separate model instance.
    // This is necessary because every entity needs its own animation state (time cursor).
    private final Map<UUID, VxSkinnedModel> modelInstances = new HashMap<>();

    @Override
    public void render(CesiumManBody body, PoseStack poseStack,
                       MultiBufferSource bufferSource,
                       float partialTicks, int packedLight, VxRenderState renderState) {

        UUID id = body.getPhysicsId();

        // 1. Get or load the model instance for this specific entity
        VxSkinnedModel model = modelInstances.computeIfAbsent(id, k -> {
            // Load the template and create a unique copy for this entity
            VxSkinnedModel newModel = VxModelManager.getSkinnedModel(MODEL_LOCATION)
                    .map(VxSkinnedModel::createInstance)
                    .orElse(null);

            if (newModel != null) {
                newModel.getAnimator().setSpeed(1.0f);

                var it = newModel.getAnimations().keySet().iterator();
                if (it.hasNext()) {
                    newModel.playAnimation(it.next());
                }
            }

            return newModel;
        });

        // If loading failed, we can't render anything
        if (model == null) return;

        // 2. Calculate smooth time delta
        Minecraft mc = Minecraft.getInstance();
        float deltaTicks = mc.getTimer().getRealtimeDeltaTicks();

        // Convert ticks to seconds (1 tick = 0.05 seconds)
        float deltaSeconds = deltaTicks * 0.05f;

        if (mc.isPaused()) {
            deltaSeconds = 0f;
        }

        // Advance the animation logic
        model.update(deltaSeconds);

        // 3. Render the model
        poseStack.pushPose();

        // Apply the physics rotation from Jolt (interpolated)
        Quat renderRotation = renderState.transform.getRotation();
        poseStack.mulPose(new Quaternionf(
                renderRotation.getX(),
                renderRotation.getY(),
                renderRotation.getZ(),
                renderRotation.getW()
        ));

        // Apply scaling if set in the body data
        float scale = body.get(CesiumManBody.DATA_SCALE);
        if (scale > 0) {
            poseStack.scale(scale, scale, scale);
        }

        poseStack.translate(0, -0.9, 0);
        poseStack.mulPose(Axis.XP.rotationDegrees(90.0f));
        poseStack.mulPose(Axis.YP.rotationDegrees(180));

        // Draw the mesh
        model.render(poseStack, packedLight);

        poseStack.popPose();
    }

    /**
     * Cleans up memory when an entity is removed.
     */
    public void cleanup(UUID id) {
        VxSkinnedModel model = modelInstances.remove(id);
        if (model != null) {
            model.delete();
        }
    }
}