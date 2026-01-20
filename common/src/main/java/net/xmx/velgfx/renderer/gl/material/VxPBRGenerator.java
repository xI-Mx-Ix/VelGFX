/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.material;

import net.xmx.velgfx.renderer.VelGFX;
import net.xmx.velgfx.resources.VxNativeImage;
import net.xmx.velgfx.resources.VxTextureLoader;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

/**
 * A utility class responsible for generating and processing PBR (Physically Based Rendering) textures.
 * <p>
 * This class serves as the bridge between the glTF 2.0 material standard and the format expected
 * by Minecraft Shaderpacks (specifically the LabPBR and OldPBR standards).
 * <p>
 * It provides functionality to:
 * <ul>
 *     <li><b>Bake Textures:</b> Combine split textures (e.g., Albedo, Occlusion, Emissive) into a single optimized map.</li>
 *     <li><b>Pack Channels:</b> Convert glTF Metallic-Roughness data into LabPBR Specular maps.</li>
 *     <li><b>Generate Fallbacks:</b> Create 1x1 pixel textures from scalar factors when image files are missing.</li>
 * </ul>
 *
 * @author xI-Mx-Ix
 */
public final class VxPBRGenerator {

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private VxPBRGenerator() {
    }

    /**
     * Creates the final Albedo (Diffuse) texture by combining base color, occlusion, and emission.
     * <p>
     * Since standard Minecraft shaders often lack explicit slots for Occlusion or Emissive textures,
     * this method "bakes" these effects directly into the color texture:
     * <ul>
     *     <li><b>Base Color:</b> Multiplied by the {@code baseColor} factor.</li>
     *     <li><b>Occlusion:</b> Darkens the color based on the red channel of the occlusion map.</li>
     *     <li><b>Emissive:</b> Adds light to the color based on the emissive map and factor.</li>
     * </ul>
     *
     * @param albedoImg    The base color image (can be null).
     * @param occlusionImg The occlusion or ORM image (can be null).
     * @param emissiveImg  The emissive image (can be null).
     * @param baseColor    The RGBA color factor to multiply (must be length 4).
     * @param aoStrength   The strength of the ambient occlusion effect (0.0 to 1.0).
     * @param emissiveFact The RGB emissive factor to multiply (must be length 3).
     * @return The OpenGL Texture ID of the generated map.
     */
    public static int createAlbedoMap(VxNativeImage albedoImg,
                                      VxNativeImage occlusionImg,
                                      VxNativeImage emissiveImg,
                                      float[] baseColor,
                                      float aoStrength,
                                      float[] emissiveFact) {

        // Determine target dimensions. If all textures are null, default to 1x1.
        int width = 1;
        int height = 1;

        if (albedoImg != null) {
            width = albedoImg.getWidth();
            height = albedoImg.getHeight();
        } else if (occlusionImg != null) {
            width = occlusionImg.getWidth();
            height = occlusionImg.getHeight();
        } else if (emissiveImg != null) {
            width = emissiveImg.getWidth();
            height = emissiveImg.getHeight();
        }

        // Validate dimension consistency (basic check to warn about mismatches)
        if (occlusionImg != null && (occlusionImg.getWidth() != width || occlusionImg.getHeight() != height)) {
            VelGFX.LOGGER.warn("Texture dimension mismatch in Albedo generation. AO bake might look incorrect.");
        }

        try (VxNativeImage result = VxNativeImage.create(width, height)) {
            ByteBuffer out = result.getPixelData();
            ByteBuffer bufAlbedo = (albedoImg != null) ? albedoImg.getPixelData() : null;
            ByteBuffer bufOcc = (occlusionImg != null) ? occlusionImg.getPixelData() : null;
            ByteBuffer bufEmit = (emissiveImg != null) ? emissiveImg.getPixelData() : null;

            int capacity = width * height * 4;

            // Cache factors for performance
            float rF = baseColor[0];
            float gF = baseColor[1];
            float bF = baseColor[2];
            float aF = baseColor[3];

            float emR = emissiveFact != null ? emissiveFact[0] : 1.0f;
            float emG = emissiveFact != null ? emissiveFact[1] : 1.0f;
            float emB = emissiveFact != null ? emissiveFact[2] : 1.0f;

            for (int i = 0; i < capacity; i += 4) {
                // 1. Process Base Color
                int r, g, b, a;
                if (bufAlbedo != null) {
                    r = (int) ((bufAlbedo.get(i) & 0xFF) * rF);
                    g = (int) ((bufAlbedo.get(i + 1) & 0xFF) * gF);
                    b = (int) ((bufAlbedo.get(i + 2) & 0xFF) * bF);
                    a = (int) ((bufAlbedo.get(i + 3) & 0xFF) * aF);
                } else {
                    // If no texture, use the factor as the color (usually white 1.0)
                    r = (int) (255 * rF);
                    g = (int) (255 * gF);
                    b = (int) (255 * bF);
                    a = (int) (255 * aF);
                }

                // 2. Apply Occlusion (Multiply)
                // In glTF, Occlusion is stored in the Red channel.
                // Formula: Color * (1.0 + Strength * (AO - 1.0))
                if (bufOcc != null && i < bufOcc.capacity()) {
                    int aoByte = bufOcc.get(i) & 0xFF;
                    float aoVal = aoByte / 255.0f;
                    float finalAo = 1.0f + aoStrength * (aoVal - 1.0f);

                    r = (int) (r * finalAo);
                    g = (int) (g * finalAo);
                    b = (int) (b * finalAo);
                }

                // 3. Apply Emissive (Additive)
                // Formula: Color + (EmissiveTexture * EmissiveFactor)
                if (bufEmit != null && i < bufEmit.capacity()) {
                    int eR = bufEmit.get(i) & 0xFF;
                    int eG = bufEmit.get(i + 1) & 0xFF;
                    int eB = bufEmit.get(i + 2) & 0xFF;

                    r += (int) (eR * emR);
                    g += (int) (eG * emG);
                    b += (int) (eB * emB);
                }

                // Clamp values to valid byte range (0-255) and write to buffer
                out.put(i, (byte) Math.min(255, Math.max(0, r)));
                out.put(i + 1, (byte) Math.min(255, Math.max(0, g)));
                out.put(i + 2, (byte) Math.min(255, Math.max(0, b)));
                out.put(i + 3, (byte) Math.min(255, Math.max(0, a)));
            }

            return VxTextureLoader.uploadTexture(result);
        }
    }

