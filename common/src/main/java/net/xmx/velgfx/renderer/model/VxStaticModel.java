/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model;

import com.mojang.blaze3d.vertex.PoseStack;
import net.xmx.velgfx.renderer.gl.VxDrawCommand;
import net.xmx.velgfx.renderer.gl.mesh.IVxRenderableMesh;
import net.xmx.velgfx.renderer.gl.mesh.VxAbstractRenderableMesh;
import net.xmx.velgfx.renderer.gl.mesh.VxRenderQueue;
import net.xmx.velgfx.renderer.model.animation.VxAnimation;
import net.xmx.velgfx.renderer.model.skeleton.VxNode;
import org.joml.Matrix4f;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A generic 3D model that supports rigid body animations (Node Hierarchy) without vertex skinning.
 * <p>
 * This model uses {@link net.xmx.velgfx.renderer.gl.layout.VxStaticVertexLayout}.
 * Rendering is performed by traversing the scene graph and applying local node transformations
 * to the PoseStack before submitting draw commands.
 *
 * @author xI-Mx-Ix
 */
public class VxStaticModel extends VxModel {

    private final Map<String, List<VxDrawCommand>> nodeDrawCommands;

    public VxStaticModel(VxNode rootNode, IVxRenderableMesh mesh, Map<String, VxAnimation> animations, Map<String, List<VxDrawCommand>> nodeDrawCommands) {
        super(rootNode, mesh, animations);
        this.nodeDrawCommands = nodeDrawCommands;
    }

    @Override
    public void render(PoseStack poseStack, int packedLight) {
        if (mesh instanceof VxAbstractRenderableMesh abstractMesh && abstractMesh.isDeleted()) return;
        traverseAndRender(rootNode, poseStack, packedLight);
    }

    /**
     * Recursively traverses the scene graph to render nodes with their animated transforms.
     */
    private void traverseAndRender(VxNode node, PoseStack poseStack, int packedLight) {
        poseStack.pushPose();

        // Apply local transformation (animated state)
        Matrix4f localTransform = node.getLocalTransform();
        poseStack.mulPose(localTransform);

        // Render geometry associated with this specific node
        List<VxDrawCommand> commands = nodeDrawCommands.get(node.getName());
        if (commands != null && !commands.isEmpty()) {
            if (mesh instanceof VxAbstractRenderableMesh abstractMesh) {
                // Submit a proxy to the render queue that shares the VBO but uses specific commands
                VxRenderQueue.getInstance().add(new NodeProxy(abstractMesh, commands), poseStack, packedLight);
            }
        }

        // Process children
        for (VxNode child : node.getChildren()) {
            traverseAndRender(child, poseStack, packedLight);
        }

        poseStack.popPose();
    }

    /**
     * Lightweight proxy to render a subset of the main mesh.
     */
    private static class NodeProxy extends VxAbstractRenderableMesh {
        private final VxAbstractRenderableMesh parent;

        NodeProxy(VxAbstractRenderableMesh parent, List<VxDrawCommand> commands) {
            super(commands, Collections.emptyMap());
            this.parent = parent;
        }

        @Override
        public void setupVaoState() {
            parent.setupVaoState();
        }

        @Override
        public int getFinalVertexOffset(VxDrawCommand command) {
            return parent.getFinalVertexOffset(command);
        }
        
        @Override
        public void delete() {
            // Proxy does not own resources
        }
    }
}