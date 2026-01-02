/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.mesh;

import com.mojang.blaze3d.vertex.PoseStack;
import net.xmx.velgfx.renderer.gl.VxDrawCommand;
import net.xmx.velgfx.resources.VxTextureLoader;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * An abstract base class for a renderable mesh.
 * <p>
 * This class acts as a data holder and submission point for the {@link VxRenderQueue}.
 * It delegates the actual drawing parameters (like VAO binding and vertex offsets) to subclasses.
 * <p>
 * It supports <b>Group Rendering</b>, allowing specific named subsets of the mesh to be
 * rendered independently (e.g., for animation) by creating lightweight proxy objects.
 * <p>
 * Updated to use {@link VxTextureLoader} for texture management, bypassing Minecraft's TextureManager
 * constraints regarding file names.
 *
 * @author xI-Mx-Ix
 */
public abstract class VxAbstractRenderableMesh implements IVxRenderableMesh {

    /**
     * The default list of commands required to render the <i>entire</i> mesh.
     */
    protected final List<VxDrawCommand> allDrawCommands;

    /**
     * A map of named groups (from the model file) to their specific draw commands.
     * Used for independent animation of parts like wheels or turrets.
     */
    protected final Map<String, List<VxDrawCommand>> groupDrawCommands;

    /**
     * Flag indicating if the GPU resources for this mesh have been released.
     */
    protected boolean isDeleted = false;

    /**
     * Constructs a new renderable mesh.
     *
     * @param allDrawCommands   The full list of commands for the whole model.
     * @param groupDrawCommands The map of group-specific commands.
     */
    protected VxAbstractRenderableMesh(List<VxDrawCommand> allDrawCommands, Map<String, List<VxDrawCommand>> groupDrawCommands) {
        this.allDrawCommands = allDrawCommands;
        this.groupDrawCommands = groupDrawCommands != null ? groupDrawCommands : Collections.emptyMap();
    }

    /**
     * Submits the <b>entire</b> mesh to the global render queue.
     * This captures the current PoseStack and Light state.
     *
     * @param poseStack   The current transformation stack.
     * @param packedLight The packed light value at the mesh's position.
     */
    @Override
    public final void queueRender(PoseStack poseStack, int packedLight) {
        if (!isDeleted) {
            VxRenderQueue.getInstance().add(this, poseStack, packedLight);
        }
    }

    /**
     * Submits only a specific named group of this mesh to the render queue.
     * <p>
     * This creates a lightweight proxy object that shares the parent's VAO/VBO logic
     * but provides a restricted list of draw commands corresponding to the requested group.
     *
     * @param poseStack   The current transformation stack.
     * @param packedLight The packed light value.
     * @param groupName   The name of the group in the OBJ file to render (case-sensitive).
     */
    public final void queueRenderGroup(PoseStack poseStack, int packedLight, String groupName) {
        if (isDeleted) return;

        List<VxDrawCommand> commands = groupDrawCommands.get(groupName);
        if (commands != null && !commands.isEmpty()) {
            // Create a temporary view of this mesh for the specific group
            VxRenderQueue.getInstance().add(new GroupProxy(this, commands), poseStack, packedLight);
        }
    }

    /**
     * Performs per-frame updates for this mesh.
     * <p>
     * This is primarily used by animated meshes (like {@link net.xmx.velgfx.renderer.gl.mesh.impl.VxSkinnedMesh})
     * to update bone matrices or physics state before rendering.
     * <p>
     * <b>Default Implementation:</b> Does nothing (no-op).
     *
     * @param dt The time elapsed since the last frame in seconds.
     */
    public void update(float dt) {
        // No-op by default for static meshes.
    }

    /**
     * Checks if the mesh has been marked as deleted.
     *
     * @return True if deleted, false otherwise.
     */
    public boolean isDeleted() {
        return isDeleted;
    }

    /**
     * Retrieves the list of draw commands associated with this mesh instance.
     * Used by the Render Queue to execute draw calls.
     *
     * @return The list of draw commands.
     */
    public List<VxDrawCommand> getDrawCommands() {
        return allDrawCommands;
    }

    /**
     * Initializes the OpenGL texture IDs for all materials used by this mesh.
     * <p>
     * This method resolves the Albedo texture from disk via the custom {@link VxTextureLoader}
     * and triggers the dynamic generation of LabPBR 1.3 textures via the material.
     */
    protected final void initializeTextures() {
        for (VxDrawCommand command : this.allDrawCommands) {
            if (command.material != null) {
                // 1. Resolve Albedo Texture (Load from Disk via Custom Loader)
                // This bypasses Minecraft's ResourceLocation validation.
                command.material.albedoMapGlId = VxTextureLoader.getTexture(command.material.albedoMap);

                // 2. Generate PBR Textures (Dynamic LabPBR 1.3)
                // Generates maps directly on GPU if not present.
                command.material.ensureGenerated();
            }
        }
    }

    // --- Abstract Methods used by the Render Queue ---

    /**
     * Binds the necessary Vertex Array Object (VAO) for this mesh.
     * This is called by the {@link VxRenderQueue} immediately before drawing the mesh.
     */
    public abstract void setupVaoState();

    /**
     * Calculates the final, absolute vertex offset for a given draw command.
     *
     * @param command The draw command for which to calculate the offset.
     * @return The absolute starting vertex index for the `glDrawArrays` call.
     */
    public abstract int getFinalVertexOffset(VxDrawCommand command);

    // --- Group Proxy Inner Class ---

    /**
     * A lightweight proxy class that masquerades as a renderable mesh.
     * It redirects VBO/VAO operations to the parent mesh but returns a specific subset of draw commands.
     * This allows parts of a single mesh to be transformed and rendered separately.
     */
    private static class GroupProxy extends VxAbstractRenderableMesh {
        private final VxAbstractRenderableMesh parent;

        /**
         * Constructs a proxy.
         *
         * @param parent        The original mesh that owns the GPU resources.
         * @param groupCommands The subset of commands to render for this proxy.
         */
        GroupProxy(VxAbstractRenderableMesh parent, List<VxDrawCommand> groupCommands) {
            // Pass the specific group commands as the "main" commands for this proxy instance.
            // We pass an empty map for subgroups because a proxy cannot be further grouped.
            super(groupCommands, Collections.emptyMap());
            this.parent = parent;
        }

        @Override
        public void setupVaoState() {
            // Delegate state setup to the parent mesh
            parent.setupVaoState();
        }

        @Override
        public int getFinalVertexOffset(VxDrawCommand command) {
            // Delegate offset calculation to the parent mesh
            return parent.getFinalVertexOffset(command);
        }

        @Override
        public void delete() {
            // No-op: The proxy does not own the resources, so it should not delete them.
        }
    }
}