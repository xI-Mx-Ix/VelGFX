/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.resources;

import com.mojang.blaze3d.systems.RenderSystem;
import net.xmx.velgfx.VelGFX;
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
 * This class handles loading image data from the Java Classpath via {@link VxResourceLocation},
 * decoding it using {@link VxNativeImage}, and uploading the raw bytes to the GPU
 * using direct {@link GL11} commands.
 * <p>
 * It includes robust state management to ensure that pixel data is read correctly
 * regardless of the previous state of the OpenGL context.
 *
 * @author xI-Mx-Ix
 */
public class VxTextureLoader {

    /**
     * Internal cache mapping resource locations to their generated OpenGL texture IDs.
     * This prevents reloading the same asset multiple times from disk.
     */
    private static final Map<VxResourceLocation, Integer> TEXTURE_CACHE = new HashMap<>();

    /**
     * The resource location for the fallback white texture.
     * This is used when a material does not define a diffuse texture to ensure
     * proper lighting calculations (multiplication by 1.0).
     */
    private static final VxResourceLocation WHITE_TEXTURE_LOCATION = new VxResourceLocation("assets/velgfx/renderer/white.png");

    // Colors used for the missing texture pattern (Magenta/Black Checkerboard).
    // Format: 0xAABBGGRR (Alpha, Blue, Green, Red)
    private static final int MAGENTA = 0xFFFF00FF;
    private static final int BLACK   = 0xFF000000;

    /**
     * Uploads a native image to the GPU and returns the texture ID without caching it
     * under a ResourceLocation.
     * <p>
     * This is primarily used for embedded model textures where no file path exists.
     *
     * @param image The image to upload.
     * @return The generated OpenGL texture ID.
     */
    public static int uploadTexture(VxNativeImage image) {
        RenderSystem.assertOnRenderThread();
        return uploadToGPU(image);
    }

    /**
     * Retrieves or loads the OpenGL texture ID for the given location.
     * <p>
     * If the texture has already been loaded, the cached ID is returned immediately.
     * Otherwise, the file is loaded from the classpath, decoded, and uploaded to VRAM.
     * <p>
     * If the provided location is null, the default white texture is returned.
     *
     * @param location The custom resource location of the texture.
     * @return The OpenGL texture ID handle.
     */
    public static int getTexture(VxResourceLocation location) {
        // If no texture is defined in the model/material, use the 1x1 white texture.
        VxResourceLocation targetLocation = (location != null) ? location : WHITE_TEXTURE_LOCATION;

        if (TEXTURE_CACHE.containsKey(targetLocation)) {
            return TEXTURE_CACHE.get(targetLocation);
        }

        // Ensure we are on the render thread before performing any GL operations.
        RenderSystem.assertOnRenderThread();

        int textureId = loadTexture(targetLocation);
        TEXTURE_CACHE.put(targetLocation, textureId);
        return textureId;
    }

    /**
     * loads the image stream from the classpath, decodes it, and uploads it.
     * If the file is missing, a checkerboard pattern is generated instead.
     *
     * @param location The resource path.
     * @return The GL Texture ID.
     */
    private static int loadTexture(VxResourceLocation location) {
        String path = location.getPath();
        // Ensure the path is absolute for ClassLoader lookup
        String classpathPath = path.startsWith("/") ? path : "/" + path;

        // Try loading the raw bytes from the JAR/Classpath
        try (InputStream stream = VxTextureLoader.class.getResourceAsStream(classpathPath)) {
            if (stream == null) {
                VelGFX.LOGGER.error("Texture not found in classpath: {}", location);
                return generateMissingTexture();
            }

            // Decode image using the custom STB wrapper.
            // VxNativeImage is configured to always return 4-channel RGBA data.
            try (VxNativeImage image = VxNativeImage.read(stream)) {
                return uploadToGPU(image);
            }

        } catch (IOException e) {
            VelGFX.LOGGER.error("Failed to load texture: {}", location, e);
            return generateMissingTexture();
        }
    }

    /**
     * Generates a 2x2 magenta/black checkerboard texture to visually indicate missing assets.
     * <p>
     * Pattern:<br>
     * [Magenta, Black]<br>
     * [Black, Magenta]
     */
    private static int generateMissingTexture() {
        try (VxNativeImage image = VxNativeImage.create(2, 2)) {
            image.setPixelRGBA(0, 0, MAGENTA);
            image.setPixelRGBA(1, 0, BLACK);
            image.setPixelRGBA(0, 1, BLACK);
            image.setPixelRGBA(1, 1, MAGENTA);

            return uploadToGPU(image);
        }
    }

    /**
     * Performs the low-level OpenGL operations to allocate a texture handle
     * and upload the pixel data from the buffer.
     *
     * @param image The decoded image data containing width, height, and the pixel buffer.
     * @return The generated GL texture ID.
     */
    private static int uploadToGPU(VxNativeImage image) {
        // 1. Generate a new Texture Object ID
        int textureId = GL11.glGenTextures();

        // 2. Bind the texture to the 2D target to modify its state
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);

        // 3. Configure Texture Parameters
        // Linear filtering with Mipmaps for Minification (smooths distant textures)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        // Linear filtering for Magnification (smooths close-up textures)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

        // Repeat wrapping ensures textures tile correctly across geometry
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);

        // Limit Mipmap levels to 4 to balance visual quality and memory usage
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 4);

        // 4. Prepare Data for Upload
        ByteBuffer data = image.getPixelData();
        // Rewind buffer to position 0 to ensure the read starts at the beginning
        data.rewind();

        // 5. Reset Pixel Storage Modes
        // This step is critical. OpenGL maintains global state for how it unpacks pixel data.
        // If other parts of the rendering engine (e.g. font rendering) set specific row lengths
        // or skip values, they must be reset to 0 here.
        // This ensures OpenGL reads the buffer as a tightly packed, contiguous array of bytes.
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);

        // Ensure 4-byte alignment, as RGBA data is always 4-byte aligned (32 bits per pixel)
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);

        try {
            // 6. Upload the texture data to the GPU
            GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,     // Target
                    0,                      // Level 0 (Base level)
                    GL11.GL_RGBA8,          // Internal Format (GPU storage format)
                    image.getWidth(),       // Width
                    image.getHeight(),      // Height
                    0,                      // Border (Must be 0)
                    GL11.GL_RGBA,           // Format (Input data format)
                    GL11.GL_UNSIGNED_BYTE,  // Type (Input data type per channel)
                    data                    // The actual pixel data
            );
        } finally {
            // Restore default alignment (Standard practice to leave state clean)
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        }

        // 7. Generate Mipmaps based on the uploaded Level 0 data
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);

        // 8. Unbind the texture to prevent accidental modification by subsequent GL calls
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        return textureId;
    }

    /**
     * Deletes all textures managed by this loader from GPU memory.
     * <p>
     * This should be called during resource reloads or when the engine shuts down
     * to prevent VRAM leaks.
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