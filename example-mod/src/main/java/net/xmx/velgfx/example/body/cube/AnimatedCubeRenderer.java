/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.example.body.cube;

import com.github.stephengold.joltjni.Quat;
import com.mojang.blaze3d.vertex.PoseStack;
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
 * Renderer for the Animated Morph Cube.
 * <p>
 * Handles loading the GLB, creating a unique instance per entity,
 * and updating the animation loop that drives the morph targets.
 *
 * @author xI-Mx-Ix
 */
public class AnimatedCubeRenderer extends VxRigidBodyRenderer<AnimatedCubeBody> {

    // Path to the model file in the resources.
    private static final VxResourceLocation MODEL_LOCATION =
            new VxResourceLocation("example_mod", "models/glb/animated_morph_cube.glb");

    // Cache instances to maintain independent animation states per cube.
    private final Map<UUID, VxSkinnedModel> modelInstances = new HashMap<>();

    @Override
    public void render(AnimatedCubeBody body, PoseStack poseStack,
                       MultiBufferSource bufferSource,
                       float partialTicks, int packedLight, VxRenderState renderState) {

        UUID id = body.getPhysicsId();

        // 1. Get or load the model instance for this specific entity
        VxSkinnedModel model = modelInstances.computeIfAbsent(id, k -> {
            // Load the template and create a unique copy
            VxSkinnedModel newModel = VxModelManager.getSkinnedModel(MODEL_LOCATION)
                    .map(VxSkinnedModel::createInstance)
                    .orElse(null);

            if (newModel != null) {
                // Auto-start the first animation found in the file
                var it = newModel.getAnimations().keySet().iterator();
                if (it.hasNext()) {
                    // Usually "animation_0" in this specific sample file
                    newModel.playAnimation(it.next());
                }
                newModel.getAnimator().setLooping(true);
            }

            return newModel;
        });

        if (model == null) return;

        // 2. Update Animation (Morph Targets are driven by the animation track)
        Minecraft mc = Minecraft.getInstance();
        float deltaTicks = mc.getTimer().getRealtimeDeltaTicks();
        float deltaSeconds = deltaTicks * 0.05f;

        if (mc.isPaused()) {
            deltaSeconds = 0f;
        }

        model.update(deltaSeconds);

        // 3. Render
        poseStack.pushPose();

        // Apply Physics Rotation
        Quat renderRotation = renderState.transform.getRotation();
        poseStack.mulPose(new Quaternionf(
                renderRotation.getX(),
                renderRotation.getY(),
                renderRotation.getZ(),
                renderRotation.getW()
        ));

        model.render(poseStack, packedLight);

        poseStack.popPose();
    }

    /**
     * Cleans up GPU resources for this specific instance when the entity is despawned.
     */
    public void cleanup(UUID id) {
        VxSkinnedModel model = modelInstances.remove(id);
        if (model != null) {
            model.delete();
        }
    }
}