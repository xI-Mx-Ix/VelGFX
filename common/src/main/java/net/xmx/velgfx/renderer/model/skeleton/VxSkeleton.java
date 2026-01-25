/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.skeleton;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Represents the skeletal hierarchy using a <b>Structure of Arrays (SoA)</b> architecture.
 * <p>
 * This class encapsulates both the static definition (topology, bind pose, names) and the
 * dynamic runtime state (local transforms, global matrices).
 * <p>
 * <b>Memory Management Strategy:</b>
 * <ul>
 *     <li><b>Static Data:</b> Arrays like {@code parentIndices}, {@code names}, and {@code inverseBindMatrices}
 *     are shared by reference across all instances of the same model type. They are treated as immutable
 *     after the skeleton is loaded.</li>
 *     <li><b>Dynamic Data:</b> Arrays like {@code localPositions} and {@code globalMatrices} are allocated
 *     uniquely for each instance to allow independent animation of entities.</li>
 * </ul>
 * <p>
 * <b>Topological Sorting:</b><br>
 * The data in these arrays is strictly sorted such that a parent bone always appears at a lower
 * index than any of its children. This allows global world-space matrices to be calculated
 * in a single linear pass (0 to N) without recursion.
 *
 * @author xI-Mx-Ix
 */
public class VxSkeleton {

    // Static Definition Data (Shared by Reference)

    /**
     * The total number of bones/nodes in this skeleton.
     */
    public final int boneCount;

    /**
     * The indices of the parent for each bone.
     * <p>
     * {@code parentIndices[i]} gives the index of the parent of bone {@code i}.
     * A value of {@code -1} indicates that the bone is a root node (no parent).
     * <p>
     * <b>Invariant:</b> {@code parentIndices[i] < i} (except for roots).
     */
    public final int[] parentIndices;

    /**
     * The names of the bones, used primarily for debugging and socket attachment lookups.
     * <p>
     * Index {@code i} corresponds to the bone at index {@code i}.
     */
    public final String[] names;

    /**
     * The Inverse Bind Matrices (IBM) required for skinning.
     * <p>
     * This is a flattened float array containing 4x4 matrices.
     * <ul>
     *     <li><b>Stride:</b> 16 floats per bone.</li>
     *     <li><b>Layout:</b> Column-major order (OpenGL standard).</li>
     *     <li><b>Function:</b> Transforms vertices from Mesh Space to Bone Local Space.</li>
     * </ul>
     * If a node is part of the hierarchy but not a skinning bone, its entry is usually the Identity matrix.
     */
    public final float[] inverseBindMatrices;

    /**
     * Reference local positions (Bind Pose Translate).
     * <p>
     * <b>Stride:</b> 3 (x, y, z).
     * Used to reset the skeleton to its resting state.
     */
    public final float[] refPositions;

    /**
     * Reference local rotations (Bind Pose Quaternion).
     * <p>
     * <b>Stride:</b> 4 (x, y, z, w).
     * Used to reset the skeleton to its resting state.
     */
    public final float[] refRotations;

    /**
     * Reference local scales (Bind Pose Scale).
     * <p>
     * <b>Stride:</b> 3 (x, y, z).
     * Used to reset the skeleton to its resting state.
     */
    public final float[] refScales;

    // Dynamic Runtime State (Unique per Instance)

    /**
     * Current Local Positions.
     * <p>
     * <b>Stride:</b> 3 (x, y, z).
     * Modified by the {@link net.xmx.velgfx.renderer.model.animation.VxAnimator} every frame.
     */
    private final float[] localPositions;

    /**
     * Current Local Rotations.
     * <p>
     * <b>Stride:</b> 4 (x, y, z, w).
     * Modified by the {@link net.xmx.velgfx.renderer.model.animation.VxAnimator} every frame.
     */
    private final float[] localRotations;

    /**
     * Current Local Scales.
     * <p>
     * <b>Stride:</b> 3 (x, y, z).
     * Modified by the {@link net.xmx.velgfx.renderer.model.animation.VxAnimator} every frame.
     */
    private final float[] localScales;

    /**
     * Calculated Global Matrices (Model Space).
     * <p>
     * Represents the absolute transform of the bone relative to the model root.
     * Calculated as: {@code Global = ParentGlobal * Local}.
     * <p>
     * <b>Stride:</b> 16 (4x4 Matrix, Column-Major).
     */
    private final float[] globalMatrices;

    /**
     * Calculated Skinning Matrices (Ready for Shader upload).
     * <p>
     * Represents the transform from Mesh Bind Space to Current Model Space.
     * Calculated as: {@code Final = Global * InverseBindMatrix}.
     * <p>
     * <b>Stride:</b> 16 (4x4 Matrix, Column-Major).
     */
    private final float[] skinningMatrices;

    // Scratchpad Variables (Reused to avoid GC)

