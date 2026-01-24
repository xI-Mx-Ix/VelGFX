/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.mesh.arena.skinning;

import com.mojang.blaze3d.systems.RenderSystem;
import net.xmx.velgfx.renderer.VelGFX;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

import java.nio.FloatBuffer;

/**
 * Manages a global Texture Buffer Object (TBO) that stores all Morph Target deltas.
 * <p>
 * This buffer acts as a massive linear array of floats on the GPU, accessible via
 * {@code samplerBuffer} in the shader. Unlike 2D textures, TBOs are not limited by
 * texture dimensions and are accessed via integer indices.
 * <p>
 * <b>Layout per Vertex per Target (Interleaved):</b>
 * <ul>
 *     <li>Texel 0: Position Delta (x, y, z)</li>
 *     <li>Texel 1: Normal Delta (x, y, z)</li>
 *     <li>Texel 2: Tangent Delta (x, y, z)</li>
 * </ul>
 * Format: GL_RGB32F (3 floats per texel).
 *
 * @author xI-Mx-Ix
 */
public class VxMorphTextureAtlas {

    private static VxMorphTextureAtlas instance;

    /**
     * Max buffer size (e.g., ~72MB of morph data).
     * 2 million texels * 3 floats/texel * 4 bytes/float.
     */
    private static final int INITIAL_CAPACITY_TEXELS = 2_000_000;

    private int tboId;
    private int textureId;
    private int capacityTexels;
    private int usedTexels;

    private VxMorphTextureAtlas() {
        this.capacityTexels = INITIAL_CAPACITY_TEXELS;
        initialize();
    }

    /**
     * Gets the global singleton instance.
     *
     * @return The atlas instance.
     */
    public static synchronized VxMorphTextureAtlas getInstance() {
        if (instance == null) {
            instance = new VxMorphTextureAtlas();
        }
        return instance;
    }

    /**
     * Initializes the GL resources (Buffer and Texture Wrapper).
     */
    private void initialize() {
        RenderSystem.assertOnRenderThread();

        // 1. Create Buffer Object (GL15)
        this.tboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, tboId);

        // Allocate empty storage (DYNAMIC_DRAW as we might append)
        // Size in bytes: Texels * 3 components (RGB) * 4 bytes (Float)
        long sizeBytes = (long) capacityTexels * 3 * 4;
        GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, sizeBytes, GL15.GL_STATIC_DRAW);

        // 2. Create Texture Wrapper (GL11)
        this.textureId = GL11.glGenTextures();
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, textureId);

        // Link Buffer to Texture with RGB32F format (GL31)
        // RGB32F is required to store raw floats without normalization.
        GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGB32F, tboId);

        // Unbind
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
    }

    /**
     * Uploads morph data for a mesh into the atlas.
     *
     * @param data The interleaved delta data [Px, Py, Pz, Nx, Ny, Nz, Tx, Ty, Tz, ...].
     * @return The start index (offset) in Texels within the TBO.
     */
    public synchronized int upload(float[] data) {
        RenderSystem.assertOnRenderThread();

        // 3 floats per texel (RGB)
        int requiredTexels = data.length / 3;

        if (usedTexels + requiredTexels > capacityTexels) {
            resize(Math.max(capacityTexels * 2, usedTexels + requiredTexels));
        }

        int startOffsetTexels = usedTexels;
        long startOffsetBytes = (long) startOffsetTexels * 3 * 4;

        FloatBuffer buffer = BufferUtils.createFloatBuffer(data.length);
        buffer.put(data);
        buffer.flip();

        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, tboId);
        GL15.glBufferSubData(GL31.GL_TEXTURE_BUFFER, startOffsetBytes, buffer);
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);

        usedTexels += requiredTexels;
        return startOffsetTexels;
    }

    /**
     * Resizes the GPU buffer when it runs out of space, preserving existing data.
     *
     * @param newCapacity The new capacity in texels.
     */
    private void resize(int newCapacity) {
        VelGFX.LOGGER.warn("Resizing Morph Atlas TBO from {} to {} texels", capacityTexels, newCapacity);

        int newTbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, newTbo);
        GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, (long) newCapacity * 3 * 4, GL15.GL_STATIC_DRAW);

        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, this.tboId);
        // Copy the used portion of the old buffer to the new one
        GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0, 0, (long) usedTexels * 3 * 4);

        // Destroy old resources
        GL15.glDeleteBuffers(this.tboId);
        GL11.glDeleteTextures(this.textureId);

        this.tboId = newTbo;
        this.capacityTexels = newCapacity;

        // Re-create Texture Wrapper for the new buffer
        this.textureId = GL11.glGenTextures();
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, textureId);
        GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGB32F, tboId);

        // Cleanup state
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
    }

    /**
     * Binds the TBO to the specified texture unit.
     *
     * @param textureUnit The texture unit index (e.g. 0 for GL_TEXTURE0).
     */
    public void bind(int textureUnit) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + textureUnit);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, textureId);
    }

    /**
     * Deletes the GL resources.
     */
    public void delete() {
        RenderSystem.assertOnRenderThread();
        GL15.glDeleteBuffers(tboId);
        GL11.glDeleteTextures(textureId);
        instance = null;
    }
}