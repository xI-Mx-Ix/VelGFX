/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.mesh.arena.skinning;

import com.mojang.blaze3d.vertex.PoseStack;
import net.xmx.velgfx.renderer.gl.VxDrawCommand;
import net.xmx.velgfx.renderer.gl.VxIndexBuffer;
import net.xmx.velgfx.renderer.gl.layout.VxSkinnedResultVertexLayout;
import net.xmx.velgfx.renderer.gl.mesh.IVxRenderableMesh;
import net.xmx.velgfx.renderer.gl.mesh.arena.VxArenaMesh;
import net.xmx.velgfx.renderer.gl.mesh.arena.VxMemorySegment;
import net.xmx.velgfx.renderer.pipeline.VxRenderDataStore;
import net.xmx.velgfx.renderer.pipeline.VxRenderPipeline;

import java.util.List;

/**
 * A proxy mesh that renders transformed vertices from the Skinning Arena
 * using the topology (Indices) of the original static source mesh.
 * <p>
 * This class binds the global Skinning VAO (which points to the transformed vertices)
 * but explicitly binds the Source Mesh's EBO to provide the triangle structure.
 *
 * @author xI-Mx-Ix
 */
public class VxSkinnedResultMesh implements IVxRenderableMesh {

    private final List<VxDrawCommand> drawCommands;

    /**
     * Reference to the index buffer of the source mesh.
     */
    private final VxIndexBuffer sourceIndexBuffer;

    /**
     * Reference to the index memory segment of the source mesh.
     */
    private final VxMemorySegment sourceIndexSegment;

    /**
     * The base vertex index in the global Skinning VBO.
     */
    private final int resultBaseVertex;

    private boolean isDeleted = false;

    /**
     * Constructs a result mesh proxy.
     *
     * @param resultSegment The segment in the Skinning Arena containing the transformed vertices.
     * @param sourceMesh    The original static mesh, used to retrieve topology (Indices/EBO).
     */
    public VxSkinnedResultMesh(VxMemorySegment resultSegment, VxArenaMesh sourceMesh) {
        this.drawCommands = sourceMesh.getDrawCommands();

        this.sourceIndexBuffer = sourceMesh.getSourceIndexBuffer();
        this.sourceIndexSegment = sourceMesh.getIndexSegment();

        // Calculate the absolute base vertex index in the Result VBO
        this.resultBaseVertex = (int) (resultSegment.offset / VxSkinnedResultVertexLayout.STRIDE);
    }

    @Override
    public void queueRender(PoseStack poseStack, int packedLight) {
        if (!isDeleted) {
            submitCommands(poseStack, packedLight);
        }
    }

    /**
     * Submits the stored draw commands to the render data store.
     * Resolves source index and result vertex offsets and records
     * them for batched rendering.
     *
     * @param poseStack   Provides model and normal matrices.
     * @param packedLight Packed light value for lighting.
     */
    private void submitCommands(PoseStack poseStack, int packedLight) {
        VxRenderDataStore store = VxRenderPipeline.getInstance().getStore();

        // Use the global Skinning Arena VAO, but the Source Mesh's EBO
        int vaoId = VxSkinningArena.getInstance().getSharedVaoId();
        int eboId = sourceIndexBuffer.getEboId();

        for (VxDrawCommand relativeCmd : drawCommands) {
            // Indices: Start at Source Mesh's EBO offset + Command offset
            long finalIndexOffset = this.sourceIndexSegment.offset + relativeCmd.indexOffsetBytes;

            // Vertices: Start at Result Mesh's VBO offset + Command base vertex
            int finalBaseVertex = this.resultBaseVertex + relativeCmd.baseVertex;

            // Record directly to SoA
            store.record(
                    vaoId,
                    eboId,
                    relativeCmd.indexCount,
                    finalIndexOffset,
                    finalBaseVertex,
                    relativeCmd.material,
                    poseStack.last().pose(),
                    poseStack.last().normal(),
                    packedLight,
                    0 // Default overlay UV (0) for skinned mesh rendering
            );
        }
    }

    @Override
    public VxDrawCommand resolveCommand(VxDrawCommand relativeCmd) {
        // Indices: Start at Mesh's EBO offset + Command offset
        long finalIndexOffset = this.sourceIndexSegment.offset + relativeCmd.indexOffsetBytes;

        // Vertices: Start at Mesh's Result VBO offset + Command base vertex
        int finalBaseVertex = this.resultBaseVertex + relativeCmd.baseVertex;

        return new VxDrawCommand(relativeCmd.material, relativeCmd.indexCount, finalIndexOffset, finalBaseVertex);
    }

    @Override
    public List<VxDrawCommand> getDrawCommands() {
        return drawCommands;
    }

    @Override
    public void delete() {
        isDeleted = true;
    }

    @Override
    public boolean isDeleted() {
        return isDeleted;
    }
}