    // Pre-allocated reusable objects to avoid garbage generation during matrix math.
    private final Matrix4f scratchLocal = new Matrix4f();
    private final Matrix4f scratchGlobal = new Matrix4f();
    private final Matrix4f scratchParent = new Matrix4f();
    private final Quaternionf scratchRot = new Quaternionf();
    private final Vector3f scratchPos = new Vector3f();
    private final Vector3f scratchScale = new Vector3f();

    /**
     * Master Constructor.
     * <p>
     * Used by the Loader to create the initial "Template" skeleton.
     * This constructor allocates all arrays (both static and dynamic) and sets the
     * initial state to the Bind Pose.
     *
     * @param boneCount           The total number of bones.
     * @param parentIndices       The topology array (must be topologically sorted).
     * @param names               The names of the bones.
     * @param inverseBindMatrices The flattened IBM array (size: boneCount * 16).
     * @param refPositions        The flattened Bind Pose positions (size: boneCount * 3).
     * @param refRotations        The flattened Bind Pose rotations (size: boneCount * 4).
     * @param refScales           The flattened Bind Pose scales (size: boneCount * 3).
     */
    public VxSkeleton(int boneCount, int[] parentIndices, String[] names,
                      float[] inverseBindMatrices,
                      float[] refPositions, float[] refRotations, float[] refScales) {
        // Assign Shared References (Static Definition)
        this.boneCount = boneCount;
        this.parentIndices = parentIndices;
        this.names = names;
        this.inverseBindMatrices = inverseBindMatrices;
        this.refPositions = refPositions;
        this.refRotations = refRotations;
        this.refScales = refScales;

        // Allocate memory for Dynamic State (Unique to this instance)
        this.localPositions = new float[boneCount * 3];
        this.localRotations = new float[boneCount * 4];
        this.localScales = new float[boneCount * 3];
        this.globalMatrices = new float[boneCount * 16];
        this.skinningMatrices = new float[boneCount * 16];

        // Initialize state to Bind Pose and perform first calculation
        resetToBindPose();
        updateMatrices();
    }

    /**
     * Copy Constructor (Instancing).
     * <p>
     * Creates a new skeleton instance that shares the <b>static arrays</b> with the template
     * (topology, names, bind pose) by reference, but allocates new memory for <b>dynamic state</b>.
     * <p>
     * This is the standard way to spawn multiple entities sharing the same model asset without
     * duplicating the heavy definition data.
     *
     * @param template The skeleton to instance from.
     */
    public VxSkeleton(VxSkeleton template) {
        // Share Static Data (References) - Zero allocation
        this.boneCount = template.boneCount;
        this.parentIndices = template.parentIndices;
        this.names = template.names;
        this.inverseBindMatrices = template.inverseBindMatrices;
        this.refPositions = template.refPositions;
        this.refRotations = template.refRotations;
        this.refScales = template.refScales;

        // Allocate new memory for Dynamic Data
        this.localPositions = new float[boneCount * 3];
        this.localRotations = new float[boneCount * 4];
        this.localScales = new float[boneCount * 3];
        this.globalMatrices = new float[boneCount * 16];
        this.skinningMatrices = new float[boneCount * 16];

        // Initialize state to Bind Pose and perform first calculation
        resetToBindPose();
        updateMatrices();
    }

    /**
     * Resets the local transform state to the static Bind Pose.
     * <p>
     * This performs a fast memory copy from the reference arrays to the dynamic arrays,
     * effectively clearing any animation applied to the skeleton.
     */
    public void resetToBindPose() {
        System.arraycopy(refPositions, 0, this.localPositions, 0, localPositions.length);
        System.arraycopy(refRotations, 0, this.localRotations, 0, localRotations.length);
        System.arraycopy(refScales, 0, this.localScales, 0, localScales.length);
    }

    /**
     * Recalculates the Global Matrices and Skinning Matrices based on the current Local state.
     * <p>
     * This method iterates linearly through the bones. Because the {@code parentIndices} array
     * guarantees a topological sort, we are guaranteed that a parent's global matrix
     * has already been computed before we process its children.
     * <p>
     * This linear access pattern is highly cache-efficient compared to tree traversal.
     */
    public void updateMatrices() {
        for (int i = 0; i < boneCount; i++) {
            // 1. Construct Local Matrix from flat arrays
            int p3 = i * 3;
            int p4 = i * 4;

            scratchPos.set(localPositions[p3], localPositions[p3 + 1], localPositions[p3 + 2]);
            scratchRot.set(localRotations[p4], localRotations[p4 + 1], localRotations[p4 + 2], localRotations[p4 + 3]);
            scratchScale.set(localScales[p3], localScales[p3 + 1], localScales[p3 + 2]);

            // Combine TRS into the scratch matrix
            scratchLocal.translationRotateScale(scratchPos, scratchRot, scratchScale);

            // 2. Multiply with Parent Global Matrix
            int parentIndex = parentIndices[i];
            if (parentIndex != -1) {
                // Read parent matrix from the ALREADY COMPUTED portion of the globalMatrices array.
                // The parentIndex is guaranteed to be < i due to topological sort.
                scratchParent.set(globalMatrices, parentIndex * 16);

                // Global = Parent * Local
                scratchParent.mul(scratchLocal, scratchGlobal);
            } else {
                // Root node: Global is just Local
                scratchGlobal.set(scratchLocal);
            }

            // 3. Store Global Matrix back into the SoA
            scratchGlobal.get(globalMatrices, i * 16);

            // 4. Compute Skinning Matrix (Global * IBM)
            // Reuse scratchLocal to load the IBM from the array to avoid allocation
            scratchLocal.set(inverseBindMatrices, i * 16);
            scratchGlobal.mul(scratchLocal, scratchGlobal);

            // Store final skinning matrix
            scratchGlobal.get(skinningMatrices, i * 16);
        }
    }

