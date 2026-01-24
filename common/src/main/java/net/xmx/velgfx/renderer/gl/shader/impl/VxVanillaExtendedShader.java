/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.shader.impl;

import net.xmx.velgfx.renderer.gl.shader.VxShaderProgram;
import net.xmx.velgfx.resources.VxResourceLocation;

/**
 * A unified shader program for rendering entities with per-pixel lighting,
 * vanilla fog, overlay handling, and LabPBR support (Normal Maps + Specular).
 *
 * @author xI-Mx-Ix
 */
public class VxVanillaExtendedShader extends VxShaderProgram {

    private static final VxResourceLocation VERTEX =
            new VxResourceLocation("velgfx", "shaders/vx_vanilla_extended.vsh");
    private static final VxResourceLocation FRAGMENT =
            new VxResourceLocation("velgfx", "shaders/vx_vanilla_extended.fsh");

    public VxVanillaExtendedShader() {
        super(VERTEX, FRAGMENT);
    }

    @Override
    protected void bindAttributes() {
        bindAttribute(0, "Position");
        bindAttribute(1, "Color");
        bindAttribute(2, "UV0");
        bindAttribute(4, "UV2");    // Lightmap
        bindAttribute(5, "Normal");
        bindAttribute(8, "Tangent"); // Required for TBN Matrix calculation
    }

    /**
     * Registers all uniform variables required by the shader program.
     */
    @Override
    protected void registerUniforms() {
        // Transformation Matrices
        createUniform("ModelViewMat");
        createUniform("ProjMat");
        createUniform("NormalMat");

        // Texture Samplers
        createUniform("Sampler0"); // Albedo
        createUniform("Sampler1"); // Overlay
        createUniform("Sampler2"); // Lightmap
        createUniform("Sampler3"); // LabPBR Specular (Roughness/Metallic/Emissive)
        createUniform("Sampler4"); // Normal Map

        // Lighting Vectors
        createUniform("Light0_Direction");
        createUniform("Light1_Direction");

        // Material Properties
        createUniform("ColorModulator");
        createUniform("AlphaCutoff");

        // Fog Settings
        createUniform("FogStart");
        createUniform("FogEnd");
        createUniform("FogColor");
        createUniform("FogShape");
    }
}