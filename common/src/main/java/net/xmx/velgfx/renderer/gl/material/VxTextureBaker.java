/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.material;

import net.xmx.velgfx.renderer.VelGFX;
import net.xmx.velgfx.renderer.gl.shader.VxPBRConverterShader;
import net.xmx.velgfx.resources.VxNativeImage;
import net.xmx.velgfx.resources.VxTextureLoader;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * A GPU-accelerated utility responsible for "baking" raw material data into optimized PBR textures.
 * <p>
 * This class handles the conversion from glTF-standard images (split channels) into the
 * specific packed formats required by the render pipeline (e.g., LabPBR Specular maps).
 * It utilizes OpenGL Framebuffers (FBO) and "Vertex-Pulling" shaders to perform these
 * operations entirely in VRAM, avoiding expensive CPU-side pixel iteration.
 *
 * @author xI-Mx-Ix
 */
public final class VxTextureBaker {

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private VxTextureBaker() {
    }

    /**
     * Bakes the final PBR textures for a material on the GPU.
     * <p>
     * The process involves:
     * <ol>
     *     <li>Uploading raw source images to temporary OpenGL textures.</li>
     *     <li>Creating a Framebuffer with two Color Attachments (Albedo, Specular).</li>
     *     <li>Executing a full-screen shader pass (using VertexID generation).</li>
     *     <li>Storing the resulting texture IDs in the material object.</li>
     *     <li>Cleaning up temporary resources (source textures, FBO).</li>
     * </ol>
     *
     * @param material     The material object to populate with generated texture IDs.
     * @param albedoImg    Raw base color image (can be null).
     * @param mrImg        Raw Metallic-Roughness image (can be null).
     * @param occlusionImg Raw Occlusion image (can be null).
     * @param emissiveImg  Raw Emissive image (can be null).
     */
    public static void bakeMaterialTextures(VxMaterial material,
                                            VxNativeImage albedoImg,
                                            VxNativeImage mrImg,
                                            VxNativeImage occlusionImg,
                                            VxNativeImage emissiveImg) {

        // 1. Determine Target Resolution (based on largest input)
        int width = 1;
        int height = 1;

        if (albedoImg != null) {
            width = albedoImg.getWidth();
            height = albedoImg.getHeight();
        } else if (mrImg != null) {
            width = mrImg.getWidth();
            height = mrImg.getHeight();
        } else if (occlusionImg != null) {
            width = occlusionImg.getWidth();
            height = occlusionImg.getHeight();
        } else if (emissiveImg != null) {
            width = emissiveImg.getWidth();
            height = emissiveImg.getHeight();
        }

        // 2. Upload Temporary Source Textures
        int tempAlbedoId = (albedoImg != null) ? VxTextureLoader.uploadTexture(albedoImg) : 0;
        int tempMrId = (mrImg != null) ? VxTextureLoader.uploadTexture(mrImg) : 0;
        int tempOccId = (occlusionImg != null) ? VxTextureLoader.uploadTexture(occlusionImg) : 0;
        int tempEmissiveId = (emissiveImg != null) ? VxTextureLoader.uploadTexture(emissiveImg) : 0;

        // 3. Create Target Textures (Empty containers)
        int bakedAlbedoId = createEmptyTexture(width, height);
        int bakedSpecularId = createEmptyTexture(width, height);

        // 4. Setup Framebuffer with Multiple Render Targets (MRT)
        int fboId = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);

