/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.resources;

import com.mojang.blaze3d.systems.RenderSystem;
import net.xmx.velgfx.renderer.VelGFX;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * A custom texture loader that manages the lifecycle of OpenGL textures entirely
 * decoupled from Minecraft's TextureManager and NativeImage system.
 * <p>
 * It handles loading from the classpath via {@link VxResourceLocation}, decoding via
 * {@link VxNativeImage}, and uploading to the GPU using direct {@link GL11} calls.
 *
 * @author xI-Mx-Ix
 */
public class VxTextureLoader {

    /**
     * Cache mapping resource locations to OpenGL texture IDs.
     */
    private static final Map<VxResourceLocation, Integer> TEXTURE_CACHE = new HashMap<>();
    
    // Magenta (0xAABBGGRR): A=FF, B=FF, G=00, R=FF
    private static final int MAGENTA = 0xFFFF00FF; 
    private static final int BLACK   = 0xFF000000;

    /**
     * Retrieves or loads the OpenGL texture ID for the given location.
     * <p>
     * If the texture has already been loaded, the cached ID is returned.
     * Otherwise, it is loaded from disk (classpath) and uploaded to VRAM.
     *
     * @param location The custom resource location of the texture.
     * @return The OpenGL texture ID.
     */
    public static int getTexture(VxResourceLocation location) {
        if (TEXTURE_CACHE.containsKey(location)) {
            return TEXTURE_CACHE.get(location);
        }

        // Ensure we are on the render thread before performing GL operations
        RenderSystem.assertOnRenderThread();

        int textureId = loadTexture(location);
        TEXTURE_CACHE.put(location, textureId);
        return textureId;
    }

    /**
     * Loads the image from the stream, uploads it to OpenGL, and returns the ID.
     */
    private static int loadTexture(VxResourceLocation location) {
        String path = location.getPath();
        // Ensure absolute classpath path
        String classpathPath = path.startsWith("/") ? path : "/" + path;

        // Try loading from classpath
        try (InputStream stream = VxTextureLoader.class.getResourceAsStream(classpathPath)) {
            if (stream == null) {
                VelGFX.LOGGER.error("Texture not found in classpath: {}", location);
                return generateMissingTexture();
            }

            // Decode image using our custom STB wrapper
            try (VxNativeImage image = VxNativeImage.read(stream)) {
                return uploadToGPU(image);
            }

        } catch (IOException e) {
            VelGFX.LOGGER.error("Failed to load texture: {}", location, e);
            return generateMissingTexture();
        }
    }

    /**
     * Generates a 2x2 magenta/black checkerboard texture to indicate missing assets.
     * <p>
     * Pattern:<br>
     * [Magenta, Black]<br>
     * [Black, Magenta]
     */
    private static int generateMissingTexture() {
        // Create a 2x2 blank image
        try (VxNativeImage image = VxNativeImage.create(2, 2)) {
            image.setPixelRGBA(0, 0, MAGENTA);
            image.setPixelRGBA(1, 0, BLACK);
            image.setPixelRGBA(0, 1, BLACK);
            image.setPixelRGBA(1, 1, MAGENTA);
            
            return uploadToGPU(image);
        }
    }

    /**
     * Performs the actual OpenGL calls to create and populate the texture.
     *
     * @param image The decoded image data.
     * @return The GL texture ID.
     */
    private static int uploadToGPU(VxNativeImage image) {
        // 1. Generate Texture ID
        int textureId = GL11.glGenTextures();
        
        // 2. Bind Texture to Texture Unit 0 (state safe)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);

        // 3. Set Texture Parameters
        // Linear filtering with Mipmaps for Minification
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        // Linear filtering for Magnification
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        
        // Repeat wrapping ensures textures tile correctly on models
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        
        // Limit Mipmap levels to 4 to save memory, usually sufficient for object textures
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 4);

        // 4. Upload Data
        // STB loads as RGBA, 8 bits per channel.
        ByteBuffer data = image.getPixelData();
        
        // glPixelStorei handles data alignment. Default is 4, which is fine for RGBA (4 bytes).
        // If width was odd and RGB (3 bytes), we'd need to set UNPACK_ALIGNMENT to 1.
        // Since we force RGBA, alignment is always 4-byte aligned per pixel.

        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 
            0,                  // Mipmap Level 0 (Base)
            GL11.GL_RGBA8,      // Internal Format (How GPU stores it)
            image.getWidth(), 
            image.getHeight(), 
            0,                  // Border (Must be 0)
            GL11.GL_RGBA,       // Format (How we provide it)
            GL11.GL_UNSIGNED_BYTE, // Type (Byte per channel)
            data
        );

        // 5. Generate Mipmaps automatically
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);

        // 6. Unbind to prevent accidental modification
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        return textureId;
    }

    /**
     * Clears the cache and deletes all textures from GPU memory using direct GL calls.
     * Should be called on resource reload or shutdown.
     */
    public static void clear() {
        RenderSystem.assertOnRenderThread();
        
        for (int id : TEXTURE_CACHE.values()) {
            GL11.glDeleteTextures(id);
        }
        TEXTURE_CACHE.clear();
        VelGFX.LOGGER.info("VxTextureLoader: Cleared texture cache and released GPU memory.");
    }
}