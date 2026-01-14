/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Wraps an OpenGL Element Buffer Object (EBO) for storing vertex indices.
 * <p>
 * This class abstracts the low-level GL calls required to allocate index memory on the GPU
 * and upload data. The engine standardizes on 32-bit indices ({@code GL_UNSIGNED_INT})
 * to support meshes and batches exceeding 65,536 vertices.
 *
 * @author xI-Mx-Ix
 */
public class VxIndexBuffer {

    /**
     * The size of a single index in bytes (32-bit integer).
     */
    public static final int BYTES_PER_INDEX = 4;

    /**
     * The OpenGL data type for indices used by this engine.
     */
    public static final int GL_INDEX_TYPE = GL15.GL_UNSIGNED_INT;

    private int eboId;
    private final boolean dynamic;
    private long capacityBytes;

    /**
     * Creates a new Index Buffer.
     *
     * @param capacityBytes The initial capacity in bytes.
     * @param dynamic       If true, uses GL_DYNAMIC_DRAW, otherwise GL_STATIC_DRAW.
     */
    public VxIndexBuffer(long capacityBytes, boolean dynamic) {
        RenderSystem.assertOnRenderThread();
        this.capacityBytes = capacityBytes;
        this.dynamic = dynamic;
        initialize();
    }

    /**
     * Initializes the GL resources.
     */
    private void initialize() {
        this.eboId = GL15.glGenBuffers();
        bind();
        // Allocate memory on the GPU (Orphaning)
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, capacityBytes, dynamic ? GL15.GL_DYNAMIC_DRAW : GL15.GL_STATIC_DRAW);
        unbind();
    }

    /**
     * Binds this buffer to the GL_ELEMENT_ARRAY_BUFFER target.
     * <p>
     * <b>Note:</b> Binding an EBO modifies the state of the currently bound Vertex Array Object (VAO).
     * Ensure the correct VAO is bound before calling this if the intent is to link them.
     */
    public void bind() {
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);
    }

    /**
     * Unbinds the buffer from the GL_ELEMENT_ARRAY_BUFFER target.
     */
    public void unbind() {
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    /**
     * Uploads index data from a ByteBuffer (raw bytes) to the buffer at a specific offset.
     *
     * @param offsetBytes The offset in the buffer to start writing.
     * @param data        The data to upload.
     */
    public void uploadSubData(long offsetBytes, ByteBuffer data) {
        bind();
        GL15.glBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, offsetBytes, data);
    }

    /**
     * Uploads index data from an IntBuffer (convenience method) to the buffer at a specific offset.
     *
     * @param offsetBytes The offset in the buffer to start writing.
     * @param data        The data to upload.
     */
    public void uploadSubData(long offsetBytes, IntBuffer data) {
        bind();
        GL15.glBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, offsetBytes, data);
    }

    /**
     * Copies data from this buffer to another buffer on the GPU using hardware copy commands.
     * Used for resizing operations.
     *
     * @param target    The destination buffer.
     * @param sizeBytes The amount of bytes to copy.
     */
    public void copyTo(VxIndexBuffer target, long sizeBytes) {
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, this.eboId);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, target.eboId);

        GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0, 0, sizeBytes);

        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
    }

    /**
     * Deletes the GL resources associated with this buffer.
     */
    public void delete() {
        RenderSystem.assertOnRenderThread();
        if (eboId != 0) GL15.glDeleteBuffers(eboId);
        eboId = 0;
    }

    public long getCapacityBytes() {
        return capacityBytes;
    }

    /**
     * Retrieves the raw OpenGL EBO ID.
     *
     * @return The EBO ID.
     */
    public int getEboId() {
        return eboId;
    }
}