/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.layout;

/**
 * Defines the contract for a vertex memory layout used within the rendering engine.
 * <p>
 * Implementations of this interface define the structure of vertex data in a Buffer Object
 * and are responsible for configuring the Vertex Array Object (VAO) to interpret that data correctly.
 *
 * @author xI-Mx-Ix
 */
public interface IVxVertexLayout {

    /**
     * Gets the total size in bytes of a single vertex in this layout (the stride).
     *
     * @return The stride in bytes.
     */
    int getStride();

    /**
     * Configures the OpenGL vertex attribute pointers for the currently bound Vertex Array Object (VAO).
     * <p>
     * This method calls {@code glEnableVertexAttribArray} and {@code glVertexAttribPointer}
     * (or {@code glVertexAttribIPointer}) to map buffer data to shader attribute locations.
     */
    void setupAttributes();
}