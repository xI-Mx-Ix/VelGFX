/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.xmx.velgfx.renderer.VelGFX;
import net.xmx.velgfx.renderer.gl.VxGlState;
import net.xmx.velgfx.renderer.gl.VxIndexBuffer;
import net.xmx.velgfx.renderer.gl.material.VxMaterial;
import net.xmx.velgfx.renderer.gl.shader.impl.VxVanillaExtendedShader;
import net.xmx.velgfx.renderer.gl.state.VxBlendMode;
import net.xmx.velgfx.renderer.gl.state.VxRenderType;
import net.xmx.velgfx.renderer.util.VxTempCache;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.*;

import java.nio.FloatBuffer;

/**
 * The rendering backend responsible for the Vanilla Minecraft environment.
 * <p>
 * This pipeline executes batched draw calls when no external shader pack is active.
 * <p>
 * Key responsibilities include:
 * <ul>
 *     <li>Dynamic lighting calculation based on World Time.</li>
 *     <li>Management of OpenGL states (Blending, Culling, Depth).</li>
 *     <li>Real-time calculation of Matrix data (ModelView and Normal matrices) using a thread-local cache.</li>
 *     <li>Binding of PBR textures (Normal, Specular) alongside standard Albedo.</li>
 * </ul>
 *
 * @author xI-Mx-Ix
 */
public class VxVanillaRenderer {

    /**
     * The vertex attribute location for the Lightmap UV coordinates (Block/Sky light).
     * This corresponds to the standard Minecraft generic attribute location.
     */
    private static final int AT_UV2 = 4;

    /**
     * Resets any cached rendering states.
     * Should be called when the pipeline is initialized or reloaded.
     */
    public void reset() {
        VxBlendMode.resetState();
    }

