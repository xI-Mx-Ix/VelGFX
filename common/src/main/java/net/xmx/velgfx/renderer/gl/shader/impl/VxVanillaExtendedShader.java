/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.shader.impl;

import net.xmx.velgfx.renderer.gl.shader.VxShaderProgram;
import net.xmx.velgfx.resources.VxResourceLocation;

/**
 * The core shader program for the VelGFX Vanilla Pipeline.
 * <p>
 * This shader provides extended functionality over the standard Minecraft shaders, including:
 * <ul>
 *     <li><b>Hardware Instancing:</b> Supports per-instance Model Matrices and attributes.</li>
 *     <li><b>PBR Lighting:</b> Implements LabPBR standard for Normal and Specular mapping.</li>
 *     <li><b>Dynamic Lighting:</b> Calculates Sun/Moon lighting per-pixel.</li>
 *     <li><b>Fog & Overlay:</b> Fully compatible with Vanilla fog and damage overlay effects.</li>
 * </ul>
 *
 * @author xI-Mx-Ix
 */
public class VxVanillaExtendedShader extends VxShaderProgram {

    /**
     * The location of the Vertex Shader source file.
     */
    private static final VxResourceLocation VERTEX =
            new VxResourceLocation("velgfx", "shaders/vx_vanilla_extended.vsh");

    /**
     * The location of the Fragment Shader source file.
     */
    private static final VxResourceLocation FRAGMENT =
            new VxResourceLocation("velgfx", "shaders/vx_vanilla_extended.fsh");

    /**
     * Constructs the shader program by loading the source files.
     */
    public VxVanillaExtendedShader() {
        super(VERTEX, FRAGMENT);
    }

    /**
     * Binds the vertex attribute locations to match the layout expected by the VAO and Instance Buffer.
     * <p>
     * <b>Standard Attributes (Per-Vertex):</b>
     * <ul>
     *     <li>0: Position (vec3)</li>
     *     <li>1: Color (vec4)</li>
     *     <li>2: UV0 (vec2) - Main Texture Coordinates</li>
     *     <li>5: Normal (vec3)</li>
     *     <li>8: Tangent (vec4)</li>
     * </ul>
     * <p>
     * <b>Instanced Attributes (Per-Instance):</b>
     * <ul>
     *     <li>10-13: i_ModelMat (mat4) - The Model Matrix</li>
     *     <li>14: i_AuxData (ivec2) - Packed Lightmap and Overlay indices</li>
     * </ul>
     */
    @Override
    protected void bindAttributes() {
        // --- Standard Geometry Attributes ---
        bindAttribute(0, "Position");
        bindAttribute(1, "Color");
        bindAttribute(2, "UV0");
        // Note: UV1 (Overlay) and UV2 (Lightmap) are usually Attributes 3 and 4 in Vanilla.
        // In this shader, we receive them via the Instanced Attribute 'i_AuxData' for entities,
        // effectively overriding the static mesh attributes for these values.
        bindAttribute(5, "Normal");
        bindAttribute(8, "Tangent");

        // --- Instanced Attributes ---
        // Defined in VxInstanceBuffer. Corresponds to divisor=1 attributes.
        bindAttribute(10, "i_ModelMat"); // Takes up locations 10, 11, 12, 13
        bindAttribute(14, "i_AuxData");  // Contains Lightmap (x) and Overlay (y)
    }

    /**
     * Registers all uniform variables required by the shader.
     */
    @Override
    protected void registerUniforms() {
        // Global Matrices
        createUniform("ViewMat");   // World -> View
        createUniform("ProjMat");   // View -> Clip

        // Note: 'ModelMat' is NOT a uniform here, as it is passed via attributes.

        // Texture Samplers
        createUniform("Sampler0"); // Albedo
        createUniform("Sampler1"); // Overlay (Damage)
        createUniform("Sampler2"); // Lightmap
        createUniform("Sampler3"); // Specular / PBR
        createUniform("Sampler4"); // Normal Map

        // Lighting Data
        createUniform("Light0_Direction");
        createUniform("Light0_Color");
        createUniform("Light1_Direction");
        createUniform("Light1_Color");

        // Material & Environment Settings
        createUniform("ColorModulator");
        createUniform("AlphaCutoff");

        // Fog Configuration
        createUniform("FogStart");
        createUniform("FogEnd");
        createUniform("FogColor");
        createUniform("FogShape");
    }
}