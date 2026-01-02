/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.mesh;

import net.xmx.velgfx.renderer.gl.VxDrawCommand;
import net.xmx.velgfx.renderer.gl.layout.IVxVertexLayout;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Represents the baked, GPU-ready definition of a mesh.
 * <p>
 * This class serves as the data transport between the {@link net.xmx.velgfx.renderer.model.loader.VxAssimpLoader}
 * and the {@link net.xmx.velgfx.renderer.gl.VxVertexBuffer}. It contains the interleaved byte stream
 * matching a specific {@link IVxVertexLayout}.
 *
 * @author xI-Mx-Ix
 */
public class VxMeshDefinition {

    private final ByteBuffer interleavedData;
    private final IVxVertexLayout layout;
    private final List<VxDrawCommand> allDrawCommands;
    private final Map<String, List<VxDrawCommand>> groupDrawCommands;

    /**
     * Constructs a new Mesh Definition.
     *
     * @param interleavedData   The direct ByteBuffer containing vertex data packed according to the layout.
     * @param layout            The vertex layout descriptor (Static or Skinned).
     * @param allDrawCommands   The flat list of draw commands.
     * @param groupDrawCommands The map of commands for specific model groups (nodes).
     */
    public VxMeshDefinition(ByteBuffer interleavedData,
                            IVxVertexLayout layout,
                            List<VxDrawCommand> allDrawCommands,
                            Map<String, List<VxDrawCommand>> groupDrawCommands) {
        this.interleavedData = interleavedData;
        this.layout = layout;
        this.allDrawCommands = allDrawCommands;
        this.groupDrawCommands = groupDrawCommands != null ? groupDrawCommands : Collections.emptyMap();
    }

    /**
     * Gets the interleaved vertex data.
     *
     * @return The direct ByteBuffer (read-only view recommended for consumers).
     */
    public ByteBuffer getData() {
        return interleavedData;
    }

    /**
     * Gets the layout describing the data structure.
     *
     * @return The vertex layout.
     */
    public IVxVertexLayout getLayout() {
        return layout;
    }

    /**
     * Gets the list of commands to render the full mesh.
     *
     * @return The list of draw commands.
     */
    public List<VxDrawCommand> getDrawCommands() {
        return allDrawCommands;
    }

    /**
     * Gets the map of group-specific draw commands.
     *
     * @return The group map.
     */
    public Map<String, List<VxDrawCommand>> getGroupDrawCommands() {
        return groupDrawCommands;
    }

    /**
     * Calculates the number of vertices in this definition based on the layout stride.
     *
     * @return The vertex count.
     */
    public int getVertexCount() {
        if (layout.getStride() == 0) return 0;
        return interleavedData.remaining() / layout.getStride();
    }
}