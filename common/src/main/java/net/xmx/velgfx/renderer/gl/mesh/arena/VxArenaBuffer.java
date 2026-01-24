/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.mesh.arena;

import com.mojang.blaze3d.systems.RenderSystem;
import net.xmx.velgfx.renderer.VelGFX;
import net.xmx.velgfx.renderer.gl.VxDrawCommand;
import net.xmx.velgfx.renderer.gl.VxIndexBuffer;
import net.xmx.velgfx.renderer.gl.VxVertexBuffer;
import net.xmx.velgfx.renderer.gl.layout.IVxVertexLayout;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * Manages a paired Vertex Buffer Object (VBO) and Element Buffer Object (EBO) for efficient
 * batch rendering.
 * <p>
 * This class implements a dual-allocator system. It manages two separate free-lists:
 * one for the vertex memory and one for the index memory. This decoupling allows efficient
 * packing of meshes with varying vertex-to-triangle ratios.
 * <p>
 * It automatically resizes the GPU buffers when space is exhausted, preserving existing data
 * via GPU-side copy operations.
 *
 * @author xI-Mx-Ix
 */
public class VxArenaBuffer {

    private final IVxVertexLayout layout;

    /**
     * The vertex buffer containing geometry attributes (Position, Normal, UV, etc.).
     */
    private VxVertexBuffer vertexBuffer;

    /**
     * The element buffer containing mesh topology indices.
     */
    private VxIndexBuffer indexBuffer;

    // --- Allocators ---
    private final Allocator vertexAllocator;
    private final Allocator indexAllocator;

    /**
     * Creates a new Arena Buffer for a specific layout.
     *
     * @param layout             The vertex layout definition this arena supports.
     * @param capacityInVertices The initial capacity in number of vertices.
     * @param capacityInIndices  The initial capacity in number of indices.
     */
    public VxArenaBuffer(IVxVertexLayout layout, int capacityInVertices, int capacityInIndices) {
        this.layout = layout;

        long vBytes = (long) capacityInVertices * layout.getStride();
        long iBytes = (long) capacityInIndices * VxIndexBuffer.BYTES_PER_INDEX;

        this.vertexBuffer = new VxVertexBuffer(vBytes, true);
        this.indexBuffer = new VxIndexBuffer(iBytes, true);

        this.vertexAllocator = new Allocator(vBytes);
        this.indexAllocator = new Allocator(iBytes);

        configureVao();
    }

    /**
     * Configures the Vertex Array Object (VAO) associated with this buffer.
     * <p>
     * This links both the VBO (for attributes) and the EBO (for indices) to the VAO state.
     * The Vertex Array Object stores the format of the vertex data as well as the
     * buffer objects providing the data.
     */
    private void configureVao() {
        // Bind the specific VAO managed by this arena to update its state.
        GL30.glBindVertexArray(vertexBuffer.getVaoId());

        // Bind the Vertex Buffer Object (VBO) to the GL_ARRAY_BUFFER target.
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer.getVboId());

        // Apply the vertex layout definitions (enable arrays and define pointers).
        layout.setupAttributes();

        // Bind the Element Buffer Object (EBO).
        indexBuffer.bind();

        // Unbind the VAO to prevent further accidental modification.
        // At this point, the VAO captures the attribute pointers and the EBO binding.
        GL30.glBindVertexArray(0);

