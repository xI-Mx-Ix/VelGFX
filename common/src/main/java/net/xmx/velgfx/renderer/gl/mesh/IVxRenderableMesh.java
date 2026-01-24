/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.mesh;

import com.mojang.blaze3d.vertex.PoseStack;
import net.xmx.velgfx.renderer.gl.VxDrawCommand;

import java.util.List;

/**
 * Defines the contract for any mesh object that can be processed.
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
     * Resolves a relative draw command into an absolute draw command used for rendering.
     * <p>
     * Since meshes are often packed into larger Arena Buffers, the command stored within the mesh
     * usually contains offsets relative to the mesh's start. This method calculates the absolute
     * byte offsets and base vertex indices required by OpenGL to locate the data in the global buffer.
     *
     * @param command The relative draw command.
     * @return The absolute draw command ready for {@code glDrawElementsBaseVertex}.
     */
    VxDrawCommand resolveCommand(VxDrawCommand command);

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