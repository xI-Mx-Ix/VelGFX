/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.mesh.arena.skinning;

import com.mojang.blaze3d.systems.RenderSystem;
import net.xmx.velgfx.VelGFX;
import net.xmx.velgfx.renderer.gl.VxVertexBuffer;
import net.xmx.velgfx.renderer.gl.layout.VxSkinnedResultVertexLayout;
import net.xmx.velgfx.renderer.gl.mesh.arena.VxMemorySegment;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Manages a single, massive Vertex Buffer Object (VBO) acting as the centralized destination for
 * all hardware skinning operations (Transform Feedback) within the engine.
 * <p>
 * This class implements an arena buffer architecture. Instead of allocating a dedicated VBO
 * and Transform Feedback Object (TFO) for every single skinned entity, this arena allocates
 * a large chunk of GPU memory upfront. Models then request dynamic segments within this buffer
 * to store their transformed vertices for the current frame.
 * <p>
 * <b>Key Features:</b>
 * <ul>
 *     <li><b>Shared Resources:</b> Maintains one VBO, one VAO, and one TFO for the entire render pass, reducing state switching overhead.</li>
 *     <li><b>Dynamic Resizing:</b> Automatically grows the GPU buffer if the number of visible skinned vertices exceeds current capacity.</li>
 *     <li><b>Ephemeral Data:</b> Since skinned vertex data is recalculated every frame, resizing discards old data without complex copying, ensuring minimal performance impact during growth.</li>
 * </ul>
 *
 * @author xI-Mx-Ix
 */
public class VxSkinningArena {

    private static VxSkinningArena instance;

    /**
     * The initial capacity of the buffer in bytes.
     * Defaults to approximately 1 million vertices (~48 MB given the 48-byte stride).
     */
    private static final long INITIAL_CAPACITY_BYTES = 1_000_000L * VxSkinnedResultVertexLayout.STRIDE;

    /**
     * The underlying vertex buffer wrapper that handles the actual GL calls.
     */
    private VxVertexBuffer buffer;

    /**
     * The Global Vertex Array Object (VAO) ID.
     * This VAO is configured once to describe the layout of the entire arena buffer.
     * All SkinnedResultMeshes share this VAO during the main render pass.
     */
    private int sharedVaoId;

    /**
     * The Global Transform Feedback Object (TFO) ID.
     * Used to capture the output of the vertex shader into the arena buffer.
     */
    private int globalTfoId;

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
     * Private constructor for singleton access.
     * Initializes the GPU resources immediately.
     */
    private VxSkinningArena() {
        initialize(INITIAL_CAPACITY_BYTES);
    }

    /**
     * Gets the singleton instance of the skinning arena.
     *
     * @return The global VxSkinningArena instance.
     */
    public static synchronized VxSkinningArena getInstance() {
        if (instance == null) {
            instance = new VxSkinningArena();
        }
        return instance;
    }

    /**
     * Initializes the GL resources (VBO, VAO, TFO).
     *
     * @param capacityBytes The size in bytes to allocate on the GPU.
     */
    private void initialize(long capacityBytes) {
        RenderSystem.assertOnRenderThread();

        // 1. Create the VBO (GL_DYNAMIC_DRAW as content changes every frame via TF)
        this.buffer = new VxVertexBuffer(capacityBytes, true);

        // 2. Create and Configure the Shared VAO
        this.sharedVaoId = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(sharedVaoId);

        // Directly bind the VBO to ARRAY_BUFFER.
        // Do NOT call this.buffer.bind(), as that would bind the internal VAO of the VxVertexBuffer,
        // effectively unbinding our sharedVaoId and leaving it empty!
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.buffer.getVboId());

        // Configure attributes using the specific result layout (Pos, Norm, UV, Tangent)
        VxSkinnedResultVertexLayout.getInstance().setupAttributes();

        // Unbind VBO from the global binding point (does not affect VAO associations)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);

