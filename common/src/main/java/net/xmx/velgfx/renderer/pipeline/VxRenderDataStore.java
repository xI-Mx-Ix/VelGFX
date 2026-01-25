/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.pipeline;

import net.xmx.velgfx.renderer.gl.material.VxMaterial;
import net.xmx.velgfx.renderer.gl.state.VxRenderType;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A central "Structure of Arrays" (SoA) data storage for all render commands in a single frame.
 * <p>
 * This class is designed to maximize CPU cache locality by storing raw OpenGL draw arguments,
 * material references, and transformation matrices in parallel primitive arrays rather than
 * wrapping them in individual objects.
 * <p>
 * This architectural choice drastically reduces Garbage Collection pressure and improves
 * iteration speed during the rendering pass, especially for large numbers of entities.
 * <p>
 * The store also handles the logical sorting of draw calls to optimize for Instanced Rendering.
 *
 * @author xI-Mx-Ix
 */
public class VxRenderDataStore {

    /**
     * The initial capacity for the internal arrays.
     * Defaults to 4096 to handle typical scenes without immediate resizing.
     */
    private static final int INITIAL_CAPACITY = 4096;

    /**
     * The total number of draw calls currently recorded in this store.
     */
    public int count = 0;

    /**
     * The current allocated capacity of the arrays. Used for resizing logic.
     */
    private int capacity = INITIAL_CAPACITY;

    // --- Raw OpenGL Draw Arguments ---

    /**
     * Array storing the OpenGL Vertex Array Object (VAO) ID for each draw call.
     */
    public int[] vaoIds;

    /**
     * Array storing the OpenGL Element Buffer Object (EBO) ID for each draw call.
     */
    public int[] eboIds;

    /**
     * Array storing the number of indices to be rendered for each draw call.
     */
    public int[] indexCounts;

    /**
     * Array storing the byte offset within the EBO where the indices start.
     */
    public long[] indexOffsets;

    /**
     * Array storing the base vertex index to be added to every element index.
     */
    public int[] baseVertices;

    /**
     * Array storing the packed lightmap coordinates (Block Light | Sky Light).
     */
    public int[] packedLights;

    /**
     * Array storing the packed overlay texture coordinates (used for damage tinting).
     */
    public int[] overlayUVs;

    // --- Material References ---

    /**
     * Array storing indices that point to the {@link #frameMaterials} list.
     * Allows multiple draw calls to reference the same material object.
     */
    public int[] materialIndices;

    /**
     * A list of unique {@link VxMaterial} objects referenced in the current frame.
     */
    public final List<VxMaterial> frameMaterials = new ArrayList<>();

    // --- Transforms (Flattened) ---

    /**
     * A flat array containing 4x4 Model Matrices for every draw call.
     * Layout: 16 floats per matrix.
     */
    public float[] modelMatrices;

    /**
     * A flat array containing 3x3 Normal Matrices for every draw call.
     * Layout: 9 floats per matrix.
     */
    public float[] normalMatrices;

    // --- Sorting Buckets ---

    /**
     * Opaque draw calls. These are sorted by Material -> Mesh to facilitate Instancing.
     */
    public final IntList opaqueBucket = new IntList(INITIAL_CAPACITY);

    /**
     * Cutout draw calls (Alpha Test). Sorted by Material -> Mesh.
     */
    public final IntList cutoutBucket = new IntList(INITIAL_CAPACITY / 4);

    /**
     * Translucent draw calls. Sorted by Depth (Back-to-Front).
     */
    public final IntList translucentBucket = new IntList(INITIAL_CAPACITY / 4);

    /**
     * Internal comparator helper used to sort buckets for instancing optimization.
     */
    private final InstancingSorter instancingSorter = new InstancingSorter();

    /**
     * Constructs a new {@code VxRenderDataStore} and allocates the initial memory blocks.
     */
    public VxRenderDataStore() {
        allocate(INITIAL_CAPACITY);
    }

    /**
     * Allocates all internal arrays to the specified size.
     *
     * @param size The new capacity for the arrays.
     */
    private void allocate(int size) {
        this.capacity = size;
        this.vaoIds = new int[size];
        this.eboIds = new int[size];
        this.indexCounts = new int[size];
        this.indexOffsets = new long[size];
        this.baseVertices = new int[size];
        this.packedLights = new int[size];
        this.overlayUVs = new int[size];
        this.materialIndices = new int[size];

        // 16 floats per 4x4 matrix
        this.modelMatrices = new float[size * 16];
        // 9 floats per 3x3 matrix
        this.normalMatrices = new float[size * 9];
    }

    /**
     * Resets the store counters for the next frame.
     * <p>
     * This operation is O(1) regarding memory allocation; it merely resets the index counters.
     */
    public void reset() {
        this.count = 0;
        this.frameMaterials.clear();
        this.opaqueBucket.clear();
        this.cutoutBucket.clear();
        this.translucentBucket.clear();
    }