        // Clean up global state by unbinding the VBO and EBO.
        // Note: Unbinding the EBO here does not affect the VAO, as the VAO is no longer active.
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        indexBuffer.unbind();
    }

    /**
     * Allocates space for a mesh in both the vertex and index buffers and uploads the data.
     *
     * @param vertexData        The raw ByteBuffer containing vertex data.
     * @param indexData         The raw ByteBuffer containing index data.
     * @param allDrawCommands   The list of draw commands relative to the mesh start.
     * @param groupDrawCommands The map of group-specific draw commands.
     * @return A {@link VxArenaMesh} handle representing the mesh's data in the shared buffers.
     */
    public VxArenaMesh allocate(ByteBuffer vertexData, ByteBuffer indexData,
                                List<VxDrawCommand> allDrawCommands,
                                Map<String, List<VxDrawCommand>> groupDrawCommands) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(vertexData, "Vertex data cannot be null");
        Objects.requireNonNull(indexData, "Index data cannot be null");

        long vSize = vertexData.remaining();
        long iSize = indexData.remaining();

        // 1. Check and Resize Vertex Buffer if needed
        if (!vertexAllocator.canAllocate(vSize)) {
            resizeVertexBuffer(vSize);
        }
        // 2. Check and Resize Index Buffer if needed
        if (!indexAllocator.canAllocate(iSize)) {
            resizeIndexBuffer(iSize);
        }

        // 3. Allocate segments using First-Fit strategy
        VxMemorySegment vSeg = vertexAllocator.allocate(vSize);
        VxMemorySegment iSeg = indexAllocator.allocate(iSize);

        // 4. Upload Data
        this.vertexBuffer.uploadSubData(vSeg.offset, vertexData);
        this.indexBuffer.uploadSubData(iSeg.offset, indexData);

        // 5. Create Mesh Handle
        return new VxArenaMesh(this, vSeg, iSeg, allDrawCommands, groupDrawCommands);
    }

    /**
     * Frees the memory segments directly.
     * <p>
     * This method is used by the Garbage Collector to release memory when the
     * owning {@link VxArenaMesh} is phantom-reachable, avoiding strong references
     * to the mesh itself.
     *
     * @param vSeg The vertex memory segment to free.
     * @param iSeg The index memory segment to free.
     */
    public void free(VxMemorySegment vSeg, VxMemorySegment iSeg) {
        RenderSystem.assertOnRenderThread();
        if (vSeg != null) vertexAllocator.free(vSeg);
        if (iSeg != null) indexAllocator.free(iSeg);
    }

    /**
     * Resizes the Vertex Buffer Object (VBO).
     *
     * @param requiredExtraBytes The additional bytes needed.
     */
    private void resizeVertexBuffer(long requiredExtraBytes) {
        long currentCapacity = vertexBuffer.getCapacityBytes();
        long newCapacity = Math.max((long) (currentCapacity * 1.5), currentCapacity + requiredExtraBytes);

        VelGFX.LOGGER.warn("Resizing Arena VBO from {} to {} bytes.", currentCapacity, newCapacity);

        VxVertexBuffer newBuffer = new VxVertexBuffer(newCapacity, true);
        this.vertexBuffer.copyTo(newBuffer, vertexAllocator.usedBytes);
        this.vertexBuffer.delete();
        this.vertexBuffer = newBuffer;

        vertexAllocator.grow(newCapacity);
        configureVao(); // Re-link new VBO to VAO
    }

    /**
     * Resizes the Element Buffer Object (EBO).
     *
     * @param requiredExtraBytes The additional bytes needed.
     */
    private void resizeIndexBuffer(long requiredExtraBytes) {
        long currentCapacity = indexBuffer.getCapacityBytes();
        long newCapacity = Math.max((long) (currentCapacity * 1.5), currentCapacity + requiredExtraBytes);

        VelGFX.LOGGER.warn("Resizing Arena EBO from {} to {} bytes.", currentCapacity, newCapacity);

        VxIndexBuffer newBuffer = new VxIndexBuffer(newCapacity, true);
        this.indexBuffer.copyTo(newBuffer, indexAllocator.usedBytes);
        this.indexBuffer.delete();
        this.indexBuffer = newBuffer;

        indexAllocator.grow(newCapacity);
        configureVao(); // Re-link new EBO to VAO
    }

    /**
     * Binds the shared VAO associated with this arena.
     */
    public void bindVao() {
        GL30.glBindVertexArray(vertexBuffer.getVaoId());
    }

    /**
     * Gets the OpenGL ID of the Vertex Array Object (VAO).
     *
     * @return The VAO ID.
     */
    public int getVaoId() {
        return vertexBuffer.getVaoId();
    }

    public VxIndexBuffer getIndexBuffer() {
        return indexBuffer;
    }

    public IVxVertexLayout getLayout() {
        return layout;
    }

    /**
     * Deletes the GL resources.
     */
    public void delete() {
        RenderSystem.assertOnRenderThread();
        if (vertexBuffer != null) vertexBuffer.delete();
        if (indexBuffer != null) indexBuffer.delete();
    }

    /**
     * Internal helper class encapsulating the First-Fit allocation logic for a single buffer.
     */
    private static class Allocator {
        private final ArrayList<VxMemorySegment> freeSegments = new ArrayList<>();
        private long usedBytes = 0;
        private long capacity;

        public Allocator(long capacity) {
            this.capacity = capacity;
        }

        public boolean canAllocate(long size) {
            // Check if we have a gap large enough
            for (VxMemorySegment seg : freeSegments) {
                if (seg.size >= size) return true;
            }
            // Check if we have space at the end
            return (usedBytes + size) <= capacity;
        }

        public void grow(long newCapacity) {
            this.capacity = newCapacity;
        }

        public VxMemorySegment allocate(long size) {
            // 1. First-Fit Strategy
            for (int i = 0; i < freeSegments.size(); i++) {
                VxMemorySegment seg = freeSegments.get(i);
                if (seg.size >= size) {
                    long offset = seg.offset;
                    if (seg.size > size) {
                        // Split segment
                        seg.offset += size;
                        seg.size -= size;
                    } else {
                        // Exact fit
                        freeSegments.remove(i);
                    }
                    return new VxMemorySegment(offset, size);
                }
            }
            // 2. Bump Allocation (Append)
            long offset = usedBytes;
            usedBytes += size;
            return new VxMemorySegment(offset, size);
        }

        public void free(VxMemorySegment segment) {
            if (segment == null) return;

            // Optimization: If freeing tail, just move pointer back
            if (segment.getEnd() == usedBytes) {
                usedBytes = segment.offset;
                // Coalesce backward with other free segments at the end
                while (!freeSegments.isEmpty()) {
                    VxMemorySegment last = freeSegments.get(freeSegments.size() - 1);
                    if (last.getEnd() == usedBytes) {
                        usedBytes = last.offset;
                        freeSegments.remove(freeSegments.size() - 1);
                    } else {
                        break;
                    }
                }
                return;
            }

            // Standard Deallocation: Insert sorted
            int idx = Collections.binarySearch(freeSegments, segment);
            if (idx < 0) idx = -(idx + 1);
            freeSegments.add(idx, segment);

            // Merge Right Neighbor
            if (idx < freeSegments.size() - 1) {
                VxMemorySegment right = freeSegments.get(idx + 1);
                if (segment.getEnd() == right.offset) {
                    segment.size += right.size;
                    freeSegments.remove(idx + 1);
                }
            }
            // Merge Left Neighbor
            if (idx > 0) {
                VxMemorySegment left = freeSegments.get(idx - 1);
                if (left.getEnd() == segment.offset) {
                    left.size += segment.size;
                    freeSegments.remove(idx);
                }
            }
        }
    }
}