    /**
     * Copies the current local state (TRS) from another skeleton instance.
     * <p>
     * This is useful for synchronizing animation states between entities or
     * applying a specific pose from a master instance.
     *
     * @param other The source skeleton (must share the same definition/structure).
     * @throws IllegalArgumentException If the definitions do not match.
     */
    public void copyFrom(VxSkeleton other) {
        // Simple sanity check: compare the reference array pointers (identity check)
        if (this.names != other.names) {
            throw new IllegalArgumentException("Cannot copy from a skeleton with a different structure definition.");
        }
        System.arraycopy(other.localPositions, 0, this.localPositions, 0, localPositions.length);
        System.arraycopy(other.localRotations, 0, this.localRotations, 0, localRotations.length);
        System.arraycopy(other.localScales, 0, this.localScales, 0, localScales.length);

        // Note: Matrices are considered dirty and will be updated on the next updateMatrices() call.
    }

    /**
     * Finds the numeric index of a bone by its name.
     * <p>
     * This performs a linear search. For performance-critical code (like Sockets),
     * this index should be cached once during initialization.
     *
     * @param name The name of the bone to search for.
     * @return The index of the bone, or -1 if no bone with that name exists.
     */
    public int indexOf(String name) {
        if (name == null) return -1;
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) return i;
        }
        return -1;
    }

    /**
     * Retrieves the computed skinning matrices for GPU upload.
     *
     * @return The flat float array containing all 4x4 matrices (Size: 16 * boneCount).
     */
    public float[] getSkinningMatrices() {
        return skinningMatrices;
    }

    /**
     * Reads the global transformation of a specific bone into a JOML Matrix.
     * <p>
     * This is used by external systems (like Sockets) to attach items to bones.
     *
     * @param boneIndex The index of the bone to read.
     * @param dest      The matrix to store the result in.
     */
    public void getGlobalTransform(int boneIndex, Matrix4f dest) {
        if (boneIndex >= 0 && boneIndex < boneCount) {
            dest.set(globalMatrices, boneIndex * 16);
        } else {
            dest.identity();
        }
    }

    // SoA Accessors (Used by VxAnimator)

    /**
     * Sets the local translation for a specific bone.
     *
     * @param index The bone index.
     * @param x     X component.
     * @param y     Y component.
     * @param z     Z component.
     */
    public void setLocalTranslation(int index, float x, float y, float z) {
        int i = index * 3;
        localPositions[i] = x;
        localPositions[i + 1] = y;
        localPositions[i + 2] = z;
    }

    /**
     * Sets the local rotation (Quaternion) for a specific bone.
     *
     * @param index The bone index.
     * @param x     X component.
     * @param y     Y component.
     * @param z     Z component.
     * @param w     W component.
     */
    public void setLocalRotation(int index, float x, float y, float z, float w) {
        int i = index * 4;
        localRotations[i] = x;
        localRotations[i + 1] = y;
        localRotations[i + 2] = z;
        localRotations[i + 3] = w;
    }

    /**
     * Sets the local scale for a specific bone.
     *
     * @param index The bone index.
     * @param x     X component.
     * @param y     Y component.
     * @param z     Z component.
     */
    public void setLocalScale(int index, float x, float y, float z) {
        int i = index * 3;
        localScales[i] = x;
        localScales[i + 1] = y;
        localScales[i + 2] = z;
    }

    /**
     * Reads the local translation of a specific bone into a vector.
     *
     * @param index The bone index.
     * @param dest  The vector to store the result.
     */
    public void getLocalTranslation(int index, Vector3f dest) {
        int i = index * 3;
        dest.set(localPositions[i], localPositions[i + 1], localPositions[i + 2]);
    }

    /**
     * Reads the local rotation of a specific bone into a quaternion.
     *
     * @param index The bone index.
     * @param dest  The quaternion to store the result.
     */
    public void getLocalRotation(int index, Quaternionf dest) {
        int i = index * 4;
        dest.set(localRotations[i], localRotations[i + 1], localRotations[i + 2], localRotations[i + 3]);
    }

    /**
     * Reads the local scale of a specific bone into a vector.
     *
     * @param index The bone index.
     * @param dest  The vector to store the result.
     */
    public void getLocalScale(int index, Vector3f dest) {
        int i = index * 3;
        dest.set(localScales[i], localScales[i + 1], localScales[i + 2]);
    }
}