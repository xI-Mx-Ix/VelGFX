/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.mesh.arena.skinning;

import com.mojang.blaze3d.vertex.PoseStack;
import net.xmx.velgfx.renderer.gl.VxDrawCommand;
import net.xmx.velgfx.renderer.gl.layout.VxSkinnedResultVertexLayout;
import net.xmx.velgfx.renderer.gl.mesh.IVxRenderableMesh;
import net.xmx.velgfx.renderer.gl.mesh.VxRenderQueue;
import net.xmx.velgfx.renderer.gl.mesh.arena.VxArenaMesh;
import net.xmx.velgfx.renderer.gl.mesh.arena.VxMemorySegment;

import java.util.List;

/**
 * A specialized mesh implementation that represents a specific segment within the
 * global {@link VxSkinningArena}.
 * <p>
 * Unlike {@link VxArenaMesh}, this class does not
 * manage memory allocation logic itself. Instead, it acts as a lightweight proxy that:
 * <ul>
 *     <li>Binds the Arena's Shared VAO during setup.</li>
 *     <li>Calculates vertex offsets relative to the provided {@link VxMemorySegment}.</li>
 * </ul>
 * It is used to render the transformed vertices produced by the skinning compute pass.
 *
 * @author xI-Mx-Ix
 */
public class VxSkinnedResultMesh implements IVxRenderableMesh {

    /**
     * The allocated memory segment in the Arena defining where this mesh's data resides.
     */
    private final VxMemorySegment segment;

    private final List<VxDrawCommand> drawCommands;

    /**
     * The calculated base vertex index in the global Arena VBO.
     * Used to offset draw calls correctly.
     */
    private final int baseVertexIndex;

    private boolean isDeleted = false;

    /**
     * Constructs a result mesh proxy.
     *
     * @param segment      The allocated segment in the {@link VxSkinningArena}.
     * @param drawCommands The draw commands (reused from the source mesh).
     */
    public VxSkinnedResultMesh(VxMemorySegment segment, List<VxDrawCommand> drawCommands) {
        this.segment = segment;
        this.drawCommands = drawCommands;

        // Calculate the absolute vertex start index based on the byte offset.
        // Formula: SegmentOffset / BytesPerVertex
        this.baseVertexIndex = (int) (segment.offset / VxSkinnedResultVertexLayout.STRIDE);
    }

    @Override
    public void queueRender(PoseStack poseStack, int packedLight) {
        // Add self to the global render queue
        if (!isDeleted) {
            VxRenderQueue.getInstance().add(this, poseStack, packedLight);
        }
    }

    @Override
    public void setupVaoState() {
        // Bind the Shared VAO of the global Arena.
        // This VAO is configured to read from the giant buffer.
        VxSkinningArena.getInstance().bindVao();
    }

    @Override
    public int getFinalVertexOffset(VxDrawCommand command) {
        // The command's offset is relative to the start of the mesh.
        // We add the segment's base index to map it to the absolute position in the Arena VBO.
        return this.baseVertexIndex + command.vertexOffset;
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