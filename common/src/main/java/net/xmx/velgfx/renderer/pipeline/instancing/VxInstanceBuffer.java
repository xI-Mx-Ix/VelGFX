/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.pipeline.instancing;

import org.joml.Matrix4f;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * A streaming buffer implementation designed for Hardware Instanced Rendering.
 * <p>
 * This class manages a specific Vertex Buffer Object (VBO) that stores per-instance data
 * (such as Model Matrices, Lightmap coordinates, and Overlay indices). It handles the
 * raw memory mapping, data uploading, and the configuration of Vertex Attribute Divisors
 * required for OpenGL to interpret the data correctly per instance rather than per vertex.
 *
 * @author xI-Mx-Ix
 */
public class VxInstanceBuffer {

    /**
     * The size of a 4x4 matrix in bytes (16 floats * 4 bytes).
     */
    private static final int STRIDE_MAT4 = 16 * 4;

    /**
     * The size of the auxiliary data (packed light + overlay) in bytes (2 integers * 4 bytes).
     */
    private static final int STRIDE_AUX = 2 * 4;

    /**
     * Total bytes per instance (Model Matrix + Light/Overlay data).
     */
    private static final int BYTES_PER_INSTANCE = STRIDE_MAT4 + STRIDE_AUX;

    /**
     * The maximum number of instances this buffer can hold before needing a flush.
     * 16,384 instances * 72 bytes ~= 1.1 MB.
     */
    private static final int MAX_INSTANCES = 16384;

    private int vboId;
    private long bufferCapacity;
    private FloatBuffer bufferFloatView;
    private IntBuffer bufferIntView;
    private int instanceCount;

    // Attribute Locations (Must match the Shader and Vertex Layout)
    private static final int ATTR_MODEL_MAT_COL0 = 10;
    private static final int ATTR_LIGHT_OVERLAY = 14; // Packed ints: x=Light, y=Overlay