    /**
     * Creates the final LabPBR-compliant Specular texture.
     * <p>
     * This method converts standard glTF channels into the LabPBR format used by most shaders:
     * <ul>
     *     <li><b>Red Channel:</b> Smoothness (Derived from 1.0 - Roughness).</li>
     *     <li><b>Green Channel:</b> Metallic (Copied from source).</li>
     *     <li><b>Blue Channel:</b> Reserved / Porosity (Hardcoded to 0).</li>
     *     <li><b>Alpha Channel:</b> Emissive Strength (Calculated from luminance of Emissive map).</li>
     * </ul>
     *
     * @param mrImg        The Metallic-Roughness image (Green=Rough, Blue=Metal). Can be null.
     * @param emissiveImg  The Emissive image (RGB). Can be null.
     * @param roughFactor  The roughness scalar factor.
     * @param metalFactor  The metallic scalar factor.
     * @param emissiveFact The emissive RGB scalar factor.
     * @return The OpenGL Texture ID of the processed map.
     */
    public static int createSpecularMap(VxNativeImage mrImg,
                                        VxNativeImage emissiveImg,
                                        float roughFactor,
                                        float metalFactor,
                                        float[] emissiveFact) {

        // Determine dimensions
        int width = 1;
        int height = 1;

        if (mrImg != null) {
            width = mrImg.getWidth();
            height = mrImg.getHeight();
        } else if (emissiveImg != null) {
            width = emissiveImg.getWidth();
            height = emissiveImg.getHeight();
        }

        try (VxNativeImage result = VxNativeImage.create(width, height)) {
            ByteBuffer out = result.getPixelData();
            ByteBuffer bufMr = (mrImg != null) ? mrImg.getPixelData() : null;
            ByteBuffer bufEmit = (emissiveImg != null) ? emissiveImg.getPixelData() : null;

            int capacity = width * height * 4;

            float emR = emissiveFact != null ? emissiveFact[0] : 1.0f;
            float emG = emissiveFact != null ? emissiveFact[1] : 1.0f;
            float emB = emissiveFact != null ? emissiveFact[2] : 1.0f;

            for (int i = 0; i < capacity; i += 4) {
                // --- 1. Metallic & Roughness Processing ---
                float currentRoughness;
                float currentMetallic;

                if (bufMr != null && i < bufMr.capacity()) {
                    // glTF Standard: Green = Roughness, Blue = Metallic
                    int rawRough = bufMr.get(i + 1) & 0xFF;
                    int rawMetal = bufMr.get(i + 2) & 0xFF;

                    // Apply scalar factors to texture values
                    currentRoughness = (rawRough / 255.0f) * roughFactor;
                    currentMetallic = (rawMetal / 255.0f) * metalFactor;
                } else {
                    // Use scalar factors only
                    currentRoughness = roughFactor;
                    currentMetallic = metalFactor;
                }

                // Convert to LabPBR format
                // Red = Smoothness = 1.0 - Roughness
                byte smoothByte = (byte) ((1.0f - currentRoughness) * 255.0f);
                // Green = Metallic
                byte metalByte = (byte) (currentMetallic * 255.0f);

                // --- 2. Emissive Strength Processing ---
                byte emissiveByte = 0;
                if (bufEmit != null && i < bufEmit.capacity()) {
                    int eR = bufEmit.get(i) & 0xFF;
                    int eG = bufEmit.get(i + 1) & 0xFF;
                    int eB = bufEmit.get(i + 2) & 0xFF;

                    // Calculate Perceptual Luminance
                    // Y = 0.2126R + 0.7152G + 0.0722B
                    float luma = 0.2126f * (eR * emR) + 0.7152f * (eG * emG) + 0.0722f * (eB * emB);
                    emissiveByte = (byte) Math.min(255, (int) luma);
                }

                // Write Output (RGBA)
                out.put(i, smoothByte);         // R: Smoothness
                out.put(i + 1, metalByte);      // G: Metallic
                out.put(i + 2, (byte) 0);       // B: Reserved
                out.put(i + 3, emissiveByte);   // A: Emissive Strength
            }

            return VxTextureLoader.uploadTexture(result);
        }
    }

