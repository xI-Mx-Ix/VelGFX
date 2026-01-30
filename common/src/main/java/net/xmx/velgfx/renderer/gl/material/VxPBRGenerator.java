/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.material;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

/**
 * A utility class responsible for generating simple CPU-side PBR textures.
 * <p>
 * This class provides fallback functionality to create 1x1 pixel textures representing
 * scalar factors (like Metallic, Roughness, or Flat Normals) when explicit texture maps
 * are missing or when GPU baking is not required.
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
     * Generates a 1x1 pixel "Flat" normal map.
     * <p>
     * Represents the vector (0, 0, 1) in tangent space, which corresponds to RGB(128, 128, 255).
     * This is used as a default when a model lacks a normal map.
     *
     * @return The OpenGL Texture ID.
     */
    public static int generateFlatNormalMap() {
        return create1x1Texture((byte) 128, (byte) 128, (byte) 255, (byte) 255);
    }

    /**
     * Generates a 1x1 pixel texture representing scalar Metallic and Roughness values
     * according to the LabPBR 1.3 standard.
     * <p>
     * <b>LabPBR 1.3 Specular Map Format:</b>
     * <ul>
     *     <li>Red: Perceptual Smoothness (1.0 - sqrt(linearRoughness))</li>
     *     <li>Green: F0 / Reflectance (linear, 0-229) OR Metal ID (230-255)</li>
     *     <li>Blue: Porosity/SSS (0 for standard materials)</li>
     *     <li>Alpha: Emissive Strength (0-254, NEVER 255)</li>
     * </ul>
     *
     * @param roughness The scalar linear roughness value (0.0 - 1.0).
     * @param metallic  The scalar metallic value (0.0 - 1.0).
     * @return The OpenGL Texture ID.
     */
    public static int generateMetallicRoughnessMap(float roughness, float metallic) {
        // Clamp inputs to safe bounds
        roughness = Math.max(0.0f, Math.min(1.0f, roughness));
        metallic = Math.max(0.0f, Math.min(1.0f, metallic));

        // R: Perceptual Smoothness (LabPBR 1.3 conversion)
        float smoothness = 1.0f - (float)Math.sqrt(roughness);
        byte red = (byte)Math.round(smoothness * 255.0f);

        // G: Reflectance (F0)
        // Use 255 for metals, or a fixed 4% (0.04) for non-metals
        byte green = (metallic > 0.5f)
                ? (byte)255
                : (byte)Math.round(0.04f * 229.0f);

        // B: Porosity (Unused)
        byte blue = (byte)0;

        // A: Emissive (Unused)
        // Important: Stay below 255 to avoid triggering LabPBR special flags
        byte alpha = (byte)0;

        return create1x1Texture(red, green, blue, alpha);
    }

    /**
     * Helper to allocate a 1x1 texture in native memory and upload it to the GPU.
     * Uses RGBA8 format and Nearest filtering to prevent interpolation artifacts on single values.
     *
     * @param r Red byte (0-255).
     * @param g Green byte (0-255).
     * @param b Blue byte (0-255).
     * @param a Alpha byte (0-255).
     * @return The OpenGL Texture ID.
     */
    private static int create1x1Texture(byte r, byte g, byte b, byte a) {
        int textureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);

        // Nearest filtering is required for single-value lookup
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