/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.material;

import net.xmx.velgfx.renderer.VelGFX;
import net.xmx.velgfx.renderer.gl.state.VxBlendMode;
import net.xmx.velgfx.renderer.gl.state.VxRenderType;
import net.xmx.velgfx.resources.VxResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * Represents a Physically Based Rendering (PBR) material definition.
 * <p>
 * This class acts as a container for all visual properties required to render a surface,
 * including scalar factors (Metallic, Roughness) and texture maps.
 * <p>
 * To ensure maximum compatibility with the OpenGL pipeline and external Shaderpacks (LabPBR),
 * this class abstracts the scalar factors by generating 1x1 pixel textures if an explicit
 * texture map is not provided. It stores optimized "baked" textures:
 * <ul>
 *     <li><b>Albedo:</b> Contains Base Color, baked Ambient Occlusion, and baked Emissive Color.</li>
 *     <li><b>Specular:</b> Contains Metallic, Roughness (Smoothness), and Emissive Strength (LabPBR).</li>
 * </ul>
 *
 * @author xI-Mx-Ix
 */
public class VxMaterial {

    /**
     * Default resource location pointing to a simple 1x1 white texture.
     * Used as a fallback for missing Albedo maps to ensure proper color multiplication.
     */
    public static final VxResourceLocation DEFAULT_WHITE =
            new VxResourceLocation(VelGFX.MODID, "/renderer/white.png");

    /**
     * The unique name of this material, used for debugging and caching.
     */
    public final String name;

    // --- Texture Locations (Source Files) ---

    /**
     * Resource location of the Albedo (Base Color) texture.
     * Defines the diffuse color of the surface.
     */
    public VxResourceLocation albedoMap = DEFAULT_WHITE;

    /**
     * Resource location of the Normal map (Tangent Space).
     * Defines high-frequency surface details.
     */
    public VxResourceLocation normalMap = null;

    /**
     * Resource location of the Specular map.
     * <p>
     * This texture packs multiple PBR data channels into one texture (LabPBR standard):
     * <ul>
     *     <li><b>Red Channel:</b> Smoothness (1.0 - Roughness).</li>
     *     <li><b>Green Channel:</b> Metallic factor.</li>
     *     <li><b>Blue Channel:</b> Reserved / Porosity.</li>
     *     <li><b>Alpha Channel:</b> Emissive Strength.</li>
     * </ul>
     */
    public VxResourceLocation specularMap = null;

    // --- GL Texture IDs (Runtime) ---

    /**
     * OpenGL texture ID for Albedo. -1 if not loaded.
     */
    public int albedoMapGlId = -1;

    /**
     * OpenGL texture ID for Normal Map. -1 if not loaded.
     */
    public int normalMapGlId = -1;

    /**
     * OpenGL texture ID for Specular/Metallic-Roughness Map. -1 if not loaded.
     */
    public int specularMapGlId = -1;

    // --- Render State ---

    /**
     * Whether the material should be rendered without backface culling.
     */
    public boolean doubleSided = false;

    /**
     * The OpenGL blend mode to use (Opaque, Alpha, etc.).
     */
    public VxBlendMode blendMode = VxBlendMode.OPAQUE;

    /**
     * The sorting bucket for the render queue (Opaque, Cutout, Translucent).
     */
    public VxRenderType renderType = VxRenderType.OPAQUE;

    /**
     * The alpha threshold for discarding pixels in CUTOUT mode.
     */
    public float alphaCutoff = 0.5f;

    // --- PBR Factors (Uniforms / Baked into 1x1 Textures) ---

    /**
     * The base color factor (RGBA) multiplied with the Albedo texture.
     */
    public final float[] baseColorFactor = {1.0f, 1.0f, 1.0f, 1.0f};

    /**
     * The emissive color factor (RGB) multiplied with the Emissive texture.
     * Stored here for fallback generation even if the texture is missing.
     */
    public final float[] emissiveFactor = {0.0f, 0.0f, 0.0f};

    /**
     * The metallic factor (0.0 to 1.0).
     */
    public float metallicFactor = 1.0f;

    /**
     * The roughness factor (0.0 to 1.0).
     */
    public float roughnessFactor = 1.0f;

    /**
     * The occlusion strength factor (0.0 to 1.0).
     * Controls the intensity of the ambient occlusion effect.
     */
    public float occlusionStrength = 1.0f;

    /**
     * Constructs a new material instance.
     *
     * @param name The identifier for this material.
     */
    public VxMaterial(String name) {
        this.name = name;
    }

    /**
     * Ensures that all required PBR textures exist on the GPU.
     * <p>
     * If specific maps (Normal, Specular) are missing from the source model,
     * this method delegates to {@link VxPBRGenerator} to create 1x1 pixel textures representing
     * the scalar factors (Metallic, Roughness).
     * <p>
     * This guarantees that the shader pipeline can always sample from valid texture units
     * without needing complex conditional logic or uniform uploads.
     */
    public void ensureGenerated() {
        if (this.normalMapGlId == -1) {
            this.normalMapGlId = VxPBRGenerator.generateFlatNormalMap();
        }

        if (this.specularMapGlId == -1) {
            // Encode metallic and roughness into a 1x1 pixel texture (LabPBR format)
            this.specularMapGlId = VxPBRGenerator.generateMetallicRoughnessMap(this.roughnessFactor, this.metallicFactor);
        }
    }

    /**
     * Releases all OpenGL texture resources associated with this material.
     * Should be called when the material is no longer needed to prevent VRAM leaks.
     */
    public void delete() {
        if (albedoMapGlId != -1) {
            GL11.glDeleteTextures(albedoMapGlId);
            albedoMapGlId = -1;
        }
        if (normalMapGlId != -1) {
            GL11.glDeleteTextures(normalMapGlId);
            normalMapGlId = -1;
        }
        if (specularMapGlId != -1) {
            GL11.glDeleteTextures(specularMapGlId);
            specularMapGlId = -1;
        }
    }
}