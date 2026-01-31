/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx;

import net.xmx.velgfx.renderer.gl.shader.VxShaderManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The core entry point for the VelGFX rendering engine.
 * <p>
 * This class maintains global state references, such as the shader manager,
 * and handles the initialization lifecycle of OpenGL resources.
 *
 * @author xI-Mx-Ix
 */
public class VelGFX {

    /**
     * The unique Mod ID.
     */
    public static final String MODID = "velgfx";

    /**
     * The global logger instance.
     */
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    /**
     * The central shader manager instance.
     * This is initialized only after the OpenGL context is ready.
     */
    private static VxShaderManager shaderManager;

    /**
     * Initializes the OpenGL resources for the engine.
     * <p>
     * This method is called via a Mixin injection into the GameRenderer
     * to ensure that a valid OpenGL context exists before shader compilation begins.
     */
    public static void initOpenGL() {
        LOGGER.debug("Initializing VelGFX OpenGL resources...");
        shaderManager = new VxShaderManager();
    }

    /**
     * Retrieves the global shader manager.
     *
     * @return The active shader manager instance.
     * @throws IllegalStateException If called before {@link #initOpenGL()}.
     */
    public static VxShaderManager getShaderManager() {
        if (shaderManager == null) {
            throw new IllegalStateException("VelGFX ShaderManager not initialized! ensure initOpenGL() was called.");
        }
        return shaderManager;
    }

    /**
     * Releases all engine resources.
     * Should be called on game shutdown.
     */
    public static void destroy() {
        if (shaderManager != null) {
            shaderManager.close();
            shaderManager = null;
        }
    }
}