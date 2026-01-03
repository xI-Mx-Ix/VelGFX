/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.mesh.skinning;

import com.mojang.blaze3d.vertex.PoseStack;
import net.xmx.velgfx.renderer.gl.VxDrawCommand;
import net.xmx.velgfx.renderer.gl.mesh.IVxRenderableMesh;
import net.xmx.velgfx.renderer.gl.mesh.VxRenderQueue;
import org.lwjgl.opengl.GL30;

import java.util.List;

/**
 * A specialized mesh implementation that represents the output of a hardware skinning pass.
 * <p>
 * Unlike {@link net.xmx.velgfx.renderer.gl.mesh.arena.VxArenaMesh}, this class does not manage
 * memory allocation. It simply wraps a pre-configured Result VAO and a list of draw commands.
 * It is used as a proxy to render the transformed vertices stored in a dedicated VBO.
 *
 * @author xI-Mx-Ix
 */
public class VxSkinnedResultMesh implements IVxRenderableMesh {

    private final int vaoId;
    private final List<VxDrawCommand> drawCommands;
    private boolean isDeleted = false;

    /**
     * Constructs a result mesh.
     *
     * @param vaoId        The OpenGL ID of the VAO containing the transformed vertices.
     * @param drawCommands The draw commands (reused from the source mesh).
     */
    public VxSkinnedResultMesh(int vaoId, List<VxDrawCommand> drawCommands) {
        this.vaoId = vaoId;
        this.drawCommands = drawCommands;
    }

    @Override
    public void queueRender(PoseStack poseStack, int packedLight) {
        // Add self to the queue via the interface
        VxRenderQueue.getInstance().add(this, poseStack, packedLight);
    }

    @Override
    public void setupVaoState() {
        // Bind the dedicated Result VAO
        GL30.glBindVertexArray(vaoId);
    }

    @Override
    public int getFinalVertexOffset(VxDrawCommand command) {
        // The Result VBO is dedicated to this mesh, so offsets are always relative to 0.
        return command.vertexOffset;
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