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
     * Generates a 1x1 pixel texture representing scalar Metallic and Roughness values.
     * <p>
     * <b>Format (LabPBR):</b>
     * <ul>
     *     <li>Red: Smoothness (1.0 - Roughness)</li>
     *     <li>Green: Metallic</li>
     *     <li>Blue: Reserved (0)</li>
     *     <li>Alpha: Emissive Strength (Must be 0 for fallback, otherwise model glows)</li>
     * </ul>
     *
     * @param roughness The scalar roughness value (0.0 - 1.0).
     * @param metallic  The scalar metallic value (0.0 - 1.0).
     * @return The OpenGL Texture ID.
     */
    public static int generateMetallicRoughnessMap(float roughness, float metallic) {
        byte r = (byte) ((1.0f - roughness) * 255.0f);
        byte g = (byte) (metallic * 255.0f);
        return create1x1Texture(r, g, (byte) 0, (byte) 0);
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