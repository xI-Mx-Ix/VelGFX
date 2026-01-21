/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.shader;

/**
 * The central manager for all shader programs used by the renderer.
 * <p>
 * This class is responsible for the instantiation, storage, and cleanup
 * of specific shader implementations (e.g., Skinning, PBR Baking).
 *
 * @author xI-Mx-Ix
 */
public class VxShaderManager implements AutoCloseable {

    private final VxSkinningShader skinningShader;
    private final VxPBRConverterShader pbrConverterShader;

    /**
     * Initializes all shader programs.
     * Must be called on the Render Thread with an active OpenGL context.
     */
    public VxShaderManager() {
        this.skinningShader = new VxSkinningShader();
        this.pbrConverterShader = new VxPBRConverterShader();
    }

    /**
     * Gets the Skinning Shader program.
     *
     * @return The skinning shader instance.
     */
    public VxSkinningShader getSkinningShader() {
        return skinningShader;
    }

    /**
     * Gets the PBR Converter Shader used for texture baking.
     *
     * @return The PBR converter shader instance.
     */
    public VxPBRConverterShader getPBRConverterShader() {
        return pbrConverterShader;
    }

    /**
     * Releases all shader resources.
     */
    @Override
    public void close() {
        skinningShader.close();
        pbrConverterShader.close();
    }
}