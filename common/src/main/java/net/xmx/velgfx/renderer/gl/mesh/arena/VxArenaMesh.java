/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.mesh.arena;

import com.mojang.blaze3d.vertex.PoseStack;
import net.xmx.velgfx.renderer.gl.VxDrawCommand;
import net.xmx.velgfx.renderer.gl.mesh.IVxRenderableMesh;
import net.xmx.velgfx.renderer.gl.mesh.VxRenderQueue;
import net.xmx.velgfx.resources.VxTextureLoader;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a contiguous segment of geometry stored within a larger {@link VxArenaBuffer}.
 * <p>
 * This is the primary mesh implementation for the engine. It is used for both:
 * <ul>
 *     <li><b>Static Meshes:</b> Directly rendered from the arena (Shared VAO).</li>
 *     <li><b>Skinned Source Data:</b> Read by the compute shader during the skinning pass.</li>
 * </ul>
 * <p>
 * This class handles the mapping of offsets and draw commands relative to the arena's memory block.
 *
 * @author xI-Mx-Ix
 */
public class VxArenaMesh implements IVxRenderableMesh {

    protected final VxArenaBuffer parentBuffer;
    protected final long offsetBytes;
    protected final long sizeBytes;
    protected final int baseVertex;

    protected final List<VxDrawCommand> allDrawCommands;
    protected final Map<String, List<VxDrawCommand>> groupDrawCommands;

    protected boolean isDeleted = false;

    /**
     * Constructs a new Arena Mesh handle.
     *
     * @param parentBuffer      The arena buffer holding the data.
     * @param offsetBytes       The byte offset within the arena.
     * @param sizeBytes         The size of the mesh data in bytes.
     * @param allDrawCommands   The global list of draw commands.
     * @param groupDrawCommands The map of group-specific commands (for rigid body animation).
     */
    public VxArenaMesh(VxArenaBuffer parentBuffer, long offsetBytes, long sizeBytes,
                       List<VxDrawCommand> allDrawCommands, Map<String, List<VxDrawCommand>> groupDrawCommands) {
        this.parentBuffer = Objects.requireNonNull(parentBuffer, "Parent buffer cannot be null");
        this.offsetBytes = offsetBytes;
        this.sizeBytes = sizeBytes;

        // Calculate base vertex index (assuming consistent stride in the arena)
        this.baseVertex = (int) (offsetBytes / parentBuffer.getLayout().getStride());

        this.allDrawCommands = allDrawCommands;
        this.groupDrawCommands = groupDrawCommands != null ? groupDrawCommands : Collections.emptyMap();

        initializeTextures();
    }

    /**
     * Initializes texture resources for the materials used by this mesh.
     * Loads the normal map if defined, otherwise the material will generate a flat default later.
     */
    protected void initializeTextures() {
        for (VxDrawCommand command : this.allDrawCommands) {
            if (command.material != null) {
                // 1. Load Albedo
                command.material.albedoMapGlId = VxTextureLoader.getTexture(command.material.albedoMap);

                // 2. Load Normal Map (if present in the model file)
                if (command.material.normalMap != null) {
                    command.material.normalMapGlId = VxTextureLoader.getTexture(command.material.normalMap);
                }

                // 3. Generate missing maps (Flat Normal / LabPBR Specular)
                command.material.ensureGenerated();
            }
        }
    }

    @Override
    public void queueRender(PoseStack poseStack, int packedLight) {
        if (!isDeleted) {
            VxRenderQueue.getInstance().add(this, poseStack, packedLight);
        }
    }

    /**
     * Queues a specific subset of draw commands for rendering.
     * <p>
     * This creates a lightweight proxy object (GroupView) that shares the parent's
     * buffer configuration (VBO/VAO) but renders only the provided commands.
     * This is essential for models where the grouping logic is managed externally.
     *
     * @param poseStack   The transformation stack.
     * @param packedLight The light value.
     * @param commands    The specific list of draw commands to execute.
     */
    public void queueRenderSubset(PoseStack poseStack, int packedLight, List<VxDrawCommand> commands) {
        if (isDeleted || commands == null || commands.isEmpty()) return;

        // Create a temporary view of this mesh for the specific subset of commands
        VxRenderQueue.getInstance().add(new GroupView(commands), poseStack, packedLight);
    }

    @Override
    public void delete() {
        if (!isDeleted) {
            parentBuffer.free(this);
            isDeleted = true;
        }
    }

    /**
     * Configures the Vertex Array Object (VAO) state for rendering.
     * <p>
     * By default, this binds the parent arena's shared VAO.
     */
    public void setupVaoState() {
        this.parentBuffer.bindVao();
    }

    /**
     * Calculates the absolute start vertex for a draw call.
     *
     * @param command The draw command.
     * @return The absolute vertex index in the VBO.
     */
    public int getFinalVertexOffset(VxDrawCommand command) {
        return this.baseVertex + command.vertexOffset;
    }

    public List<VxDrawCommand> getDrawCommands() {
        return allDrawCommands;
    }

    public long getOffsetBytes() {
        return offsetBytes;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    /**
     * Checks if this mesh has been deleted and its resources freed.
     *
     * @return True if deleted, false otherwise.
     */
    @Override
    public boolean isDeleted() {
        return isDeleted;
    }

    /**
     * A lightweight internal proxy that presents a subset of draw commands
     * while sharing the parent's geometry configuration.
     */
    protected class GroupView extends VxArenaMesh {
        public GroupView(List<VxDrawCommand> groupCommands) {
            super(VxArenaMesh.this.parentBuffer, VxArenaMesh.this.offsetBytes,
                    VxArenaMesh.this.sizeBytes, groupCommands, Collections.emptyMap());
        }

        // Override to prevent double-freeing or invalid operations
        @Override
        public void delete() {
            // No-op
        }

        @Override
        public void setupVaoState() {
            VxArenaMesh.this.setupVaoState();
        }

        @Override
        public int getFinalVertexOffset(VxDrawCommand command) {
            return VxArenaMesh.this.getFinalVertexOffset(command);
        }
    }
}