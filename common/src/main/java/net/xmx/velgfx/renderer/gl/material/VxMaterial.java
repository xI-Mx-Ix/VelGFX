/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.material;

import net.xmx.velgfx.renderer.VelGFX;
import net.xmx.velgfx.resources.VxResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * Represents a PBR material defined by scalar properties and texture identifiers.
 * <p>
 * This class uses {@link VxResourceLocation} for file-based textures (Albedo) to ensure
 * compatibility with custom file paths, and delegates the generation of dynamic
 * PBR maps to {@link VxPBRGenerator}.
 *
 * @author xI-Mx-Ix
 */
public class VxMaterial {

    /**
     * A default resource location pointing to a simple white texture.
     */
    public static final VxResourceLocation DEFAULT_WHITE = new VxResourceLocation("assets/" + VelGFX.MODID + "/renderer/white.png");

    /**
     * The unique name of the material.
     */
    public final String name;

    // --- Texture Maps ---
    // Using VxResourceLocation here allows us to bypass Minecraft's restriction on characters.
    public VxResourceLocation albedoMap = DEFAULT_WHITE;

    // --- OpenGL Texture IDs ---
    public int albedoMapGlId = -1;
    public int normalMapGlId = -1;
    public int specularMapGlId = -1;

    // --- Scalar Factors ---
    /**
     * The base color factor (RGBA). Defaults to white [1.0, 1.0, 1.0, 1.0].
     */
    public final float[] baseColorFactor = {1.0f, 1.0f, 1.0f, 1.0f};

    /**
     * The metallic factor (0.0 = dielectric, 1.0 = metal). Defaults to 0.0.
     */
    public float metallicFactor = 0.0f;

    /**
     * The roughness factor (0.0 = smooth, 1.0 = rough). Defaults to 1.0.
     */
    public float roughnessFactor = 1.0f;

    /**
     * Constructs a new material with the given name.
     *
     * @param name The name of the material.
     */
    public VxMaterial(String name) {
        this.name = name;
    }

    /**
     * Checks if the PBR textures (Normal and Specular) exist, and generates them
     * directly on the GPU via {@link VxPBRGenerator} if they do not.
     */
    public void ensureGenerated() {
        if (this.normalMapGlId == -1) {
            this.normalMapGlId = VxPBRGenerator.generateFlatNormalMap();
        }
        if (this.specularMapGlId == -1) {
            this.specularMapGlId = VxPBRGenerator.generateLabPBRSpecularMap(this.roughnessFactor, this.metallicFactor);
        }
    }

    /**
     * Deletes the generated OpenGL textures associated with this material.
     */
    public void delete() {
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