        // Attach textures to color attachment points
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, bakedAlbedoId, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, bakedSpecularId, 0);

        // Configure Draw Buffers to write to both attachments
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer drawBuffers = stack.mallocInt(2);
            drawBuffers.put(GL30.GL_COLOR_ATTACHMENT0);
            drawBuffers.put(GL30.GL_COLOR_ATTACHMENT1);
            drawBuffers.flip();
            GL20.glDrawBuffers(drawBuffers);
        }

        // Validate FBO status
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            VelGFX.LOGGER.error("PBR Baking FBO incomplete! Aborting bake.");
            // Material will fallback to CPU generation in ensureGenerated()
            cleanup(fboId, tempAlbedoId, tempMrId, tempOccId, tempEmissiveId);
            return;
        }

        // 5. Setup Rendering State
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer viewport = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);

            // Set Viewport to the size of our target textures
            GL11.glViewport(0, 0, width, height);

            // Disable depth testing and blending for pure data copy
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_BLEND);

            // 6. Bind Shader & Set Uniforms
            VxPBRConverterShader shader = VelGFX.getShaderManager().getPBRConverterShader();
            shader.bind();

            // Set Scalar Factors
            int locBase = shader.getUniformLocation("u_BaseColorFactor");
            GL20.glUniform4fv(locBase, material.baseColorFactor);

            shader.setUniform("u_EmissiveFactor", new Vector3f(
                    material.emissiveFactor[0],
                    material.emissiveFactor[1],
                    material.emissiveFactor[2]
            ));
            shader.setUniform("u_RoughnessFactor", material.roughnessFactor);
            shader.setUniform("u_MetallicFactor", material.metallicFactor);
            shader.setUniform("u_OcclusionStrength", material.occlusionStrength);

            // Set Textures & Toggle Switches
            bindAndSetUniform(shader, "u_TexAlbedo", "u_HasAlbedo", 0, tempAlbedoId);
            bindAndSetUniform(shader, "u_TexMR", "u_HasMR", 1, tempMrId);
            bindAndSetUniform(shader, "u_TexOcclusion", "u_HasOcclusion", 2, tempOccId);
            bindAndSetUniform(shader, "u_TexEmissive", "u_HasEmissive", 3, tempEmissiveId);

            // 7. Render Fullscreen Triangle (VertexID approach)
            renderFullscreenTriangle();

            shader.unbind();

            // Restore Viewport
            GL11.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        }

        // 8. Assign Results to Material
        material.albedoMapGlId = bakedAlbedoId;
        material.specularMapGlId = bakedSpecularId;

        // 9. Cleanup Temporary Resources
        cleanup(fboId, tempAlbedoId, tempMrId, tempOccId, tempEmissiveId);
    }

    /**
     * Helper to allocate an empty RGBA8 texture on the GPU.
     * Uses Linear filtering.
     *
     * @param w Width in pixels.
     * @param h Height in pixels.
     * @return The OpenGL Texture ID.
     */
    private static int createEmptyTexture(int w, int h) {
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        // Null buffer allocates storage without data
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return id;
    }

    /**
     * Binds a texture to a specific unit and updates the corresponding shader uniforms.
     */
    private static void bindAndSetUniform(VxPBRConverterShader shader, String samplerName, String boolName, int unit, int textureId) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        if (textureId != 0) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            shader.setUniform(samplerName, unit);
            shader.setUniform(boolName, true);
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            shader.setUniform(boolName, false);
        }
    }

    /**
     * Deletes the framebuffer and temporary source textures.
     */
    private static void cleanup(int fboId, int t1, int t2, int t3, int t4) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL30.glDeleteFramebuffers(fboId);
        if (t1 != 0) GL11.glDeleteTextures(t1);
        if (t2 != 0) GL11.glDeleteTextures(t2);
        if (t3 != 0) GL11.glDeleteTextures(t3);
        if (t4 != 0) GL11.glDeleteTextures(t4);
    }

    /**
     * Triggers the vertex shader to generate a full-screen triangle.
     * <p>
     * This method relies on the "Vertex ID" trick. It binds an empty VAO
     * (required by Core Profile) and issues a draw call for 3 vertices.
     * The shader calculates the coordinates, so no VBO upload is needed.
     */
    private static void renderFullscreenTriangle() {
        // Core Profile requires a VAO to be bound, even if it's empty/unused.
        int vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);

        // Draw 3 vertices -> The Vertex Shader creates the triangle from IDs 0, 1, 2
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

        GL30.glBindVertexArray(0);
        GL30.glDeleteVertexArrays(vao);
    }
}