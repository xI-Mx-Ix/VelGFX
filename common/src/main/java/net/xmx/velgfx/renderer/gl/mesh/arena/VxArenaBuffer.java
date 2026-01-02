/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.mesh.arena;

import com.mojang.blaze3d.systems.RenderSystem;
import net.xmx.velgfx.renderer.VelGFX;
import net.xmx.velgfx.renderer.gl.VxDrawCommand;
import net.xmx.velgfx.renderer.gl.VxVertexBuffer;
import net.xmx.velgfx.renderer.gl.layout.IVxVertexLayout;
import net.xmx.velgfx.renderer.gl.mesh.impl.VxArenaMesh;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * Manages a single, large, dynamically-growing Vertex Buffer Object (VBO) and a corresponding
 * Vertex Array Object (VAO) specifically configured for a given {@link IVxVertexLayout}.
 * <p>
 * <b>Performance Improvements:</b>
 * <ul>
 *   <li>Uses {@link VxMemorySegment} with an internal object pool to eliminate allocation during free/allocate cycles.</li>
 *   <li>Replaces {@code LinkedList} with {@code ArrayList} for better CPU cache locality.</li>
 *   <li>Implements Binary Search for deallocation to quickly locate the correct merge position.</li>
 * </ul>
 * <p>
 * This class abstracts the low-level GL calls via {@link VxVertexBuffer}.
 *
 * @author xI-Mx-Ix
 */
public class VxArenaBuffer {

    private final IVxVertexLayout layout;

    /**
     * The underlying vertex buffer wrapper that handles the actual GL calls.
     */
    private VxVertexBuffer buffer;

    /**
     * The high-water mark for the buffer, indicating the next available byte at the very end of the allocated memory.
     * Everything before this index is either used or tracked in {@link #freeSegments}.
     */
    private long usedBytes = 0;

    /**
     * A sorted list of available memory segments (gaps).
     * Using ArrayList provides fast iteration and random access for binary search.
     */
    private final ArrayList<VxMemorySegment> freeSegments = new ArrayList<>();

    /**
     * An object pool for {@link VxMemorySegment} instances.
     * This prevents creating new objects every time a mesh is deleted, significantly reducing GC pressure.
     */
    private final ArrayDeque<VxMemorySegment> segmentPool = new ArrayDeque<>();

    /**
     * Creates a new Arena Buffer for a specific layout.
     *
     * @param layout             The vertex layout definition this arena supports.
     * @param capacityInVertices The initial maximum number of vertices this buffer can hold.
     */
    public VxArenaBuffer(IVxVertexLayout layout, int capacityInVertices) {
        this.layout = layout;
        long capacityBytes = (long) capacityInVertices * layout.getStride();
        this.buffer = new VxVertexBuffer(capacityBytes, true);

        // Configure the VAO for this specific layout immediately
        configureVao();
    }

    /**
     * Configures the Vertex Array Object (VAO) associated with this buffer.
     * <p>
     * This binds the VBO and calls the layout's attribute setup method to define
     * how data is read by the GPU.
     */
    private void configureVao() {
        GL30.glBindVertexArray(buffer.getVaoId());
        buffer.bind(); // Bind VBO to ARRAY_BUFFER
        layout.setupAttributes(); // Setup pointers (e.g., glVertexAttribPointer)
        buffer.unbind();
        GL30.glBindVertexArray(0);
    }

    /**
     * Resizes the VBO to a new, larger capacity, copying all existing data.
     *
     * @param requiredBytes The minimum number of additional bytes needed.
     */
    private void resize(long requiredBytes) {
        RenderSystem.assertOnRenderThread();

        long currentCapacity = this.buffer.getCapacityBytes();
        long newCapacity = Math.max((long) (currentCapacity * 1.5), currentCapacity + requiredBytes);

        VelGFX.LOGGER.warn("Resizing VxArenaBuffer (Layout: {}) from {} to {} bytes. This may cause a brief stutter.", layout.getClass().getSimpleName(), currentCapacity, newCapacity);

        // 1. Create a new, larger buffer.
        VxVertexBuffer newBuffer = new VxVertexBuffer(newCapacity, true);

        // 2. Copy the vertex data from the old buffer to the new one using GPU-side copy.
        this.buffer.copyTo(newBuffer, this.usedBytes);

        // 3. Delete the old buffer resources.
        this.buffer.delete();

        // 4. Swap the reference to point to the new buffer.
        this.buffer = newBuffer;

        // 5. The new buffer has a new VAO/VBO ID, so we must re-configure attributes
        configureVao();
    }

