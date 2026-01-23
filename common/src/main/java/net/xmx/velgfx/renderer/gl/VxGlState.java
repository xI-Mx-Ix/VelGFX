/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl;

import org.lwjgl.opengl.*;

/**
 * A utility class for safely managing and isolating critical OpenGL state.
 * <p>
 * This class provides a mechanism to snapshot the current bindings for Vertex Array Objects (VAO),
 * Buffer Objects, Shader Programs, and Texture Units before custom rendering operations
 * and restore them afterward.
 * <p>
 * This isolation is essential when mixing custom OpenGL calls with the vanilla Minecraft
 * rendering engine (Blaze3D) or external mods like Iris/OptiFine, preventing state leakage
 * such as wrong shaders being active or texture units being overwritten.
 *
 * @author xI-Mx-Ix
 */
public class VxGlState {
    private static int previousVaoId = -1;
    private static int previousVboId = -1;
    private static int previousEboId = -1;
    private static int previousProgramId = -1;
    private static int previousActiveTexture = -1;

    /**
     * Queries and stores the currently bound OpenGL state.
     * <p>
     * Captures:
     * <ul>
     *     <li>Vertex Array Object (VAO)</li>
     *     <li>Array Buffer (VBO)</li>
     *     <li>Element Array Buffer (EBO)</li>
     *     <li>Current Shader Program</li>
     *     <li>Active Texture Unit</li>
     * </ul>
     */
    public static void saveCurrentState() {
        previousVaoId = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        previousVboId = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        previousEboId = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        previousProgramId = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    }

    /**
     * Restores the OpenGL state that was captured by the last call to {@link #saveCurrentState()}.
     * <p>
     * This ensures that the vanilla renderer continues exactly where it left off,
     * unaware that intermediate custom draw calls occurred.
     */
    public static void restorePreviousState() {
        // Restore Shader Program
        // This fixes compatibility issues where custom shaders might remain active,
        // causing vanilla geometry to render with incorrect attributes.
        if (previousProgramId != -1) {
            GL20.glUseProgram(previousProgramId);
            previousProgramId = -1;
        }

        // Restore Vertex Array Object
        // This fixes the "Gradient Bug" where UV coordinates were interpreted as colors
        // because the custom skinning VAO remained bound.
        if (previousVaoId != -1) {
            GL30.glBindVertexArray(previousVaoId);
            previousVaoId = -1;
        }

        // Restore Buffer Bindings
        // Ensures subsequent vanilla draw calls do not accidentally write to custom buffers.
        if (previousVboId != -1) {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousVboId);
            previousVboId = -1;
        }
        if (previousEboId != -1) {
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, previousEboId);
            previousEboId = -1;
        }

        // Restore Active Texture Unit
        // Critical for Morph Target rendering, which modifies active units (e.g. Unit 14).
        if (previousActiveTexture != -1) {
            GL13.glActiveTexture(previousActiveTexture);
            previousActiveTexture = -1;
        }
    }
}