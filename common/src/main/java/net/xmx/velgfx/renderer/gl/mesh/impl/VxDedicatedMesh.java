/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.mesh.impl;

import net.xmx.velgfx.renderer.gl.VxDrawCommand;
import net.xmx.velgfx.renderer.gl.VxVertexBuffer;
import net.xmx.velgfx.renderer.gl.mesh.VxAbstractRenderableMesh;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;

/**
 * A renderable mesh that owns a dedicated Vertex Buffer Object (VBO).
 * <p>
 * This implementation is used for static models where the geometry does not change
 * frame-by-frame (unlike skinned meshes). It uploads the provided vertex data
 * directly to GPU memory upon instantiation.
 *
 * @author xI-Mx-Ix
 */
public class VxDedicatedMesh extends VxAbstractRenderableMesh {

    /**
     * The wrapper for the OpenGL VBO and VAO handles.
     */
    private final VxVertexBuffer vertexBuffer;

    /**
     * Constructs a new dedicated mesh.
     *
     * @param vertexData      The interleaved vertex data (Direct Buffer).
     * @param allDrawCommands The list of commands required to render this mesh.
     */
    public VxDedicatedMesh(ByteBuffer vertexData, List<VxDrawCommand> allDrawCommands) {
        // Static models delegate group handling to the VxStaticModel hierarchy,
        // so we pass an empty map for group commands here to the base class.
        super(allDrawCommands, Collections.emptyMap());

        // Allocate GPU memory and upload data (Static Draw)
        this.vertexBuffer = new VxVertexBuffer(vertexData.remaining(), false);
        this.vertexBuffer.uploadSubData(0, vertexData);

        // Resolve and generate textures immediately
        initializeTextures();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setupVaoState() {
        this.vertexBuffer.bind();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getFinalVertexOffset(VxDrawCommand command) {
        // Since this mesh owns the VBO exclusively, the offset is exactly what's in the command.
        return command.vertexOffset;
    }

    /**
     * Releases the VBO and VAO associated with this mesh.
     */
    @Override
    public void delete() {
        if (!isDeleted) {
            this.vertexBuffer.delete();
            isDeleted = true;
        }
    }
}