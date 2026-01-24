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
 * wrapping them in individual objects. This approach significantly reduces garbage collection
 * pressure and improves iteration speed during the rendering pass.
 * <p>
 * The store is stateful and must be reset at the start of every frame.
 *
 * @author xI-Mx-Ix
 */
public class VxRenderDataStore {

    /**
     * The initial capacity for the internal arrays.
     * Set to a reasonably high number (4096) to avoid resizing during typical gameplay scenarios.
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
     * Array storing the base vertex index to be added to every element index during the draw call.
     */
    public int[] baseVertices;

    /**
     * Array storing the packed lightmap coordinates (SkyLight and BlockLight).
     * <p>
     * Format: 16 bits for BlockLight | 16 bits for SkyLight.
     */
    public int[] packedLights;

    // --- Material References ---

    /**
     * Array storing indices that point to the {@link #frameMaterials} list.
     * This allows multiple draw calls to reference the same material object without duplication.
     */
    public int[] materialIndices;

    /**
     * A list of unique {@link VxMaterial} objects referenced in the current frame.
     * The {@code materialIndices} array stores pointers to positions within this list.
     */
    public final List<VxMaterial> frameMaterials = new ArrayList<>();

    // --- Transforms (Flattened) ---

    /**
     * A flat array containing 4x4 Model Matrices for every draw call.
     * <p>
     * Layout: The matrix for index {@code i} starts at {@code i * 16}.
     * The data is stored in column-major order to be compatible with OpenGL upload requirements.
     */
    public float[] modelMatrices;

    /**
     * A flat array containing 3x3 Normal Matrices for every draw call.
     * <p>
     * Layout: The matrix for index {@code i} starts at {@code i * 9}.
     * This matrix is used to transform surface normals into world/view space correctly.
     */
    public float[] normalMatrices;

    // --- Sorting Buckets ---

    /**
     * A list of indices pointing to draw calls that belong to the {@link VxRenderType#OPAQUE} pass.
     * These are rendered first and write to the depth buffer.
     */
    public final IntList opaqueBucket = new IntList(INITIAL_CAPACITY);

    /**
     * A list of indices pointing to draw calls that belong to the {@link VxRenderType#CUTOUT} pass.
     * These require alpha testing (discarding fragments) but write to the depth buffer.
     */
    public final IntList cutoutBucket = new IntList(INITIAL_CAPACITY / 4);

    /**
     * A list of indices pointing to draw calls that belong to the {@link VxRenderType#TRANSLUCENT} pass.
     * These require alpha blending and depth sorting.
     */
    public final IntList translucentBucket = new IntList(INITIAL_CAPACITY / 4);

    /**
     * Constructs a new {@code VxRenderDataStore} and allocates the initial memory blocks.
     */
    public VxRenderDataStore() {
        allocate(INITIAL_CAPACITY);
    }

    /**
     * Allocates all internal arrays to the specified size.
     * This is used during initialization and when the store needs to grow.
     *
     * @param size The number of elements the arrays should be able to hold.
     */
    private void allocate(int size) {
        this.capacity = size;
        this.vaoIds = new int[size];
        this.eboIds = new int[size];
        this.indexCounts = new int[size];
        this.indexOffsets = new long[size];
        this.baseVertices = new int[size];
        this.packedLights = new int[size];
        this.materialIndices = new int[size];
        // 16 floats per 4x4 matrix
        this.modelMatrices = new float[size * 16];
        // 9 floats per 3x3 matrix
        this.normalMatrices = new float[size * 9];
    }

    /**
     * Resets the store counters for the next frame.
     * <p>
     * This method is extremely lightweight; it does not nullify arrays or release memory.
     * It simply resets the {@code count} and clears the bucket lists, allowing the existing
     * arrays to be overwritten in the next frame. This ensures zero allocation during steady-state rendering.
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
     * This method captures all necessary rendering state, flattens the transformation matrices
     * into the float arrays, and assigns the draw call to the correct render pass bucket based
     * on the material's {@link VxRenderType}.
     *
     * @param vaoId       The OpenGL Vertex Array Object ID containing the mesh data.
     * @param eboId       The OpenGL Element Buffer Object ID containing the indices.
     * @param indexCount  The number of indices to render (the triangle count * 3).
     * @param indexOffset The byte offset within the EBO where the indices begin.
     * @param baseVertex  The integer value added to each index (used for batching).
     * @param material    The {@link VxMaterial} defining textures and render state.
     * @param pose        The 4x4 Model Matrix (Position, Rotation, Scale).
     * @param normal      The 3x3 Normal Matrix (Rotation, Scale).
     * @param packedLight The lightmap coordinates for the mesh.
     */
    public void record(int vaoId, int eboId, int indexCount, long indexOffset, int baseVertex,
                       VxMaterial material, Matrix4f pose, Matrix3f normal, int packedLight) {
        ensureCapacity(count + 1);

        int index = count;

        // Store Primitive Data
        this.vaoIds[index] = vaoId;
        this.eboIds[index] = eboId;
        this.indexCounts[index] = indexCount;
        this.indexOffsets[index] = indexOffset;
        this.baseVertices[index] = baseVertex;
        this.packedLights[index] = packedLight;

        // Store Material Index
        this.frameMaterials.add(material);
        this.materialIndices[index] = this.frameMaterials.size() - 1;

        // Store Flattened Matrices (Zero Allocation)
        // We write directly into the float array to avoid creating buffer objects here.
        pose.get(this.modelMatrices, index * 16);
        normal.get(this.normalMatrices, index * 9);

        // Add to the appropriate sorting Bucket
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
     * Checks if the current arrays are large enough to hold the requested capacity.
     * If not, it triggers a resize operation, growing the arrays by a factor of 2.
     *
     * @param minCapacity The minimum required capacity.
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
            this.materialIndices = Arrays.copyOf(this.materialIndices, newCapacity);
            this.modelMatrices = Arrays.copyOf(this.modelMatrices, newCapacity * 16);
            this.normalMatrices = Arrays.copyOf(this.normalMatrices, newCapacity * 9);

            this.capacity = newCapacity;
        }
    }

    /**
     * A specialized primitive integer list implementation.
     * <p>
     * This class replaces {@code ArrayList<Integer>} to avoid the significant overhead of
     * Java object auto-boxing/unboxing when managing thousands of indices per frame.
     */
    public static class IntList {
        /**
         * The backing array of integers.
         */
        public int[] data;

        /**
         * The logical size of the list (number of valid elements).
         */
        public int size = 0;

        /**
         * Constructs a new IntList with the specified initial capacity.
         *
         * @param capacity The initial size of the backing array.
         */
        public IntList(int capacity) {
            this.data = new int[capacity];
        }

        /**
         * Adds a primitive integer to the list.
         * Resizes the backing array if necessary.
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
         * Resets the list size to zero.
         * Does not release the backing array memory.
         */
        public void clear() {
            size = 0;
        }
    }
}