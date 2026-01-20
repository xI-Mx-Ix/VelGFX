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
 * A utility class responsible for generating procedural PBR textures directly on the GPU.
 * <p>
 * This class ensures that every material has a complete set of textures by creating
 * 1x1 pixel representations for scalar values.
 * <p>
 * <b>Important for Minecraft Shaders:</b>
 * Standard Minecraft Shaders (OldPBR/LabPBR) expect the following channel packing for the Specular map:
 * <ul>
 *     <li><b>Red:</b> Smoothness (Inverse of Roughness) -> 0.0 = Rough, 1.0 = Smooth</li>
 *     <li><b>Green:</b> Metallic -> 0.0 = Dielectric, 1.0 = Metal</li>
 *     <li><b>Blue:</b> (Optional/Emissive) -> Kept empty here</li>
 * </ul>
 * This differs from the raw glTF format (Green=Roughness, Blue=Metallic), so we perform conversion here.
 *
 * @author xI-Mx-Ix
 */
public final class VxPBRGenerator {

    /**
     * Private constructor to prevent instantiation.
     */
    private VxPBRGenerator() {
    }

    /**
     * Generates a 1x1 pixel "Flat" normal map.
     * <p>
     * <b>Color:</b> RGB(128, 128, 255).
     * <br><b>Vector:</b> (0, 0, 1) in Tangent Space.
     *
     * @return The OpenGL texture ID.
     */
    public static int generateFlatNormalMap() {
        return create1x1Texture((byte) 128, (byte) 128, (byte) 255, (byte) 255);
    }

    /**
     * Generates a 1x1 pixel texture representing the Metallic and Roughness factors.
     * <p>
     * This method converts standard glTF PBR values into the format expected by
     * Minecraft Shaders (Red=Smoothness, Green=Metallic).
     *
     * @param roughness The roughness value (0.0 = Smooth, 1.0 = Rough).
     * @param metallic  The metallic value (0.0 = Dielectric, 1.0 = Metal).
     * @return The OpenGL texture ID.
     */
    public static int generateMetallicRoughnessMap(float roughness, float metallic) {
        // Convert Roughness to Smoothness (Smoothness = 1.0 - Roughness)
        float smoothness = 1.0f - roughness;

        // Channel Mapping for Minecraft Shaders:
        // Red   = Smoothness
        // Green = Metallic
        // Blue  = 0 (Unused)
        byte r = (byte) (smoothness * 255.0f);
        byte g = (byte) (metallic * 255.0f);
        byte b = 0; // Keep Blue channel empty to prevent artifacts

        return create1x1Texture(r, g, b, (byte) 255);
    }

    /**
     * Generates a 1x1 pixel texture representing the Emissive color.
     *
     * @param rgb The emissive color factors (0.0 - 1.0).
     * @return The OpenGL texture ID.
     */
    public static int generateEmissiveMap(float[] rgb) {
        byte r = (byte) (rgb[0] * 255.0f);
        byte g = (byte) (rgb[1] * 255.0f);
        byte b = (byte) (rgb[2] * 255.0f);
        return create1x1Texture(r, g, b, (byte) 255);
    }

    /**
     * Generates a 1x1 pixel texture representing Ambient Occlusion strength.
     * <p>
     * <b>Channel Mapping:</b> Red Channel = Occlusion Strength.
     *
     * @param strength The occlusion strength (0.0 = fully occluded, 1.0 = no occlusion).
     * @return The OpenGL texture ID.
     */
    public static int generateOcclusionMap(float strength) {
        byte val = (byte) (strength * 255.0f);
        // Red channel is primary for AO, but we replicate for safety
        return create1x1Texture(val, val, val, (byte) 255);
    }

    /**
     * Helper method to allocate and upload a 1x1 RGBA texture.
     *
     * @param r Red component (0-255).
     * @param g Green component (0-255).
     * @param b Blue component (0-255).
     * @param a Alpha component (0-255).
     * @return The generated OpenGL texture ID.
     */
    private static int create1x1Texture(byte r, byte g, byte b, byte a) {
        int textureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);

        // Nearest filtering is required for single-value lookups
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

    /**
     * Converts and uploads a glTF Metallic-Roughness texture to the GPU.
     * <p>
     * <b>glTF Standard:</b>
     * <ul>
     *     <li>Green: Roughness</li>
     *     <li>Blue: Metallic</li>
     * </ul>
     * <p>
     * <b>Minecraft Shader Standard (LabPBR/OldPBR):</b>
     * <ul>
     *     <li>Red: Smoothness (Inverse Roughness)</li>
     *     <li>Green: Metallic</li>
     * </ul>
     * <p>
     * This method reads the raw image data, performs the pixel-wise conversion,
     * and uploads the result as a standard OpenGL texture.
     *
     * @param imageData The raw native image data from the texture file.
     * @return The OpenGL Texture ID, or -1 if processing failed.
     */
    public static int convertAndUploadMetallicRoughness(VxNativeImage imageData) {
        if (imageData == null) {
            return -1;
        }

        try {
            ByteBuffer pixels = imageData.getPixelData();
            int limit = pixels.capacity();

            // Iterate over all pixels (RGBA = 4 bytes per pixel)
            // We modify the buffer in-place before uploading.
            for (int i = 0; i < limit; i += 4) {
                // Read glTF values
                // Byte is signed in Java (-128 to 127), so we mask with 0xFF to get unsigned int (0-255)
                int roughVal = pixels.get(i + 1) & 0xFF; // Green Channel
                byte metalVal = pixels.get(i + 2);       // Blue Channel

                // Calculate Smoothness: 255 - Roughness
                // (Roughness 0 -> Smoothness 255)
                byte smoothness = (byte) (255 - roughVal);

                // Write new Minecraft Shader PBR format
                // Red   = Smoothness
                // Green = Metallic
                // Blue  = 0 (Unused/Emissive placeholder)
                // Alpha = 255 (Full Opacity/Unused)
                pixels.put(i, smoothness);     // R
                pixels.put(i + 1, metalVal);   // G
                pixels.put(i + 2, (byte) 0);   // B
                pixels.put(i + 3, (byte) 255); // A
            }

            // Upload the modified buffer to VRAM
            return VxTextureLoader.uploadTexture(imageData);

        } catch (Exception e) {
            VelGFX.LOGGER.error("Failed to process Metallic-Roughness texture", e);
            return -1;
        }
    }
}