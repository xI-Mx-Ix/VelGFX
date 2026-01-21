/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.shader;

import net.xmx.velgfx.resources.VxResourceLocation;

/**
 * A utility shader used to bake PBR textures on the GPU.
 * <p>
 * This shader supports Multiple Render Targets (MRT) to generate both the
 * Albedo map and the LabPBR Specular map in a single pass.
 *
 * @author xI-Mx-Ix
 */
public class VxPBRConverterShader extends VxShaderProgram {

    private static final VxResourceLocation VERTEX =
            new VxResourceLocation("velgfx", "shaders/pbr_convert.vsh");
    private static final VxResourceLocation FRAGMENT =
            new VxResourceLocation("velgfx", "shaders/pbr_convert.fsh");

    /**
     * Constructs a new PBR converter shader.
     */
    public VxPBRConverterShader() {
        super(VERTEX, FRAGMENT);
    }

    /**
     * No attributes to bind as we generate vertices inside the shader using gl_VertexID.
     */
    @Override
    protected void bindAttributes() {
        // No-op
    }

    /**
     * Registers all uniforms required for texture processing.
     */
    @Override
    protected void registerUniforms() {
        // Texture Samplers
        createUniform("u_TexAlbedo");
        createUniform("u_TexMR");        // Metallic & Roughness
        createUniform("u_TexOcclusion"); // Ambient Occlusion
        createUniform("u_TexEmissive");

        // Texture Presence Flags (booleans)
        createUniform("u_HasAlbedo");
        createUniform("u_HasMR");
        createUniform("u_HasOcclusion");
        createUniform("u_HasEmissive");

        // PBR Scalar Factors
        createUniform("u_BaseColorFactor");
        createUniform("u_EmissiveFactor");
        createUniform("u_RoughnessFactor");
        createUniform("u_MetallicFactor");
        createUniform("u_OcclusionStrength");
    }
}