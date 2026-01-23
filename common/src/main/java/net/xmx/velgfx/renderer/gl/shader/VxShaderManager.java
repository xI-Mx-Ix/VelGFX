/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.shader;

import net.xmx.velgfx.renderer.gl.shader.impl.VxVanillaExtendedShader;
import net.xmx.velgfx.renderer.gl.shader.impl.VxPBRConverterShader;
import net.xmx.velgfx.renderer.gl.shader.impl.VxSkinningShader;

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
    private final VxVanillaExtendedShader vanillaExtendedShader;

    /**
     * Initializes all shader programs.
     * Must be called on the Render Thread with an active OpenGL context.
     */
    public VxShaderManager() {
        this.skinningShader = new VxSkinningShader();
        this.pbrConverterShader = new VxPBRConverterShader();
        this.vanillaExtendedShader = new VxVanillaExtendedShader();
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
     * Gets the Vanilla Extended Shader program.
     *
     * @return The entity shader instance.
     */
    public VxVanillaExtendedShader getVanillaExtendedShader() {
        return vanillaExtendedShader;
    }


    /**
     * Releases all shader resources.
     */
    @Override
    public void close() {
        skinningShader.close();
        pbrConverterShader.close();
        vanillaExtendedShader.close();
    }
}