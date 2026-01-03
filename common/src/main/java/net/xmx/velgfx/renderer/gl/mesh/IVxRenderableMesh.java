/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.mesh;

import com.mojang.blaze3d.vertex.PoseStack;
import net.xmx.velgfx.renderer.gl.VxDrawCommand;
import java.util.List;

/**
 * Defines the contract for any mesh object that can be processed by the {@link VxRenderQueue}.
 * <p>
 * This abstraction allows the render pipeline to handle both static geometry (residing in shared Arenas)
 * and dynamic geometry (residing in dedicated Skinned Result VBOs) uniformly.
 *
 * @author xI-Mx-Ix
 */
public interface IVxRenderableMesh {

    /**
     * Submits the mesh to the global render queue.
     *
     * @param poseStack   The current transformation stack.
     * @param packedLight The packed light value.
     */
    void queueRender(PoseStack poseStack, int packedLight);

    /**
     * Prepares the OpenGL Vertex Array State for this mesh.
     * <p>
     * Called by the Render Queue immediately before drawing. This typically binds the VAO.
     */
    void setupVaoState();

    /**
     * Calculates the absolute vertex start index for a specific command.
     *
     * @param command The draw command relative to the mesh start.
     * @return The absolute index in the currently bound VBO.
     */
    int getFinalVertexOffset(VxDrawCommand command);

    /**
     * Retrieves the list of draw commands (Materials + Counts) for this mesh.
     *
     * @return The list of commands.
     */
    List<VxDrawCommand> getDrawCommands();

    /**
     * Releases GPU resources associated with this mesh.
     */
    void delete();

    /**
     * Checks if the mesh has been marked as deleted.
     *
     * @return True if deleted, false otherwise.
     */
    boolean isDeleted();
}