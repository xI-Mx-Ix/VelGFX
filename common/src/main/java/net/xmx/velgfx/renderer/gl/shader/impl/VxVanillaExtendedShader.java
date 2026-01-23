/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.shader.impl;

import net.xmx.velgfx.renderer.gl.shader.VxShaderProgram;
import net.xmx.velgfx.resources.VxResourceLocation;

/**
 * A unified shader program for rendering entities with per-pixel lighting,
 * vanilla fog, overlay handling, and LabPBR emissive support.
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
        bindAttribute(4, "UV2"); // Lightmap
        bindAttribute(5, "Normal");
        bindAttribute(8, "Tangent"); // Bound to satisfy layout, unused in vanilla logic
    }

    /**
     * Registers all uniform variables required by the shader program.
     * Maps the uniform names from the GLSL source to integer locations.
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
        createUniform("Sampler3"); // Specular / Emissive

        // Lighting Vectors
        createUniform("Light0_Direction");
        createUniform("Light1_Direction");

        // Material Properties
        createUniform("ColorModulator");
        createUniform("AlphaCutoff");
        createUniform("UseEmissive"); // Controls whether Sampler3 alpha is used for emission

        // Fog Settings
        createUniform("FogStart");
        createUniform("FogEnd");
        createUniform("FogColor");
        createUniform("FogShape");
    }
}