    /**
     * Constructs a new Instance Buffer.
     * Initializes the OpenGL buffer object.
     */
    public VxInstanceBuffer() {
        this.vboId = GL15.glGenBuffers();
        this.bufferCapacity = (long) MAX_INSTANCES * BYTES_PER_INSTANCE;

        // Allocate immutable storage for the buffer to hint driver usage.
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, bufferCapacity, GL15.GL_STREAM_DRAW);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        // Allocate off-heap memory for client-side staging
        // We allocate one large ByteBuffer and create views for Float and Int writing.
        ByteBuffer nativeBuffer = MemoryUtil.memAlloc(MAX_INSTANCES * BYTES_PER_INSTANCE);
        this.bufferFloatView = nativeBuffer.asFloatBuffer();
        this.bufferIntView = nativeBuffer.asIntBuffer();
        this.instanceCount = 0;
    }

    /**
     * Cleans up OpenGL resources and frees native memory.
     */
    public void delete() {
        if (vboId != -1) {
            GL15.glDeleteBuffers(vboId);
            vboId = -1;
        }
        if (bufferFloatView != null) {
            MemoryUtil.memFree(bufferFloatView.duplicate().rewind()); // Hack to free the underlying ByteBuffer
            bufferFloatView = null;
            bufferIntView = null;
        }
    }

    /**
     * Clears the buffer counters.
     * Should be called after a draw command to prepare for the next batch.
     */
    public void clear() {
        instanceCount = 0;
        bufferFloatView.clear();
        bufferIntView.clear();
    }

    /**
     * Adds a single instance to the buffer.
     *
     * @param modelMatrix The 4x4 Model Matrix for this instance.
     * @param packedLight The packed lightmap coordinates.
     * @param overlayUV   The packed overlay texture coordinates.
     */
    public void add(Matrix4f modelMatrix, int packedLight, int overlayUV) {
        // Interleaved layout: [Mat4 (16 floats)] [Light (1 int)] [Overlay (1 int)]

        // Write Matrix (16 floats)
        // We write directly into the buffer view.
        // Note: JOML's get(FloatBuffer) writes in column-major order, which is correct for OpenGL.
        modelMatrix.get(bufferFloatView);
        int pos = bufferFloatView.position() + 16;
        bufferFloatView.position(pos);

        // Synchronize the IntBuffer position with the FloatBuffer position
        // Since Int and Float are both 4 bytes, the indices are aligned.
        bufferIntView.position(pos);

        // Write Aux Data
        bufferIntView.put(packedLight);
        bufferIntView.put(overlayUV);

        // Advance buffers
        // We advanced 2 ints, so total 18 elements per instance.
        // bufferIntView is now at pos + 2.
        int newLimit = pos + 2;
        bufferIntView.position(newLimit);
        bufferFloatView.position(newLimit); // Keep views in sync

        instanceCount++;
    }

    /**
     * Adds a single instance using raw matrix data from an array.
     * This avoids matrix object allocation/copying if the source is already a flat array.
     *
     * @param matrices    The source array containing matrix data.
     * @param matOffset   The starting index of the matrix in the source array.
     * @param packedLight The packed lightmap coordinates.
     * @param overlayUV   The packed overlay texture coordinates.
     */
    public void add(float[] matrices, int matOffset, int packedLight, int overlayUV) {
        // Write Matrix (16 floats)
        bufferFloatView.put(matrices, matOffset, 16);

        // Sync and write ints
        int pos = bufferFloatView.position();
        bufferIntView.position(pos);
        bufferIntView.put(packedLight);
        bufferIntView.put(overlayUV);

        // Advance
        int newLimit = pos + 2;
        bufferIntView.position(newLimit);
        bufferFloatView.position(newLimit);

        instanceCount++;
    }

    /**
     * Uploads the accumulated data to the GPU.
     */
    public void upload() {
        if (instanceCount == 0) return;

        // Flip buffers for reading
        bufferFloatView.flip();

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);

        // We use glBufferSubData for updates as we are in a streaming context.
        // Ideally, we would map the buffer or use orphaning, but SubData is robust for this scale.
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, bufferFloatView);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        // Restore buffer limit for next write cycle (clear logic handles position)
        bufferFloatView.limit(bufferFloatView.capacity());
        bufferIntView.limit(bufferIntView.capacity());
    }

    /**
     * Configures the Vertex Attribute Arrays for the currently bound VAO.
     * This sets up the attribute pointers to read from this instance buffer
     * and enables the attribute divisors.
     */
    public void bindAttributes() {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);

        int stride = BYTES_PER_INSTANCE;

        // --- Model Matrix (Attributes 10-13) ---
        // A mat4 requires 4 vec4 attributes.
        for (int i = 0; i < 4; i++) {
            int loc = ATTR_MODEL_MAT_COL0 + i;
            GL20.glEnableVertexAttribArray(loc);
            // Pointer: 4 floats per column, offset = i * 16 bytes
            GL20.glVertexAttribPointer(loc, 4, GL11.GL_FLOAT, false, stride, i * 16L);
            // Divisor: 1 (Advance once per instance)
            ARBInstancedArrays.glVertexAttribDivisorARB(loc, 1);
        }

        // --- Aux Data (Attribute 14) ---
        // Contains Lightmap (Low 32) and Overlay (High 32) or two separate ints.
        // We mapped them as 2 ints: Light, Overlay.
        // We can use glVertexAttribIPointer (Integer pointer)
        int locAux = ATTR_LIGHT_OVERLAY;
        GL20.glEnableVertexAttribArray(locAux);
        // We have 2 integers starting at offset 64 (16 floats * 4 bytes)
        GL30.glVertexAttribIPointer(locAux, 2, GL11.GL_INT, stride, 64L);
        ARBInstancedArrays.glVertexAttribDivisorARB(locAux, 1);

        // Unbind array buffer to prevent accidental modification
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    /**
     * Disables the Vertex Attribute Arrays and resets divisors.
     * Must be called after drawing to clean up the VAO state.
     */
    public void unbindAttributes() {
        for (int i = 0; i < 4; i++) {
            int loc = ATTR_MODEL_MAT_COL0 + i;
            ARBInstancedArrays.glVertexAttribDivisorARB(loc, 0); // Reset divisor to 0 (per-vertex)
            GL20.glDisableVertexAttribArray(loc);
        }

        int locAux = ATTR_LIGHT_OVERLAY;
        ARBInstancedArrays.glVertexAttribDivisorARB(locAux, 0);
        GL20.glDisableVertexAttribArray(locAux);
    }

    /**
     * @return The number of instances currently recorded in the buffer.
     */
    public int getInstanceCount() {
        return instanceCount;
    }

    /**
     * @return The maximum capacity of the buffer.
     */
    public int getCapacity() {
        return MAX_INSTANCES;
    }
}