    /**
     * Allocates space in the buffer for a new mesh and uploads its data.
     * <p>
     * Strategy: First-Fit. It iterates through the sorted {@code freeSegments}.
     * If a suitable gap is found, it is used (and split if too large).
     * If no gap is found, the buffer "high-water mark" is bumped.
     *
     * @param vertexData        The raw ByteBuffer containing the vertex data (must be direct).
     * @param allDrawCommands   The list of draw commands for rendering the entire mesh.
     * @param groupDrawCommands The map of group-specific draw commands for animation support.
     * @return A {@link VxArenaMesh} handle representing the mesh's data in the shared buffer.
     */
    public VxArenaMesh allocate(ByteBuffer vertexData,
                                List<VxDrawCommand> allDrawCommands,
                                Map<String, List<VxDrawCommand>> groupDrawCommands) {
        Objects.requireNonNull(vertexData, "Vertex data cannot be null");
        RenderSystem.assertOnRenderThread();

        int modelSizeBytes = vertexData.remaining();
        long allocationOffset = -1;

        // 1. Try to find a free block that fits (First-Fit).
        // Standard loop is faster than iterator for ArrayList access.
        int segmentIndex = -1;
        for (int i = 0; i < freeSegments.size(); i++) {
            VxMemorySegment segment = freeSegments.get(i);
            if (segment.size >= modelSizeBytes) {
                allocationOffset = segment.offset;
                segmentIndex = i;
                break;
            }
        }

        if (segmentIndex != -1) {
            // Found a reusable gap.
            VxMemorySegment segment = freeSegments.get(segmentIndex);

            if (segment.size > modelSizeBytes) {
                // The gap is bigger than needed. Split it.
                // We modify the segment in-place to avoid allocation.
                segment.offset += modelSizeBytes;
                segment.size -= modelSizeBytes;
            } else {
                // Exact fit. Remove the segment completely and recycle the object.
                freeSegments.remove(segmentIndex);
                recycleSegment(segment);
            }
        } else {
            // 2. No suitable gap found. Allocate from the end (Bump Allocation).
            if (this.usedBytes + modelSizeBytes > this.buffer.getCapacityBytes()) {
                resize(modelSizeBytes);
            }
            allocationOffset = this.usedBytes;
            this.usedBytes += modelSizeBytes;
        }

        // Upload the new mesh data into the determined slot.
        this.buffer.uploadSubData(allocationOffset, vertexData);

        return new VxArenaMesh(this, allocationOffset, modelSizeBytes, allDrawCommands, groupDrawCommands);
    }

    /**
     * Frees the memory associated with a sub-mesh.
     * <p>
     * This method:
     * 1. Updates the high-water mark if freeing the tail.
     * 2. Uses binary search to find the correct insertion index in {@code freeSegments}.
     * 3. Merges (coalesces) with adjacent free blocks to reduce fragmentation.
     *
     * @param subMesh The sub-mesh to deallocate.
     */
    public void free(VxArenaMesh subMesh) {
        RenderSystem.assertOnRenderThread();
        if (subMesh == null) return;

        long offset = subMesh.getOffsetBytes();
        long size = subMesh.getSizeBytes();

        // Optimization: if we are freeing the very last allocated block, simply rewind the counter.
        if (offset + size == this.usedBytes) {
            this.usedBytes = offset;

            // Check if the previous block is also free, to rewind even further.
            if (!freeSegments.isEmpty()) {
                VxMemorySegment last = freeSegments.get(freeSegments.size() - 1);
                if (last.getEnd() == this.usedBytes) {
                    this.usedBytes = last.offset;
                    freeSegments.remove(freeSegments.size() - 1);
                    recycleSegment(last);
                }
            }
            return;
        }

        // 1. Create (or reuse) a segment for this freed block.
        VxMemorySegment newSegment = obtainSegment(offset, size);

        // 2. Find insertion point to keep list sorted.
        // Collections.binarySearch returns (-(insertion point) - 1) if not found.
        int index = Collections.binarySearch(freeSegments, newSegment);

        if (index < 0) {
            index = -(index + 1);
        } else {
            // Exact match on offset should basically never happen unless double-free logic error.
            VelGFX.LOGGER.error("Double free detected in VxArenaBuffer for offset {}", offset);
            return;
        }

        // Insert the new segment at the sorted position.
        freeSegments.add(index, newSegment);

        // 3. Coalesce (Merge) Right Neighbor first (index + 1).
        if (index < freeSegments.size() - 1) {
            VxMemorySegment right = freeSegments.get(index + 1);
            if (newSegment.getEnd() == right.offset) {
                // Merge current and right.
                newSegment.size += right.size;
                freeSegments.remove(index + 1);
                recycleSegment(right);
            }
        }

        // 4. Coalesce (Merge) Left Neighbor (index - 1).
        if (index > 0) {
            VxMemorySegment left = freeSegments.get(index - 1);
            if (left.getEnd() == newSegment.offset) {
                // Merge left and current.
                left.size += newSegment.size;
                freeSegments.remove(index);
                recycleSegment(newSegment);
            }
        }
    }

    /**
     * Binds the shared VAO associated with this arena.
     * Used by meshes before rendering to establish state.
     */
    public void bindVao() {
        if (this.buffer != null) {
            GL30.glBindVertexArray(this.buffer.getVaoId());
        }
    }

    /**
     * Deletes the shared VBO and VAO and clears all memory trackers.
     */
    public void delete() {
        RenderSystem.assertOnRenderThread();
        if (this.buffer != null) {
            this.buffer.delete();
            this.buffer = null;
        }

        // Recycle all segments back to pool (optional, but good for restart cleanliness)
        for (VxMemorySegment seg : freeSegments) {
            recycleSegment(seg);
        }
        freeSegments.clear();
        this.usedBytes = 0;
    }

    public IVxVertexLayout getLayout() {
        return layout;
    }

    // --- Object Pooling for GC Efficiency ---

    /**
     * Obtains a segment instance from the pool or creates a new one.
     */
    private VxMemorySegment obtainSegment(long offset, long size) {
        if (!segmentPool.isEmpty()) {
            return segmentPool.pop().set(offset, size);
        }
        return new VxMemorySegment(offset, size);
    }

    /**
     * Returns a segment instance to the pool for reuse.
     */
    private void recycleSegment(VxMemorySegment segment) {
        segmentPool.push(segment);
    }
}