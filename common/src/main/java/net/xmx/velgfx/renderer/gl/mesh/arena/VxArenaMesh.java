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
 * Represents a mesh stored within an {@link VxArenaBuffer}.
 * <p>
 * This class holds references to its segments in both the Vertex Buffer and the Element Buffer.
 * It is responsible for calculating the final offsets required for {@code glDrawElementsBaseVertex}
 * by mapping local draw commands to absolute memory addresses in the Arena.
 *
 * @author xI-Mx-Ix
 */
public class VxArenaMesh implements IVxRenderableMesh {

    protected final VxArenaBuffer parentBuffer;

    /**
     * The allocated memory segment in the Vertex Buffer (VBO).
     */
    protected final VxMemorySegment vertexSegment;

    /**
     * The allocated memory segment in the Element Buffer (EBO).
     */
    protected final VxMemorySegment indexSegment;

    /**
     * The calculated base vertex offset.
     * This is the number of vertices from the start of the VBO to the start of this mesh.
     */
    protected final int baseVertexOffset;

    protected final List<VxDrawCommand> allDrawCommands;
    protected final Map<String, List<VxDrawCommand>> groupDrawCommands;

    protected boolean isDeleted = false;

    /**
     * Constructs a new Arena Mesh.
     *
     * @param parentBuffer      The parent arena.
     * @param vertexSegment     The allocated vertex memory.
     * @param indexSegment      The allocated index memory.
     * @param allDrawCommands   The draw commands with offsets relative to the mesh start.
     * @param groupDrawCommands The group commands with offsets relative to the mesh start.
     */
    public VxArenaMesh(VxArenaBuffer parentBuffer,
                       VxMemorySegment vertexSegment,
                       VxMemorySegment indexSegment,
                       List<VxDrawCommand> allDrawCommands,
                       Map<String, List<VxDrawCommand>> groupDrawCommands) {

        this.parentBuffer = Objects.requireNonNull(parentBuffer);
        this.vertexSegment = vertexSegment;
        this.indexSegment = indexSegment;

        // Calculate Base Vertex: Global Vertex Byte Offset / Stride
        // This value corresponds to the 'basevertex' parameter in glDrawElementsBaseVertex
        this.baseVertexOffset = (int) (vertexSegment.offset / parentBuffer.getLayout().getStride());

        this.allDrawCommands = allDrawCommands;
        this.groupDrawCommands = groupDrawCommands != null ? groupDrawCommands : Collections.emptyMap();

        initializeTextures();
    }

    /**
     * Initializes texture resources for the materials used by this mesh.
     * <p>
     * Iterates through all materials used by the draw commands and ensures their
     * OpenGL texture IDs are valid. If a GL ID is missing but a ResourceLocation is present
     * (e.g. for manually defined materials), the texture is loaded via {@link VxTextureLoader}.
     */
    protected void initializeTextures() {
        for (VxDrawCommand command : this.allDrawCommands) {
            if (command.material != null) {
                // 1. Load Albedo (Base Color)
                if (command.material.albedoMapGlId == -1) {
                    command.material.albedoMapGlId = VxTextureLoader.getTexture(command.material.albedoMap);
                }

                // 2. Load Normal Map
                if (command.material.normalMapGlId == -1 && command.material.normalMap != null) {
                    command.material.normalMapGlId = VxTextureLoader.getTexture(command.material.normalMap);
                }

                // 3. Load Specular Map (Metallic/Roughness)
                if (command.material.specularMapGlId == -1 && command.material.specularMap != null) {
                    command.material.specularMapGlId = VxTextureLoader.getTexture(command.material.specularMap);
                }

                // 4. Load Emissive Map
                if (command.material.emissiveMapGlId == -1 && command.material.emissiveMap != null) {
                    command.material.emissiveMapGlId = VxTextureLoader.getTexture(command.material.emissiveMap);
                }

                // 5. Load Occlusion Map
                if (command.material.occlusionMapGlId == -1 && command.material.occlusionMap != null) {
                    command.material.occlusionMapGlId = VxTextureLoader.getTexture(command.material.occlusionMap);
                }

                // 6. Generate missing maps (Flat Normal / 1x1 Pixel Fallbacks)
                // This ensures the shader always has something to sample from.
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

    @Override
    public void setupVaoState() {
        // Binds the Arena VAO which has both VBO and EBO configured correctly
        this.parentBuffer.bindVao();
    }

    @Override
    public int getFinalVertexOffset(VxDrawCommand command) {
        // Used for legacy glDrawArrays logic (e.g., skinning pass input), returns absolute vertex index
        return this.baseVertexOffset + command.baseVertex;
    }

    @Override
    public VxDrawCommand resolveCommand(VxDrawCommand relativeCmd) {
        // Absolute Index Offset = Mesh EBO Segment Start + Command Index Offset
        long finalIndexOffset = this.indexSegment.offset + relativeCmd.indexOffsetBytes;

        // Absolute Base Vertex = Mesh VBO Segment Start Index + Command Base Vertex Offset
        int finalBaseVertex = this.baseVertexOffset + relativeCmd.baseVertex;

        return new VxDrawCommand(relativeCmd.material, relativeCmd.indexCount, finalIndexOffset, finalBaseVertex);
    }

    @Override
    public List<VxDrawCommand> getDrawCommands() {
        return allDrawCommands;
    }

    public VxMemorySegment getVertexSegment() {
        return vertexSegment;
    }

    public VxMemorySegment getIndexSegment() {
        return indexSegment;
    }

    /**
     * Retrieves the index buffer associated with the parent arena.
     * Used by SkinnedResultMesh to bind the source topology.
     *
     * @return The index buffer.
     */
    public net.xmx.velgfx.renderer.gl.VxIndexBuffer getSourceIndexBuffer() {
        return parentBuffer.getIndexBuffer();
    }

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
            super(VxArenaMesh.this.parentBuffer,
                    VxArenaMesh.this.vertexSegment,
                    VxArenaMesh.this.indexSegment,
                    groupCommands, Collections.emptyMap());
        }

        @Override
        public void delete() {
            // No-op: The GroupView is ephemeral; only the parent mesh owns the memory.
        }

        @Override
        public VxDrawCommand resolveCommand(VxDrawCommand relativeCmd) {
            // Delegate resolution to parent to ensure correct offsets
            return VxArenaMesh.this.resolveCommand(relativeCmd);
        }
    }
}