    /**
     * Executes the rendering logic for a specific batch (bucket) of draw calls.
     * <p>
     * This method orchestrates the entire pipeline: preserving OpenGL state,
     * calculating lighting based on the game time, setting up the custom shader,
     * and iterating through the provided batch to draw meshes.
     *
     * @param store       The data store containing all mesh data (matrices, buffers, materials).
     * @param bucket      The list of indices within the store that need to be rendered in this pass.
     * @param viewMatrix  The current camera View Matrix.
     * @param projMatrix  The current camera Projection Matrix.
     * @param translucent {@code true} if rendering translucent geometry (enables blending),
     *                    {@code false} for opaque or cutout geometry.
     */
    public void render(VxRenderDataStore store, VxRenderDataStore.IntList bucket, Matrix4f viewMatrix, Matrix4f projMatrix, boolean translucent) {
        RenderSystem.assertOnRenderThread();

        // 1. Snapshot the current OpenGL texture state.
        // We capture the bindings of Texture Units 0-4. This allows us to modify them
        // freely during rendering and restore them perfectly afterwards, preventing
        // conflicts with Minecraft's main rendering engine.
        VxGlState.saveCurrentState();
        int[] savedTextureState = VxRenderPipeline.getInstance().captureTextureState();

        // 2. Prepare the custom shader program.
        VxVanillaExtendedShader shader = VelGFX.getShaderManager().getVanillaExtendedShader();

        // 3. Acquire Scratch Objects from Cache.
        // This avoids creating new Matrix4f/Vector3f objects every frame, eliminating GC pressure.
        VxTempCache cache = VxTempCache.get();
        Matrix4f auxViewRotationOnly = cache.mat4_4;
        Vector3f auxLight0 = cache.vec3_1;
        Vector3f auxLight1 = cache.vec3_2;
        FloatBuffer matrixBuffer = cache.floatBuffer16;

        try {
            shader.bind();

            // 4. Upload standard environment uniforms.
            // These values define global fog settings and projection parameters.
            shader.setUniform("ProjMat", projMatrix);

            // Upload the View Matrix globally. The shader will combine this with
            // the per-object Model Matrix to create the ModelView Matrix.
            shader.setUniform("ViewMat", viewMatrix);

            shader.setUniform("ColorModulator", RenderSystem.getShaderColor());
            shader.setUniform("FogStart", RenderSystem.getShaderFogStart());
            shader.setUniform("FogEnd", RenderSystem.getShaderFogEnd());
            shader.setUniform("FogColor", RenderSystem.getShaderFogColor());
            shader.setUniform("FogShape", RenderSystem.getShaderFogShape().getIndex());

            // 5. Calculate Dynamic Lighting Directions.
            // We determine the sun's position based on the current world time.
            // This ensures specular highlights on blocks align with the celestial bodies.
            float sunAngle = 0.0f;
            if (Minecraft.getInstance().level != null) {
                // getSunAngle returns radians (0 to 2PI), where 0 represents noon.
                sunAngle = Minecraft.getInstance().level.getSunAngle(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true));
            }

            // Calculate Sun Vector in World Space (Simple rotation around Z-axis logic).
            float sunX = (float) Math.sin(sunAngle);
            float sunY = (float) Math.cos(sunAngle);
            float sunZ = 0.0f;

            // Set Light 0 (Sun)
            auxLight0.set(sunX, sunY, sunZ).normalize();

            // Set Light 1 (Moon) - Opposite to Sun
            auxLight1.set(-sunX, -sunY, -sunZ).normalize();

            // Transform Lights to View Space.
            // The shader expects light directions relative to the camera (View Space).
            // We take the View Matrix, strip the translation (position), and apply the rotation.
            auxViewRotationOnly.set(viewMatrix);
            auxViewRotationOnly.setTranslation(0, 0, 0);

            auxViewRotationOnly.transformDirection(auxLight0);
            shader.setUniform("Light0_Direction", auxLight0);

            auxViewRotationOnly.transformDirection(auxLight1);
            shader.setUniform("Light1_Direction", auxLight1);

            // Reset the generic vertex color attribute to pure white (1.0).
            // This ensures that meshes without vertex colors aren't tinted unexpectedly.
            GL30.glVertexAttrib4f(1, 1.0f, 1.0f, 1.0f, 1.0f);

            // 6. Configure Global Textures.
            // These textures are bound once per batch as they usually don't change per object.

            // Unit 1: Overlay Texture (Used for damage tinting / red flash).
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, RenderSystem.getShaderTexture(1));
            shader.setUniform("Sampler1", 1);

