/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.mesh.arena;

import com.mojang.blaze3d.vertex.PoseStack;
import net.xmx.velgfx.renderer.gl.VxDrawCommand;
import net.xmx.velgfx.renderer.gl.material.VxMaterial;
import net.xmx.velgfx.renderer.gl.mesh.IVxRenderableMesh;
import net.xmx.velgfx.renderer.pipeline.VxRenderDataStore;
import net.xmx.velgfx.renderer.pipeline.VxRenderPipeline;
import net.xmx.velgfx.renderer.util.VxGlGarbageCollector;
import net.xmx.velgfx.resources.VxTextureLoader;

import java.lang.ref.Cleaner;
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
     * Handle to the cleaner task.
     */
    protected final Cleaner.Cleanable cleanable;

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

        VxArenaBuffer bufferRef = this.parentBuffer;
        VxMemorySegment vSegRef = this.vertexSegment;
        VxMemorySegment iSegRef = this.indexSegment;

        this.cleanable = VxGlGarbageCollector.getInstance().track(this, () -> {
            // This runs on the Render Thread when the Mesh object is dead.
            bufferRef.free(vSegRef, iSegRef);
        });
    }

    /**
     * Initializes texture resources for the materials used by this mesh.
     * <p>
     * Iterates through all materials used by the draw commands and ensures their
     * OpenGL texture IDs are valid. If a GL ID is missing but a ResourceLocation is present
     * (e.g. for manually defined materials), the texture is loaded via {@link VxTextureLoader}.
     * <p>
     * This method focuses only on Albedo, Normal, and Specular maps, as secondary maps
     * like Occlusion and Emissive are assumed to be baked into these primary textures.
     */
    protected void initializeTextures() {
        for (VxDrawCommand command : this.allDrawCommands) {
            VxMaterial mat = command.material;
            if (mat != null) {
                // 1. Load Albedo (Base Color)
                if (mat.albedoMapGlId == -1 && mat.albedoMap != null) {
                    mat.albedoMapGlId = VxTextureLoader.getTexture(mat.albedoMap);
                }

                // 2. Load Normal Map
                if (mat.normalMapGlId == -1 && mat.normalMap != null) {
                    mat.normalMapGlId = VxTextureLoader.getTexture(mat.normalMap);
                }

                // 3. Load Specular Map (Metallic/Roughness/LabPBR)
                if (mat.specularMapGlId == -1 && mat.specularMap != null) {
                    mat.specularMapGlId = VxTextureLoader.getTexture(mat.specularMap);
                }

                // 4. Generate missing maps (Flat Normal / 1x1 Pixel Fallbacks)
                // This ensures the shader always has something to sample from.
                mat.ensureGenerated();
            }
        }
    }

    @Override
    public void queueRender(PoseStack poseStack, int packedLight) {
        if (!isDeleted) {
            submitCommands(allDrawCommands, poseStack, packedLight);
        }
    }

    /**
     * Queues a specific subset of draw commands for rendering.
     * <p>
     * This writes directly to the SoA data store without creating intermediate objects.
     *
     * @param poseStack   The transformation stack.
     * @param packedLight The light value.
     * @param commands    The specific list of draw commands to execute.
     */
    public void queueRenderSubset(PoseStack poseStack, int packedLight, List<VxDrawCommand> commands) {
        if (!isDeleted && commands != null && !commands.isEmpty()) {
            submitCommands(commands, poseStack, packedLight);
        }
    }

    /**
     * Submits draw commands to the render data store.
     * Resolves relative index and vertex offsets to absolute values
     * and records them for batched rendering.
     *
     * @param commands    The draw commands to submit.
     * @param poseStack   Provides model and normal matrices.
     * @param packedLight Packed light value for lighting.
     */
    private void submitCommands(List<VxDrawCommand> commands, PoseStack poseStack, int packedLight) {
        VxRenderDataStore store = VxRenderPipeline.getInstance().getStore();
        int vaoId = parentBuffer.getVaoId();
        int eboId = parentBuffer.getIndexBuffer().getEboId();

        for (VxDrawCommand cmd : commands) {
            // Calculate absolute offsets
            long finalIndexOffset = this.indexSegment.offset + cmd.indexOffsetBytes;
            int finalBaseVertex = this.baseVertexOffset + cmd.baseVertex;

            // Record directly to SoA
            store.record(
                    vaoId,
                    eboId,
                    cmd.indexCount,
                    finalIndexOffset,
                    finalBaseVertex,
                    cmd.material,
                    poseStack.last().pose(),
                    poseStack.last().normal(),
                    packedLight,
                    0 // Default overlay UV (0) for standard static mesh rendering
            );
        }
    }

    @Override
    public void delete() {
        if (!isDeleted) {
            isDeleted = true;
            // Unregister from Cleaner and run the logic immediately via the queue.
            // This prevents double-freeing because Cleanable guarantees at-most-once execution.
            cleanable.clean();
        }
    }

    @Override
    public VxDrawCommand resolveCommand(VxDrawCommand relativeCmd) {
        // Helper for Skinned Models compute pass
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

    public List<VxDrawCommand> getGroupCommands(String groupName) {
        return groupDrawCommands.get(groupName);
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
}