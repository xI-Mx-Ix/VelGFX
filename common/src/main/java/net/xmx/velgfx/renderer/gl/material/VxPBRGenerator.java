/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.material;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

/**
 * A utility class responsible for generating procedural PBR textures on the GPU.
 * <p>
 * This class handles the creation of default 1x1 textures for missing normal maps
 * and the generation of LabPBR 1.3 compliant specular maps based on scalar
 * material properties (roughness, metallic).
 *
 * @author xI-Mx-Ix
 */
public final class VxPBRGenerator {

    /**
     * Private constructor to prevent instantiation.
     */
    private VxPBRGenerator() {}

    /**
     * Generates a 1x1 pixel flat normal map.
     * <p>
     * <b>Value:</b> RGB(128, 128, 255) -> Vector(0, 0, 1).
     *
     * @return The OpenGL texture ID.
     */
    public static int generateFlatNormalMap() {
        // Flat normal: X=0, Y=0, Z=1 -> mapped to 0..255 is 128, 128, 255
        return create1x1Texture((byte) 128, (byte) 128, (byte) 255, (byte) 255);
    }

    /**
     * Generates a 1x1 pixel LabPBR 1.3 compliant specular map based on scalar factors.
     * <p>
     * <b>Channel Mapping:</b>
     * <ul>
     *     <li><b>Red:</b> Perceptual Smoothness (1.0 - Roughness).</li>
     *     <li><b>Green:</b> F0 (Reflectance). 230 for metals, 10 for dielectrics.</li>
     *     <li><b>Blue:</b> Porosity (0).</li>
     *     <li><b>Alpha:</b> Emission (0).</li>
     * </ul>
     *
     * @param roughness The roughness factor (0.0 = smooth, 1.0 = rough).
     * @param metallic  The metallic factor (0.0 = dielectric, 1.0 = metal).
     * @return The OpenGL texture ID.
     */
    public static int generateLabPBRSpecularMap(float roughness, float metallic) {
        // 1. Red: Smoothness
        float smoothness = 1.0f - roughness;
        byte r = (byte) (smoothness * 255.0f);

        // 2. Green: F0 (Reflectance)
        byte g;
        if (metallic > 0.5f) {
            // Metal: LabPBR ID 230 (Generic Iron)
            g = (byte) 230;
        } else {
            // Dielectric: ~0.04 linear -> ~10/255
            g = (byte) 10;
        }

        // 3. Blue: Porosity (Default 0)
        byte b = 0;

        // 4. Alpha: Emission (Default 0)
        byte a = 0;

        return create1x1Texture(r, g, b, a);
    }

    /**
     * Creates a single-pixel 2D texture on the GPU with the specified RGBA values.
     * Uses LWJGL MemoryStack for efficient off-heap buffer management.
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

        // Configure texture parameters (Nearest filter is sufficient for 1x1)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);

        // Push a new stack frame to allocate a short-lived direct buffer
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer buffer = stack.malloc(4); // 4 bytes for RGBA
            buffer.put(r);
            buffer.put(g);
            buffer.put(b);
            buffer.put(a);
            buffer.flip(); // Prepare for reading

            // Upload data to the GPU
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        }

        // Unbind texture to prevent accidental modification
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        return textureId;
    }
}