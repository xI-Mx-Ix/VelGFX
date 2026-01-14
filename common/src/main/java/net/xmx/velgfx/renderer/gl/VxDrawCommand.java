/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl;

import net.xmx.velgfx.renderer.gl.material.VxMaterial;

/**
 * Represents a single, self-contained draw call for a portion of a mesh using indexed rendering.
 * <p>
 * This command maps directly to the arguments required by {@code glDrawElementsBaseVertex}.
 * It decouples the topology (indices) from the memory location (base vertex), allowing
 * geometry to be relocated in memory without rewriting index buffers.
 *
 * @author xI-Mx-Ix
 */
public class VxDrawCommand {
    /**
     * The material to apply for this draw call.
     */
    public final VxMaterial material;

    /**
     * The number of indices to render (i.e., the number of vertices defined by the topology).
     * <p>
     * Corresponds to the {@code count} parameter in {@code glDrawElementsBaseVertex}.
     */
    public final int indexCount;

    /**
     * The byte offset into the Element Buffer Object (EBO) where the indices for this command start.
     * <p>
     * Corresponds to the {@code indices} (offset) parameter in {@code glDrawElementsBaseVertex}.
     * Note: This is a byte offset, not an index index.
     */
    public final long indexOffsetBytes;

    /**
     * The constant value added to each index read from the EBO to get the final vertex index.
     * <p>
     * Corresponds to the {@code basevertex} parameter in {@code glDrawElementsBaseVertex}.
     * This allows multiple meshes to share the same EBO range (if identical) or allows
     * indices to start at 0 relative to the mesh while the mesh resides at a non-zero offset in the VBO.
     */
    public final int baseVertex;

    /**
     * Constructs a new indexed draw command.
     *
     * @param material         The material to use for this draw call.
     * @param indexCount       The number of indices to draw.
     * @param indexOffsetBytes The byte offset in the EBO where indices start.
     * @param baseVertex       The base vertex index in the VBO to add to all indices.
     */
    public VxDrawCommand(VxMaterial material, int indexCount, long indexOffsetBytes, int baseVertex) {
        this.material = material;
        this.indexCount = indexCount;
        this.indexOffsetBytes = indexOffsetBytes;
        this.baseVertex = baseVertex;
    }
}