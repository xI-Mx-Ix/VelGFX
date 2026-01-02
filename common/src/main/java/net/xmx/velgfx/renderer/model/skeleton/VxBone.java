/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.skeleton;

import org.joml.Matrix4f;

/**
 * Represents a single bone within the skeletal hierarchy of a skinned model.
 * <p>
 * A bone acts as the link between the logical scene graph ({@link VxNode}) and the
 * GPU skinning pipeline. It holds the <b>Inverse Bind Matrix</b> (Offset Matrix)
 * required to transform vertices from Model Space into Bone Space.
 *
 * @author xI-Mx-Ix
 */
public class VxBone {

    /**
     * The unique index of this bone, corresponding to the index in the shader's uniform array.
     */
    private final int id;

    /**
     * The name of the bone, matching the {@link VxNode} name in the hierarchy.
     */
    private final String name;

    /**
     * The Inverse Bind Matrix.
     * Transforms vertices from the mesh's Bind Pose into the Bone's local space.
     */
    private final Matrix4f offsetMatrix;

    /**
     * The scene graph node that drives this bone's transformation.
     */
    private final VxNode node;

    /**
     * A reusable scratch matrix to hold the calculated final transform for the current frame.
     * Avoids per-frame allocation.
     */
    private final Matrix4f finalTransform = new Matrix4f();

    /**
     * Constructs a new Bone instance.
     *
     * @param id           The unique bone index (0 to MAX_BONES).
     * @param name         The name of the bone.
     * @param offsetMatrix The immutable inverse bind matrix extracted from the model file.
     * @param node         The non-null scene node controlling this bone.
     */
    public VxBone(int id, String name, Matrix4f offsetMatrix, VxNode node) {
        if (node == null) {
            throw new IllegalArgumentException("Bone '" + name + "' cannot be linked to a null node.");
        }
        this.id = id;
        this.name = name;
        this.offsetMatrix = offsetMatrix;
        this.node = node;
    }

    /**
     * Computes the final skinning matrix to be sent to the GPU.
     * <p>
     * Formula: {@code Final = GlobalInverse * NodeGlobal * OffsetMatrix}
     *
     * @param globalInverseTransform The inverse transform of the root node (normalizes model space).
     */
    public void update(Matrix4f globalInverseTransform) {
        // 1. Start with Global Inverse to remove the root's initial transform
        finalTransform.set(globalInverseTransform)
                // 2. Apply the Node's current animated global transform (Bone Space -> World)
                .mul(node.getGlobalTransform())
                // 3. Apply the Offset Matrix (Model Space -> Bone Space)
                .mul(offsetMatrix);
    }

    /**
     * Gets the computed final transformation matrix.
     *
     * @return The 4x4 matrix for the shader.
     */
    public Matrix4f getFinalTransform() {
        return finalTransform;
    }

    /**
     * Gets the name of the bone.
     *
     * @return The bone name.
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the unique index ID of the bone.
     *
     * @return The integer ID.
     */
    public int getId() {
        return id;
    }
}