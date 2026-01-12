/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.skeleton;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the complete skeletal structure of a model.
 * <p>
 * This container holds the root {@link VxNode} of the scene graph and the list of {@link VxBone}s
 * relevant for skinning. It orchestrates the update cycle of bone matrices for the shader.
 *
 * @author xI-Mx-Ix
 */
public class VxSkeleton {
    private final VxNode rootNode;
    private final List<VxBone> bones;
    private final Matrix4f globalInverseTransform;

    /**
     * Constructs a skeleton.
     *
     * @param rootNode               The root of the hierarchy.
     * @param bones                  The list of bones extracted from the mesh.
     * @param globalInverseTransform The inverse transform of the root node (from Assimp) to normalize space.
     */
    public VxSkeleton(VxNode rootNode, List<VxBone> bones, Matrix4f globalInverseTransform) {
        this.rootNode = rootNode;
        this.bones = bones;
        this.globalInverseTransform = globalInverseTransform;
    }

    /**
     * Updates and flattens the global skinning matrices for all bones into a float array.
     * <p>
     * This method expects the {@link VxNode} hierarchy to be already updated (via Animator).
     * It writes 16 floats per bone into the provided array.
     *
     * @param matrices The destination float array. Must be at least {@code bones.size() * 16}.
     */
    public void updateBoneMatrices(float[] matrices) {
        for (int i = 0; i < bones.size(); i++) {
            VxBone bone = bones.get(i);
            bone.update(globalInverseTransform);

            // Flatten the 4x4 matrix into the array at the correct offset
            bone.getFinalTransform().get(matrices, i * 16);
        }
    }

    /**
     * Creates a deep copy of the skeleton.
     * <p>
     * This includes cloning the entire node hierarchy and re-linking the bone list
     * to the new independent nodes. This is crucial for creating independent instances
     * of a model that can be animated separately.
     *
     * @return A new independent VxSkeleton.
     */
    public VxSkeleton deepCopy() {
        // 1. Deep copy the node hierarchy
        VxNode newRoot = this.rootNode.deepCopy(null);

        // 2. Re-create the bone list linked to the NEW nodes
        List<VxBone> newBones = new ArrayList<>(this.bones.size());

        for (VxBone originalBone : this.bones) {
            // Find the node in the new hierarchy that matches the bone name
            VxNode newNode = newRoot.findByName(originalBone.getName());

            if (newNode == null) {
                // Fallback (should logically not happen if hierarchy copy worked correctly)
                newNode = newRoot;
            }

            // Create a new bone instance sharing the original OffsetMatrix (immutable) but using the new Node
            newBones.add(new VxBone(
                    originalBone.getId(),
                    originalBone.getName(),
                    new Matrix4f(originalBone.getOffsetMatrix()), // Deep copy matrix
                    newNode
            ));
        }

        return new VxSkeleton(newRoot, newBones, new Matrix4f(this.globalInverseTransform));
    }

    /**
     * Gets the root node of the skeleton hierarchy.
     *
     * @return The root node.
     */
    public VxNode getRootNode() {
        return rootNode;
    }
}