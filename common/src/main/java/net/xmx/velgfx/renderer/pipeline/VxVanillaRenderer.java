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
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
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
 *     <li>Real-time calculation of Matrix data (ModelView and Normal matrices).</li>
 *     <li>Binding of PBR textures (Normal, Specular) alongside standard Albedo.</li>
 * </ul>
 *
 * @author xI-Mx-Ix
 */
public class VxVanillaRenderer {

    /**
     * A direct float buffer used for uploading 4x4 matrices (16 floats) to the GPU.
     * This avoids creating new float arrays for every uniform upload.
     */
    private static final FloatBuffer MATRIX_BUFFER_16 = BufferUtils.createFloatBuffer(16);

    /**
     * A direct float buffer used for uploading 3x3 matrices (9 floats) to the GPU.
     * Primarily used for the Normal Matrix.
     */
    private static final FloatBuffer MATRIX_BUFFER_9 = BufferUtils.createFloatBuffer(9);

    /**
     * Scratch matrix for holding the raw Model Matrix of the current object.
     * Populated from the data store before being multiplied with the View Matrix.
     */
    private final Matrix4f auxModel = new Matrix4f();

    /**
     * Scratch matrix for the combined Model-View Matrix.
     * Calculated as: {@code ViewMatrix * ModelMatrix}.
     * This transforms vertices from Object Space to Camera Space.
     */
    private final Matrix4f auxModelView = new Matrix4f();

    /**
     * Scratch matrix used exclusively for calculating the Normal Matrix.
     * It holds the result of {@code ModelView.invert().transpose()}.
     */
    private final Matrix4f auxInverseView = new Matrix4f();

    /**
     * The final 3x3 Normal Matrix sent to the shader.
     * Extracted from the top-left corner of the {@link #auxInverseView} matrix.
     */
    private final Matrix3f auxNormalMat = new Matrix3f();

    /**
     * Scratch vector for the Sun's direction.
     * Used to calculate lighting direction in World Space before transforming to View Space.
     */
    private final Vector3f auxLight0 = new Vector3f();

    /**
     * Scratch vector for the Moon's direction.
     * Always strictly opposite to the Sun's direction.
     */
    private final Vector3f auxLight1 = new Vector3f();

    /**
     * Scratch matrix used to transform light vectors into View Space.
     * This is a copy of the camera's View Matrix with the translation component removed.
     */
    private final Matrix4f auxViewRotationOnly = new Matrix4f();

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

        try {
            shader.bind();

            // 3. Upload standard environment uniforms.
            // These values define global fog settings and projection parameters.
            shader.setUniform("ProjMat", projMatrix);
            shader.setUniform("ColorModulator", RenderSystem.getShaderColor());
            shader.setUniform("FogStart", RenderSystem.getShaderFogStart());
            shader.setUniform("FogEnd", RenderSystem.getShaderFogEnd());
            shader.setUniform("FogColor", RenderSystem.getShaderFogColor());
            shader.setUniform("FogShape", RenderSystem.getShaderFogShape().getIndex());

            // 4. Calculate Dynamic Lighting Directions.
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

            // 5. Configure Global Textures.
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

            // 6. Set up Render State for Translucency.
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

            // Cache the uniform location for the Normal Matrix.
            int locNormalMat = shader.getUniformLocation("NormalMat");

            // --- Inner Render Loop ---
            // Iterate over all batched items in this bucket.
            for (int i = 0; i < bucket.size; i++) {
                int ptr = bucket.data[i];
                VxMaterial mat = store.frameMaterials.get(store.materialIndices[ptr]);

                // 1. Compute ModelView Matrix.
                // We retrieve the Model Matrix from the store and multiply it with the Camera View Matrix.
                MATRIX_BUFFER_16.clear();
                MATRIX_BUFFER_16.put(store.modelMatrices, ptr * 16, 16);
                MATRIX_BUFFER_16.flip();
                auxModel.set(MATRIX_BUFFER_16);

                // Calculation: ModelView = View * Model
                auxModelView.set(viewMatrix).mul(auxModel);
                shader.setUniform("ModelViewMat", auxModelView);

                // 2. Compute Normal Matrix.
                // To handle non-uniform scaling correctly, we must use the Inverse-Transpose
                // of the ModelView matrix. This ensures lighting normals remain perpendicular to surfaces.
                auxInverseView.set(auxModelView).invert().transpose();
                auxInverseView.get3x3(auxNormalMat);

                MATRIX_BUFFER_9.clear();
                auxNormalMat.get(MATRIX_BUFFER_9);
                GL20.glUniformMatrix3fv(locNormalMat, false, MATRIX_BUFFER_9);

                // 3. Bind Mesh Geometry.
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

                // 4. Set Lightmap Coordinates.
                // These are passed as a custom integer attribute (Index 4).
                GL30.glDisableVertexAttribArray(AT_UV2);
                int packedLight = store.packedLights[ptr];
                GL30.glVertexAttribI2i(AT_UV2, packedLight & 0xFFFF, packedLight >> 16);

                // 5. Apply Material Properties.
                VxRenderType type = mat.renderType;

                // Handle Face Culling (Double-Sided vs Single-Sided).
                boolean shouldCull = !mat.doubleSided;
                if (shouldCull != isCullingEnabled) {
                    if (shouldCull) RenderSystem.enableCull();
                    else RenderSystem.disableCull();
                    isCullingEnabled = shouldCull;
                }

                // Update Alpha Cutoff based on render type.
                if (type == VxRenderType.CUTOUT) {
                    shader.setUniform("AlphaCutoff", mat.alphaCutoff);
                } else if (!translucent) {
                    // Reset cutoff for standard opaque materials if we switched context.
                    shader.setUniform("AlphaCutoff", 0.0f);
                }

                // Apply custom blending mode if defined by the material.
                mat.blendMode.apply();

                // 6. Bind Material Textures.

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

                // 7. Issue Draw Call.
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