        // 3. Create the Global Transform Feedback Object
        this.globalTfoId = GL40.glGenTransformFeedbacks();
    }

    /**
     * Allocates a contiguous segment of memory in the giant buffer.
     * <p>
     * <b>Strategy:</b> First-Fit. It searches for the first available gap in {@code freeSegments}
     * that is large enough. If none is found, it appends to the end. If the buffer is full,
     * it triggers a resize.
     *
     * @param sizeBytes The required size in bytes.
     * @return A {@link VxMemorySegment} representing the allocated range.
     */
    public VxMemorySegment allocate(long sizeBytes) {
        RenderSystem.assertOnRenderThread();
        // Initialize with -1 to satisfy compiler definite assignment analysis
        long allocationOffset = -1;
        int segmentIndex = -1;

        // 1. Try to find a free block that fits (First-Fit).
        for (int i = 0; i < freeSegments.size(); i++) {
            VxMemorySegment segment = freeSegments.get(i);
            if (segment.size >= sizeBytes) {
                allocationOffset = segment.offset;
                segmentIndex = i;
                break;
            }
        }

        if (segmentIndex != -1) {
            // Found a reusable gap.
            VxMemorySegment segment = freeSegments.get(segmentIndex);

            if (segment.size > sizeBytes) {
                // The gap is bigger than needed. Split it.
                // We modify the segment in-place to avoid allocation.
                segment.offset += sizeBytes;
                segment.size -= sizeBytes;
            } else {
                // Exact fit. Remove the segment completely and recycle the object.
                freeSegments.remove(segmentIndex);
                recycleSegment(segment);
            }
        } else {
            // 2. No suitable gap found. Allocate from the end (Bump Allocation).
            if (this.usedBytes + sizeBytes > this.buffer.getCapacityBytes()) {
                // Buffer is full. Resize it.
                resize(sizeBytes);
            }
            allocationOffset = this.usedBytes;
            this.usedBytes += sizeBytes;
        }

        return obtainSegment(allocationOffset, sizeBytes);
    }

    /**
     * Resizes the internal VBO to accommodate more data.
     * <p>
     * <b>Behavior:</b>
     * Since the data in this arena is the result of per-frame skinning calculations,
     * this method <b>discards</b> existing data instead of copying it. The models will
     * simply write to the new buffer during their next update pass.
     *
     * @param requiredExtraBytes The minimum additional bytes required that triggered this resize.
     */
    private void resize(long requiredExtraBytes) {
        long currentCapacity = this.buffer.getCapacityBytes();
        long newCapacity = Math.max((long) (currentCapacity * 1.5), currentCapacity + requiredExtraBytes);

        VelGFX.LOGGER.warn("Resizing VxSkinningArena from {} MB to {} MB. Old skinning data discarded.",
                currentCapacity / 1024 / 1024, newCapacity / 1024 / 1024);

        // Delete old resources to prevent memory leaks
        GL30.glDeleteVertexArrays(this.sharedVaoId);
        GL40.glDeleteTransformFeedbacks(this.globalTfoId);
        this.buffer.delete();

        // Re-initialize with the new larger capacity
        initialize(newCapacity);
    }

    /**
     * Frees the memory segment, returning it to the pool of available gaps.
     * <p>
     * This method keeps the {@code freeSegments} list sorted by offset to allow
     * efficient coalescing (merging) of adjacent free blocks in the future.
     *
     * @param segment The segment to free.
     */
    public void free(VxMemorySegment segment) {
        RenderSystem.assertOnRenderThread();
        if (segment == null) return;

        // Optimization: if we are freeing the very last allocated block, simply rewind the counter.
        if (segment.getEnd() == this.usedBytes) {
            this.usedBytes = segment.offset;
            recycleSegment(segment);

            // Check if the previous block is also free, to rewind even further
            while (!freeSegments.isEmpty()) {
                VxMemorySegment last = freeSegments.get(freeSegments.size() - 1);
                if (last.getEnd() == this.usedBytes) {
                    this.usedBytes = last.offset;
                    freeSegments.remove(freeSegments.size() - 1);
                    recycleSegment(last);
                } else {
                    break;
                }
            }
            return;
        }

        // Standard Deallocation: Insert into sorted free list
        int index = Collections.binarySearch(freeSegments, segment);
        if (index < 0) {
            index = -(index + 1);
        }

        freeSegments.add(index, segment);
    }

    /**
     * Binds the Global Transform Feedback Object and attaches the specific range of the
     * arena buffer corresponding to the provided segment.
     * <p>
     * This method must be called by the {@link net.xmx.velgfx.renderer.model.VxSkinnedModel}
     * immediately before dispatching the compute draw call.
     *
     * @param segment The memory segment where the GPU should write the transform results.
     */
    public void bindForFeedback(VxMemorySegment segment) {
        // 1. Bind the global TFO
        GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, this.globalTfoId);

        // 2. Bind the specific range of the VBO to the TFO attachment point (Index 0)
        GL30.glBindBufferRange(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0,
                buffer.getVboId(), segment.offset, segment.size);
    }

    /**
     * Unbinds the Transform Feedback Object and the Buffer Range.
     * Should be called after the skinning pass is complete.
     */
    public void unbindFeedback() {
        GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, 0);
        GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, 0);
    }

    /**
     * Retrieves the OpenGL ID of the global shared VAO.
     * Used for SoA batching.
     *
     * @return The VAO ID.
     */
    public int getSharedVaoId() {
        return sharedVaoId;
    }

    /**
     * Deletes all GL resources (VBO, VAO, TFO) and resets the singleton.
     */
    public void delete() {
        RenderSystem.assertOnRenderThread();
        if (buffer != null) buffer.delete();
        if (sharedVaoId != 0) GL30.glDeleteVertexArrays(sharedVaoId);
        if (globalTfoId != 0) GL40.glDeleteTransformFeedbacks(globalTfoId);

        buffer = null;
        sharedVaoId = 0;
        globalTfoId = 0;
        instance = null;
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