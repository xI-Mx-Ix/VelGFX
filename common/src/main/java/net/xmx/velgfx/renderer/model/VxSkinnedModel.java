/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model;

import com.mojang.blaze3d.vertex.PoseStack;
import net.xmx.velgfx.renderer.gl.mesh.impl.VxSkinnedMesh;
import net.xmx.velgfx.renderer.model.animation.VxAnimation;
import net.xmx.velgfx.renderer.model.skeleton.VxSkeleton;

import java.util.Map;

/**
 * A model that supports hardware vertex skinning.
 * <p>
 * This model uses {@link net.xmx.velgfx.renderer.gl.layout.VxSkinnedVertexLayout}.
 * Geometry deformation is handled by the GPU via Transform Feedback in {@link VxSkinnedMesh}.
 *
 * @author xI-Mx-Ix
 */
public class VxSkinnedModel extends VxModel {

    private final VxSkeleton skeleton;

    public VxSkinnedModel(VxSkeleton skeleton, VxSkinnedMesh mesh, Map<String, VxAnimation> animations) {
        super(skeleton.getRootNode(), mesh, animations);
        this.skeleton = skeleton;
    }

    @Override
    public void update(float dt) {
        // Update the node hierarchy (CPU)
        super.update(dt);
        
        // Update the skeleton matrices and trigger GPU skinning pass
        if (mesh instanceof VxSkinnedMesh skinnedMesh) {
            skinnedMesh.update(dt); 
        }
    }

    @Override
    public void render(PoseStack poseStack, int packedLight) {
        mesh.queueRender(poseStack, packedLight);
    }
    
    public VxSkeleton getSkeleton() {
        return skeleton;
    }
}