    /**
     * Records a single draw call into the SoA structure.
     * <p>
     * This method captures all state required to render a mesh and assigns it to the
     * correct sorting bucket based on its material type.
     *
     * @param vaoId       The OpenGL Vertex Array Object ID.
     * @param eboId       The OpenGL Element Buffer Object ID.
     * @param indexCount  The number of indices to render.
     * @param indexOffset The byte offset within the EBO.
     * @param baseVertex  The integer value added to each index.
     * @param material    The {@link VxMaterial} defining textures and render state.
     * @param pose        The 4x4 Model Matrix.
     * @param normal      The 3x3 Normal Matrix.
     * @param packedLight The lightmap coordinates for the mesh.
     * @param overlayUV   The overlay texture coordinates (packed int).
     */
    public void record(int vaoId, int eboId, int indexCount, long indexOffset, int baseVertex,
                       VxMaterial material, Matrix4f pose, Matrix3f normal, int packedLight, int overlayUV) {
        // Grow arrays if necessary.
        ensureCapacity(count + 1);

        int index = count;

        // Store Primitive Data
        this.vaoIds[index] = vaoId;
        this.eboIds[index] = eboId;
        this.indexCounts[index] = indexCount;
        this.indexOffsets[index] = indexOffset;
        this.baseVertices[index] = baseVertex;
        this.packedLights[index] = packedLight;
        this.overlayUVs[index] = overlayUV;

        // Store Material Reference
        this.frameMaterials.add(material);
        this.materialIndices[index] = this.frameMaterials.size() - 1;

        // Store Flattened Matrices
        // Writes directly to the array to avoid array copying.
        pose.get(this.modelMatrices, index * 16);
        normal.get(this.normalMatrices, index * 9);

        // Add to the appropriate sorting Bucket based on render type.
        if (material.renderType == VxRenderType.TRANSLUCENT) {
            translucentBucket.add(index);
        } else if (material.renderType == VxRenderType.CUTOUT) {
            cutoutBucket.add(index);
        } else {
            opaqueBucket.add(index);
        }

        count++;
    }

    /**
     * Sorts the Opaque and Cutout buckets to optimize for Instanced Rendering.
     * <p>
     * The sorting order is:
     * <ol>
     *     <li>Material (Minimize state changes, group compatible items).</li>
     *     <li>VAO ID (Group identical geometries).</li>
     *     <li>Index Offset (Group sub-meshes).</li>
     * </ol>
     * This grouping allows the {@link VxVanillaRenderer} to process long sequential runs
     * of identical meshes as a single instanced draw call.
     */
    public void sortForInstancing() {
        sortBucket(opaqueBucket);
        sortBucket(cutoutBucket);
    }

    /**
     * Sorts a specific bucket using the instancing comparator.
     *
     * @param bucket The bucket to sort.
     */
    private void sortBucket(IntList bucket) {
        if (bucket.size <= 1) return;

        // We box indices to Integer[] to use the Arrays.sort(T[], Comparator) method.
        // While primitive sorting would be faster, the overhead here is negligible compared
        // to the GPU savings from instancing.
        Integer[] indices = new Integer[bucket.size];
        for (int i = 0; i < bucket.size; i++) indices[i] = bucket.data[i];

        Arrays.sort(indices, instancingSorter);

        for (int i = 0; i < bucket.size; i++) bucket.data[i] = indices[i];
    }

    /**
     * Checks capacity and resizes arrays if needed.
     *
     * @param minCapacity The required capacity.
     */
    private void ensureCapacity(int minCapacity) {
        if (minCapacity > capacity) {
            int newCapacity = Math.max(capacity * 2, minCapacity);

            this.vaoIds = Arrays.copyOf(this.vaoIds, newCapacity);
            this.eboIds = Arrays.copyOf(this.eboIds, newCapacity);
            this.indexCounts = Arrays.copyOf(this.indexCounts, newCapacity);
            this.indexOffsets = Arrays.copyOf(this.indexOffsets, newCapacity);
            this.baseVertices = Arrays.copyOf(this.baseVertices, newCapacity);
            this.packedLights = Arrays.copyOf(this.packedLights, newCapacity);
            this.overlayUVs = Arrays.copyOf(this.overlayUVs, newCapacity);
            this.materialIndices = Arrays.copyOf(this.materialIndices, newCapacity);
            this.modelMatrices = Arrays.copyOf(this.modelMatrices, newCapacity * 16);
            this.normalMatrices = Arrays.copyOf(this.normalMatrices, newCapacity * 9);

            this.capacity = newCapacity;
        }
    }

    /**
     * Comparator for sorting draw calls to maximize instancing potential.
     */
    private class InstancingSorter implements java.util.Comparator<Integer> {
        @Override
        public int compare(Integer a, Integer b) {
            // 1. Sort by Material Identity (Reference Equality)
            // Grouping by material prevents frequent texture bindings.
            VxMaterial matA = frameMaterials.get(materialIndices[a]);
            VxMaterial matB = frameMaterials.get(materialIndices[b]);

            // We compare system identity hash codes for fast grouping.
            int matCompare = Integer.compare(System.identityHashCode(matA), System.identityHashCode(matB));
            if (matCompare != 0) return matCompare;

            // 2. Sort by Geometry (VAO)
            // Required for instancing: batches must share the same VAO.
            int vaoCompare = Integer.compare(vaoIds[a], vaoIds[b]);
            if (vaoCompare != 0) return vaoCompare;

            // 3. Sort by Index Offset
            // Keeps sub-meshes of the same buffer together.
            return Long.compare(indexOffsets[a], indexOffsets[b]);
        }
    }

    /**
     * A specialized primitive integer list implementation.
     * <p>
     * Replaces {@code ArrayList<Integer>} to avoid auto-boxing overhead during recording.
     */
    public static class IntList {
        /**
         * Backing array.
         */
        public int[] data;

        /**
         * Current logical size.
         */
        public int size = 0;

        /**
         * Constructs a new list.
         *
         * @param capacity Initial capacity.
         */
        public IntList(int capacity) {
            this.data = new int[capacity];
        }

        /**
         * Adds a value to the list, resizing if necessary.
         *
         * @param value The integer to add.
         */
        public void add(int value) {
            if (size == data.length) {
                data = Arrays.copyOf(data, size * 2);
            }
            data[size++] = value;
        }

        /**
         * Resets the size to 0.
         */
        public void clear() {
            size = 0;
        }
    }
}