    /**
     * Generates a 1x1 pixel "Flat" normal map.
     * <p>
     * Represents the vector (0, 0, 1) in tangent space, which corresponds to RGB(128, 128, 255).
     *
     * @return The OpenGL Texture ID.
     */
    public static int generateFlatNormalMap() {
        return create1x1Texture((byte) 128, (byte) 128, (byte) 255, (byte) 255);
    }

    /**
     * Generates a 1x1 pixel texture representing scalar Metallic and Roughness values.
     * <p>
     * <b>Format (LabPBR):</b>
     * <ul>
     *     <li>Red: Smoothness (1.0 - Roughness)</li>
     *     <li>Green: Metallic</li>
     * </ul>
     *
     * @param roughness The scalar roughness value (0.0 - 1.0).
     * @param metallic  The scalar metallic value (0.0 - 1.0).
     * @return The OpenGL Texture ID.
     */
    public static int generateMetallicRoughnessMap(float roughness, float metallic) {
        byte r = (byte) ((1.0f - roughness) * 255.0f);
        byte g = (byte) (metallic * 255.0f);
        return create1x1Texture(r, g, (byte) 0, (byte) 255);
    }

    /**
     * Generates a 1x1 pixel texture representing scalar Emissive color.
     *
     * @param rgb The RGB array of emissive factors.
     * @return The OpenGL Texture ID.
     */
    public static int generateEmissiveMap(float[] rgb) {
        byte r = (byte) (rgb[0] * 255.0f);
        byte g = (byte) (rgb[1] * 255.0f);
        byte b = (byte) (rgb[2] * 255.0f);
        return create1x1Texture(r, g, b, (byte) 255);
    }

    /**
     * Generates a 1x1 pixel texture representing scalar Occlusion strength.
     *
     * @param strength The occlusion strength (0.0 - 1.0).
     * @return The OpenGL Texture ID.
     */
    public static int generateOcclusionMap(float strength) {
        byte val = (byte) (strength * 255.0f);
        return create1x1Texture(val, val, val, (byte) 255);
    }

    // --- Internal Helpers ---

    /**
     * Helper to allocate a 1x1 texture in native memory and upload it to the GPU.
     * Uses RGBA8 format.
     */
    private static int create1x1Texture(byte r, byte g, byte b, byte a) {
        int textureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);

        // Nearest filtering is required for single-value lookup to avoid interpolation artifacts
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        // Repeat wrapping
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer buffer = stack.malloc(4);
            buffer.put(r).put(g).put(b).put(a);
            buffer.flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        }

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return textureId;
    }
}