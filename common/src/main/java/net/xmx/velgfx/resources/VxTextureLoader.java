/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.resources;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.xmx.velgfx.renderer.VelGFX;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * A custom texture loader that bypasses Minecraft's TextureManager.
 * <p>
 * This loader handles loading images from the classpath using {@link VxResourceLocation},
 * uploading them to the GPU, and caching the resulting texture IDs.
 * It supports arbitrary file names and paths.
 *
 * @author xI-Mx-Ix
 */
public class VxTextureLoader {

    private static final Map<VxResourceLocation, Integer> TEXTURE_CACHE = new HashMap<>();

    /**
     * Retrieves or loads the OpenGL texture ID for the given location.
     *
     * @param location The custom resource location of the texture.
     * @return The OpenGL texture ID.
     */
    public static int getTexture(VxResourceLocation location) {
        if (TEXTURE_CACHE.containsKey(location)) {
            return TEXTURE_CACHE.get(location);
        }

        int textureId = loadTexture(location);
        TEXTURE_CACHE.put(location, textureId);
        return textureId;
    }

    /**
     * Loads the image from the stream, uploads it to OpenGL, and returns the ID.
     */
    private static int loadTexture(VxResourceLocation location) {
        // Use the system classloader to find the resource, bypassing MC's domain checks
        String path = location.getPath();
        
        // Ensure path starts with root if checking classpath directly
        String classpathPath = path.startsWith("/") ? path : "/" + path;

        try (InputStream stream = VxTextureLoader.class.getResourceAsStream(classpathPath)) {
            if (stream == null) {
                VelGFX.LOGGER.error("Texture not found: {}", location);
                return generateMissingTexture();
            }

            NativeImage image = NativeImage.read(stream);
            int textureId = TextureUtil.generateTextureId();
            
            RenderSystem.bindTexture(textureId);
            
            // Upload to GPU
            // Using TextureUtil.prepareImage helps setup mipmaps and parameters automatically
            TextureUtil.prepareImage(textureId, image.getWidth(), image.getHeight());
            image.upload(0, 0, 0, 0, 0, image.getWidth(), image.getHeight(), false, false);
            
            // Set standard filtering parameters
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);

            return textureId;

        } catch (IOException e) {
            VelGFX.LOGGER.error("Failed to load texture: {}", location, e);
            return generateMissingTexture();
        }
    }

    /**
     * Generates a 1x1 magenta texture to indicate missing assets.
     */
    private static int generateMissingTexture() {
        int textureId = TextureUtil.generateTextureId();
        RenderSystem.bindTexture(textureId);
        
        try (NativeImage missing = new NativeImage(1, 1, false)) {
            missing.setPixelRGBA(0, 0, 0xFF00FFFF); // ABGR format: Full Alpha, Blue=255, Red=255
            missing.upload(0, 0, 0, 0, 0, 1, 1, false, false);
        }
        
        return textureId;
    }

    /**
     * Clears the cache and deletes all textures from GPU memory.
     */
    public static void clear() {
        for (int id : TEXTURE_CACHE.values()) {
            TextureUtil.releaseTextureId(id);
        }
        TEXTURE_CACHE.clear();
    }
}