            // Unit 2: Lightmap Texture (Contains Sky Light and Block Light).
            Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, RenderSystem.getShaderTexture(2));
            shader.setUniform("Sampler2", 2);

            // Unit 3: Specular/Emissive Map (PBR Data).
            shader.setUniform("Sampler3", 3);

            // Unit 4: Normal Map (Tangent Space).
            shader.setUniform("Sampler4", 4);

            // Unit 0: Albedo/Base Color.
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            shader.setUniform("Sampler0", 0);

            // 7. Set up Render State for Translucency.
            if (translucent) {
                // For translucent objects (water, stained glass), we enable alpha blending
                // and disable depth writing to prevent occlusion artifacts.
                RenderSystem.enableBlend();
                RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                RenderSystem.depthMask(false);
                shader.setUniform("AlphaCutoff", 0.0f);
            } else {
                // For opaque objects, we initialize the alpha cutoff to 0.
                shader.setUniform("AlphaCutoff", 0.0f);
            }

            // Local state tracking to minimize OpenGL calls inside the loop.
            int currentVao = -1;
            int currentEbo = -1;
            boolean isCullingEnabled = true;

            // Cache the uniform location for the Model Matrix to avoid lookups in the loop.
            int locModelMat = shader.getUniformLocation("ModelMat");

            // --- Inner Render Loop ---
            // Iterate over all batched items in this bucket.
            for (int i = 0; i < bucket.size; i++) {
                int ptr = bucket.data[i];
                VxMaterial mat = store.frameMaterials.get(store.materialIndices[ptr]);

                // 1. Upload Model Matrix.
                // We transfer the raw Model Matrix data directly from the store to the shader.
                // The shader performs the multiplication (View * Model) and Inverse-Transpose.
                // This reduces CPU load significantly.
                matrixBuffer.clear();
                matrixBuffer.put(store.modelMatrices, ptr * 16, 16);
                matrixBuffer.flip();
                GL20.glUniformMatrix4fv(locModelMat, false, matrixBuffer);

                // 2. Bind Mesh Geometry.
                // Only bind the VAO and EBO if they differ from the previously bound mesh.
                int vao = store.vaoIds[ptr];
                int ebo = store.eboIds[ptr];

                if (vao != currentVao) {
                    GL30.glBindVertexArray(vao);
                    currentVao = vao;
                    currentEbo = -1; // Reset EBO tracking when VAO changes
                }
                if (ebo != currentEbo) {
                    GL30.glBindBuffer(GL30.GL_ELEMENT_ARRAY_BUFFER, ebo);
                    currentEbo = ebo;
                }

                // 3. Set Lightmap Coordinates.
                // These are passed as a custom integer attribute (Index 4).
                GL30.glDisableVertexAttribArray(AT_UV2);
                int packedLight = store.packedLights[ptr];
                GL30.glVertexAttribI2i(AT_UV2, packedLight & 0xFFFF, packedLight >> 16);

                // 4. Apply Material Properties.
                // Handle Face Culling (Double-Sided vs Single-Sided).
                boolean shouldCull = !mat.doubleSided;
                if (shouldCull != isCullingEnabled) {
                    if (shouldCull) RenderSystem.enableCull();
                    else RenderSystem.disableCull();
                    isCullingEnabled = shouldCull;
                }

                // Update Alpha Cutoff based on render type.
                if (mat.renderType == VxRenderType.CUTOUT) {
                    shader.setUniform("AlphaCutoff", mat.alphaCutoff);
                } else if (!translucent) {
                    // Reset cutoff for standard opaque materials if we switched context.
                    shader.setUniform("AlphaCutoff", 0.0f);
                }

                // Apply custom blending mode if defined by the material.
                mat.blendMode.apply();

                // 5. Bind Material Textures.

                // Unit 0: Albedo Texture (Base Color).
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, mat.albedoMapGlId != -1 ? mat.albedoMapGlId : 0);

                // Unit 3: Specular / LabPBR Texture.
                GL13.glActiveTexture(GL13.GL_TEXTURE3);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, mat.specularMapGlId != -1 ? mat.specularMapGlId : 0);

                // Unit 4: Normal Map.
                GL13.glActiveTexture(GL13.GL_TEXTURE4);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, mat.normalMapGlId != -1 ? mat.normalMapGlId : 0);

                // Reset active texture unit to 0 for the draw call.
                GL13.glActiveTexture(GL13.GL_TEXTURE0);

                // 6. Issue Draw Call.
                GL32.glDrawElementsBaseVertex(
                        GL30.GL_TRIANGLES,
                        store.indexCounts[ptr],
                        VxIndexBuffer.GL_INDEX_TYPE,
                        store.indexOffsets[ptr],
                        store.baseVertices[ptr]
                );
            }

            // Restore Rendering State.
            // Ensure culling is re-enabled if a double-sided material disabled it.
            if (!isCullingEnabled) {
                RenderSystem.enableCull();
            }
            // If we were rendering translucent objects, restore depth writing and disable blending.
            if (translucent) {
                RenderSystem.depthMask(true);
                RenderSystem.disableBlend();
            }

        } finally {
            // Unbind shader and clean up attributes.
            shader.unbind();
            GL30.glEnableVertexAttribArray(AT_UV2);

            // Restore the texture units to their original state.
            VxRenderPipeline.getInstance().restoreTextureState(savedTextureState);
            VxGlState.restorePreviousState();
        }
    }
}