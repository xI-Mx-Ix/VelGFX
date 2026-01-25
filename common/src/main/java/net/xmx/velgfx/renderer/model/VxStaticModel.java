/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model;

import com.mojang.blaze3d.vertex.PoseStack;
import net.xmx.velgfx.renderer.gl.VxDrawCommand;
import net.xmx.velgfx.renderer.gl.mesh.arena.VxArenaMesh;
import net.xmx.velgfx.renderer.model.animation.VxAnimation;
import net.xmx.velgfx.renderer.model.skeleton.VxSkeleton;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Map;

/**
 * A generic 3D model that supports rigid body animations without vertex skinning.
 * <p>
 * This model uses the SoA Skeleton to manage the hierarchy of rigid parts (e.g., wheels on a car).
 * During rendering, it iterates through bones, fetches their global transforms, and renders
 * the associated mesh subset.
 *
 * @author xI-Mx-Ix
 */
public class VxStaticModel extends VxModel {

    /**
     * Map linking Bone Indices to specific Mesh Draw Commands.
     */
    private final Map<Integer, List<VxDrawCommand>> boneDrawCommands;

    // Scratch matrix
    private final Matrix4f transformCache = new Matrix4f();

    /**
     * Constructs a new static model.
     *
     * @param skeleton         The runtime skeleton.
     * @param mesh             The arena mesh.
     * @param animations       Animation map.
     * @param boneDrawCommands Map of bone index -> list of commands.
     */
    public VxStaticModel(VxSkeleton skeleton, VxArenaMesh mesh, Map<String, VxAnimation> animations, Map<Integer, List<VxDrawCommand>> boneDrawCommands) {
        super(skeleton, mesh, animations, null);
        this.boneDrawCommands = boneDrawCommands;
    }

    @Override
    public VxStaticModel createInstance() {
        // Create new skeleton instance sharing definition using Copy Constructor
        VxSkeleton newSkeleton = new VxSkeleton(this.skeleton);

        return new VxStaticModel(
                newSkeleton,
                (VxArenaMesh) this.mesh,
                this.animations,
                this.boneDrawCommands
        );
    }

    @Override
    public void render(PoseStack poseStack, int packedLight) {
        if (mesh instanceof VxArenaMesh arenaMesh) {
            // Direct access to boneCount
            int boneCount = skeleton.boneCount;

            // Iterate over all bones to render their attached parts
            // This is a linear loop 0..N, very cache friendly.
            for (int i = 0; i < boneCount; i++) {
                List<VxDrawCommand> commands = boneDrawCommands.get(i);

                if (commands != null && !commands.isEmpty()) {
                    poseStack.pushPose();

                    // Retrieve global transform directly from SoA
                    skeleton.getGlobalTransform(i, transformCache);
                    poseStack.mulPose(transformCache);

                    // Submit commands
                    arenaMesh.queueRenderSubset(poseStack, packedLight, commands);

                    poseStack.popPose();
                }
            }
        }

        // Render attachments (Sockets)
        renderAttachments(poseStack, packedLight);
    }
}