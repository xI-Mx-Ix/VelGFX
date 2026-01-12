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
 * Renderer for the {@link CesiumManBody}.
 * <p>
 * Handles the rendering of a GLB skinned mesh with embedded textures.
 * Manages independent animation states for each entity instance.
 *
 * @author xI-Mx-Ix
 */
public class CesiumManRenderer extends VxRigidBodyRenderer<CesiumManBody> {

    /**
     * The resource location of the GLB file containing the skinned model and embedded textures.
     */
    private static final VxResourceLocation MODEL_LOCATION =
            new VxResourceLocation("example_mod", "models/glb/cesium_man.glb");

    /**
     * Cache mapping entity UUIDs to their specific model instance.
     * Required because Skinned Models are stateful (they hold animation time).
     */
    private final Map<UUID, VxSkinnedModel> modelInstances = new HashMap<>();

    @Override
    public void render(CesiumManBody body, PoseStack poseStack,
                       MultiBufferSource bufferSource,
                       float partialTicks, int packedLight, VxRenderState renderState) {

        UUID id = body.getPhysicsId();

        // 1. Retrieve or Create the per-entity model instance
        VxSkinnedModel model = modelInstances.computeIfAbsent(id, k -> {
            // Fetch the template from the manager
            return VxModelManager.getSkinnedModel(MODEL_LOCATION)
                    // Create a unique instance (Deep copy skeleton) so this body animates independently
                    .map(VxSkinnedModel::createInstance)
                    .orElse(null);
        });

        // Abort if model failed to load
        if (model == null) return;

        // 2. Update Animation State
        // "anim_0" is the standard default animation name in GLTF files
        // We ensure it's playing and update the timer.
        model.playAnimation("anim_0");
        
        // Update the animation skeleton.
        model.update(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true));

        // 3. Render
        poseStack.pushPose();

        // Apply Physics Rotation (Jolt Quaternion -> JOML Quaternion)
        Quat renderRotation = renderState.transform.getRotation();
        poseStack.mulPose(new Quaternionf(
                renderRotation.getX(),
                renderRotation.getY(),
                renderRotation.getZ(),
                renderRotation.getW()
        ));

        // Apply Scaling from Body Data
        float scale = body.get(CesiumManBody.DATA_SCALE);
        if (scale > 0) {
            poseStack.scale(scale, scale, scale);
        }

        // Correction for coordinate system differences
        // GLTF models are often Y-Up, but facing -Z.
        // Depending on how Jolt and the Model align, a rotation might be needed.
        // Usually, CesiumMan requires a 180 rotation to face "forward" or -90 on X if imported differently.
        // Assuming standard import:
        poseStack.mulPose(Axis.YP.rotationDegrees(180));
        poseStack.translate(0, -0.9, 0); // Offset to center model in the Box Shape (0.9 is half-height)

        model.render(poseStack, packedLight);

        poseStack.popPose();
    }

    /**
     * Optional cleanup hook to prevent memory leaks when entities are unloaded.
     * Should be called by the system when the body is removed.
     *
     * @param id The UUID of the body to remove.
     */
    public void cleanup(UUID id) {
        VxSkinnedModel model = modelInstances.remove(id);
        if (model != null) {
            model.delete();
        }
    }
}