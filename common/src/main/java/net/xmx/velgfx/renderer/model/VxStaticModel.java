/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model;

import com.mojang.blaze3d.vertex.PoseStack;
import net.xmx.velgfx.renderer.gl.VxDrawCommand;
import net.xmx.velgfx.renderer.gl.mesh.arena.VxArenaMesh;
import net.xmx.velgfx.renderer.model.animation.VxAnimation;
import net.xmx.velgfx.renderer.model.skeleton.VxNode;
import net.xmx.velgfx.renderer.model.skeleton.VxSkeleton;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Map;

/**
 * A generic 3D model that supports rigid body animations (Node Hierarchy) without vertex skinning.
 * <p>
 * Rendering is performed by traversing the scene graph and submitting group-specific
 * render calls to the queue. The geometry resides in a static Arena Mesh.
 *
 * @author xI-Mx-Ix
 */
public class VxStaticModel extends VxModel {

    private final VxSkeleton skeleton;
    private final Map<String, List<VxDrawCommand>> nodeDrawCommands;

    /**
     * Constructs a new Static Model.
     *
     * @param skeleton         The skeleton wrapper containing the root node of the scene graph.
     * @param mesh             The arena mesh containing the model geometry.
     * @param animations       The map of available animations.
     * @param nodeDrawCommands The mapping of node names to specific draw commands.
     */
    public VxStaticModel(VxSkeleton skeleton, VxArenaMesh mesh, Map<String, VxAnimation> animations, Map<String, List<VxDrawCommand>> nodeDrawCommands) {
        super(skeleton.getRootNode(), mesh, animations);
        this.skeleton = skeleton;
        this.nodeDrawCommands = nodeDrawCommands;
    }

    @Override
    public VxStaticModel createInstance() {
        // 1. Deep copy the node hierarchy (Skeleton)
        // This allows nodes to move independently (Rigid Body Animation) without affecting other instances.
        VxSkeleton newSkeleton = this.skeleton.deepCopy();

        // 2. Create new instance sharing the geometry
        // We cast mesh to VxArenaMesh because VxStaticModel guarantees it uses Arena meshes.
        return new VxStaticModel(
                newSkeleton,
                (VxArenaMesh) this.mesh,
                this.animations,
                this.nodeDrawCommands
        );
    }

    @Override
    public void render(PoseStack poseStack, int packedLight) {
        // Safe cast check, though it should always be VxArenaMesh in this architecture
        if (mesh instanceof VxArenaMesh arenaMesh) {
            traverseAndRender(rootNode, poseStack, packedLight, arenaMesh);
        }
    }

    /**
     * Recursively traverses the scene graph to render nodes with their animated transforms.
     */
    private void traverseAndRender(VxNode node, PoseStack poseStack, int packedLight, VxArenaMesh arenaMesh) {
        poseStack.pushPose();

        // Apply local transformation (animated state)
        Matrix4f localTransform = node.getLocalTransform();
        poseStack.mulPose(localTransform);

        // Retrieve the specific draw commands associated with this node from the model's internal map
        List<VxDrawCommand> commands = nodeDrawCommands.get(node.getName());

        if (commands != null && !commands.isEmpty()) {
            // Queue only the specific parts of the mesh belonging to this node
            arenaMesh.queueRenderSubset(poseStack, packedLight, commands);
        }

        // Process children
        for (VxNode child : node.getChildren()) {
            traverseAndRender(child, poseStack, packedLight, arenaMesh);
        }

        poseStack.popPose();
    }

    public VxSkeleton getSkeleton() {
        return skeleton;
    }
}