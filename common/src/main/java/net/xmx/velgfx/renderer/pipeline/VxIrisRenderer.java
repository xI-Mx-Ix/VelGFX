/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.pipeline;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.xmx.velgfx.renderer.VelGFX;
import net.xmx.velgfx.renderer.gl.VxGlState;
import net.xmx.velgfx.renderer.gl.VxIndexBuffer;
import net.xmx.velgfx.renderer.gl.material.VxMaterial;
import net.xmx.velgfx.renderer.gl.state.VxBlendMode;
import net.xmx.velgfx.renderer.util.VxTempCache;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.*;

import java.nio.FloatBuffer;

/**
 * The rendering backend responsible for compatibility with Iris and Shaderpacks.
 * <p>
 * Unlike the Vanilla pipeline, this class cannot assume fixed binding points for textures
 * or specific uniform names, as every shader pack is different. Instead, it performs
 * <b>dynamic analysis</b> of the currently active shader program to discover where
 * the shader expects Normal Maps, Specular Maps, and matrix data.
 * <p>
 * It acts as a bridge between the VelGFX material system and the external shader pack,
 * ensuring that high-fidelity PBR data is correctly passed to third-party shaders.
 *
 * @author xI-Mx-Ix
 */
public class VxIrisRenderer {

    /**
     * The vertex attribute location for the Lightmap UV coordinates.
     */
    private static final int AT_UV2 = 4;

    // --- Shader Analysis Cache ---

    /**
     * Stores the OpenGL Program ID of the last analyzed shader.
     * Used to detect when the active shader program changes (e.g., between render passes).
     */
    private int cachedProgramId = -1;

    /**
     * The texture unit index (0-31) where the current shader expects the Normal Map.
     * Discovered dynamically via {@code glGetUniformLocation}.
     */
    private int texUnitNormal = -1;

    /**
     * The texture unit index (0-31) where the current shader expects the Specular Map.
     * Discovered dynamically via {@code glGetUniformLocation}.
     */
    private int texUnitSpecular = -1;

    /**
     * The uniform location ID for the Normal Matrix in the current shader.
     * Iris typically uses "iris_NormalMat", but we fallback to standard names if needed.
     */
    private int locNormalMat = -1;

    /**
     * Resets the internal state and invalidates the shader cache.
     * Should be called when the renderer is reloaded or the world changes.
     */
    public void reset() {
        VxBlendMode.resetState();
        cachedProgramId = -1;
    }

    /**
     * Executes the rendering logic for a specific batch (bucket) of draw calls.
     * <p>
     * This method adapts to the active shader pack by analyzing the shader program
     * on the fly and binding textures to the slots the shader demands.
     *
     * @param store       The global data store containing mesh data.
     * @param bucket      The list of indices to render in this pass.
     * @param viewMatrix  The current camera View Matrix.
     * @param projMatrix  The current camera Projection Matrix.
     * @param translucent {@code true} if rendering the translucent pass (enables blending).
     */
    public void render(VxRenderDataStore store, VxRenderDataStore.IntList bucket, Matrix4f viewMatrix, Matrix4f projMatrix, boolean translucent) {
        VxGlState.saveCurrentState();
        // 1. Snapshot the current OpenGL texture state.
        // We use the shared utility method from VxRenderPipeline since this logic is identical
        // to the Vanilla pipeline. This ensures we don't break Minecraft's internal rendering state.
        int[] savedTextureState = VxRenderPipeline.getInstance().captureTextureState();

        // 2. Initialize the standard entity shader state.
        // Even though Iris replaces the shader, we must set up the baseline "RendertypeEntitySolid"
        // state so that Iris hooks can intercept and apply the shader pack correctly.
        ShaderInstance shader = setupCommonRenderState(projMatrix, GameRenderer.getRendertypeEntitySolidShader());
        if (shader == null) return;

        // Reset standard texture samplers (Units 0-11) to defaults to prevent state bleeding.
        for (int i = 0; i < 12; ++i) {
            shader.setSampler("Sampler" + i, RenderSystem.getShaderTexture(i));
        }
        shader.apply();

        // Reset generic vertex color to white.
        GL30.glVertexAttrib4f(1, 1.0f, 1.0f, 1.0f, 1.0f);

        // 3. Dynamic Shader Analysis.
        // We inspect the currently bound OpenGL program (which is now the Shaderpack's program)
        // to find out where we should put our Normal and Specular maps.
        analyzeActiveShader();

        // 4. Configure Blend and Depth State.
        if (translucent) {
            RenderSystem.enableBlend();
            RenderSystem.depthMask(false);
        } else {
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
        }

        // 5. Acquire Scratch Objects from Cache.
        // This prevents GC churn during matrix operations.
        VxTempCache cache = VxTempCache.get();
        Matrix4f auxModel = cache.mat4_1;
        Matrix4f auxModelView = cache.mat4_2;
        Matrix3f auxNormalMat = cache.mat3_1;
        Matrix3f auxNormalView = cache.mat3_2;
        Matrix3f auxViewRot = cache.mat3_2;

        FloatBuffer matrixBuffer = cache.floatBuffer16;

        try {
            int currentVao = -1;
            int currentEbo = -1;
            boolean isCullingEnabled = true;

            // Extract View Rotation for Normal Matrix calculation.
            // We need this to transform our stored object normals into View Space.
            viewMatrix.get3x3(auxViewRot);

            // --- Render Loop ---
            for (int i = 0; i < bucket.size; i++) {
                int ptr = bucket.data[i];
                VxMaterial mat = store.frameMaterials.get(store.materialIndices[ptr]);

                // 1. Calculate Matrices.

                // ModelView Matrix: View * Model
                matrixBuffer.clear();
                matrixBuffer.put(store.modelMatrices, ptr * 16, 16);
                matrixBuffer.flip();
                auxModel.set(matrixBuffer);
                auxModelView.set(viewMatrix).mul(auxModel);

                // Normal Matrix: ViewRotation * StoredNormalMatrix
                matrixBuffer.clear();
                matrixBuffer.put(store.normalMatrices, ptr * 9, 9);
                matrixBuffer.flip();
                auxNormalMat.set(matrixBuffer);

                auxNormalView.set(auxViewRot).mul(auxNormalMat);

                // Upload ModelView Matrix via the standard ShaderInstance helper.
                if (shader.MODEL_VIEW_MATRIX != null) {
                    shader.MODEL_VIEW_MATRIX.set(auxModelView);
                }

                // Upload Normal Matrix (if we found a compatible uniform location during analysis).
                // This allows the shader pack to receive correct normal data.
                if (this.locNormalMat != -1) {
                    matrixBuffer.clear();
                    auxNormalView.get(matrixBuffer);
                    matrixBuffer.flip();
                    RenderSystem.glUniformMatrix3(this.locNormalMat, false, matrixBuffer);
                }

                // 2. Bind Mesh Geometry.
                int vao = store.vaoIds[ptr];
                int ebo = store.eboIds[ptr];

                if (vao != currentVao) {
                    GL30.glBindVertexArray(vao);
                    currentVao = vao;
                    currentEbo = -1;
                }
                if (ebo != currentEbo) {
                    GL30.glBindBuffer(GL30.GL_ELEMENT_ARRAY_BUFFER, ebo);
                    currentEbo = ebo;
                }

                // 3. Set Lightmap Coordinates (Attribute 4).
                // The vertex attribute array is disabled to allow injecting a constant value
                // for the entire draw call using glVertexAttribI2i.
                GL30.glDisableVertexAttribArray(AT_UV2);
                int packedLight = store.packedLights[ptr];
                GL30.glVertexAttribI2i(AT_UV2, packedLight & 0xFFFF, packedLight >> 16);

                // 4. Draw Call Preparation.

                // Toggle Face Culling based on material double-sidedness.
                boolean shouldCull = !mat.doubleSided;
                if (shouldCull != isCullingEnabled) {
                    if (shouldCull) RenderSystem.enableCull();
                    else RenderSystem.disableCull();
                    isCullingEnabled = shouldCull;
                }

                // Ensure the Color Modulator is neutral (white).
                if (shader.COLOR_MODULATOR != null) {
                    shader.COLOR_MODULATOR.set(1.0f, 1.0f, 1.0f, 1.0f);
                }

                mat.blendMode.apply();
                shader.apply();

                // Bind Material Textures to the units discovered by analyzeActiveShader().
                bindIrisTextures(mat);

                // Issue Draw Call.
                GL32.glDrawElementsBaseVertex(
                        GL30.GL_TRIANGLES,
                        store.indexCounts[ptr],
                        VxIndexBuffer.GL_INDEX_TYPE,
                        store.indexOffsets[ptr],
                        store.baseVertices[ptr]
                );

                // Restore attribute state for the next iteration.
                GL30.glEnableVertexAttribArray(AT_UV2);
            }

            // Restore global state after loop.
            if (!isCullingEnabled) {
                RenderSystem.enableCull();
            }
            if (translucent) {
                RenderSystem.depthMask(true);
                RenderSystem.disableBlend();
            }

        } finally {
            // Clean up shader and texture state.
            shader.clear();
            cleanupTextureUnits();

            // Restore original texture bindings using the shared utility method.
            VxRenderPipeline.getInstance().restoreTextureState(savedTextureState);

            VxGlState.restorePreviousState();
        }
    }

    /**
     * Inspects the currently active OpenGL shader program (provided by the shader pack).
     * <p>
     * If the program ID has changed since the last frame, this method queries the
     * locations of specific uniforms (like "normals", "specular", and "iris_NormalMat").
     * This allows us to map our texture data to the correct Texture Units that the
     * shader pack expects to read from.
     */
    private void analyzeActiveShader() {
        int currentProgramId = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);

        // Only re-analyze if the program has changed.
        if (currentProgramId != this.cachedProgramId) {
            this.cachedProgramId = currentProgramId;

            // Find texture units for Normal and Specular maps.
            // We look up the uniform location, and then get the integer value of that uniform,
            // which tells us which Texture Unit (0-31) the sampler is bound to.
            int locNormals = GL20.glGetUniformLocation(currentProgramId, "normals");
            int locSpecular = GL20.glGetUniformLocation(currentProgramId, "specular");

            this.texUnitNormal = (locNormals != -1) ? GL20.glGetUniformi(currentProgramId, locNormals) : -1;
            this.texUnitSpecular = (locSpecular != -1) ? GL20.glGetUniformi(currentProgramId, locSpecular) : -1;

            // Locate the Normal Matrix uniform.
            // Iris generally uses "iris_NormalMat", but we check common fallbacks just in case.
            this.locNormalMat = Uniform.glGetUniformLocation(currentProgramId, "iris_NormalMat");
            if (this.locNormalMat == -1) {
                this.locNormalMat = Uniform.glGetUniformLocation(currentProgramId, "NormalMat");
            }
            if (this.locNormalMat == -1) {
                this.locNormalMat = Uniform.glGetUniformLocation(currentProgramId, "normalMatrix");
            }
        }
    }

    /**
     * Binds the material's textures to the specific texture units required by the active shader.
     *
     * @param mat The material containing the OpenGL texture IDs.
     */
    private void bindIrisTextures(VxMaterial mat) {
        // 0. Albedo -> Unit 0 (Standard convention for almost all shaders).
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        if (mat.albedoMapGlId != -1) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, mat.albedoMapGlId);
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }

        // 1. Normal Map -> Targeted Unit (Dynamic).
        // If the shader pack requests a normal map, bind it to the discovered unit.
        if (this.texUnitNormal != -1 && mat.normalMapGlId != -1) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + this.texUnitNormal);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, mat.normalMapGlId);
        }

        // 2. Specular Map -> Targeted Unit (Dynamic).
        // If the shader pack requests a specular map, bind it to the discovered unit.
        if (this.texUnitSpecular != -1 && mat.specularMapGlId != -1) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + this.texUnitSpecular);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, mat.specularMapGlId);
        }

        // Always restore the active texture to Unit 0 to avoid side effects.
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /**
     * Unbinds textures from the custom units used by the shader pack.
     * This prevents potential interference with subsequent rendering passes that might
     * use these texture units for other purposes.
     */
    private void cleanupTextureUnits() {
        if (this.texUnitNormal != -1) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + this.texUnitNormal);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }
        if (this.texUnitSpecular != -1) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + this.texUnitSpecular);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /**
     * Sets up the common OpenGL state required for rendering entities.
     * This includes enabling depth testing and face culling, which are required
     * before applying the shader program.
     *
     * @param projectionMatrix The camera projection matrix.
     * @param targetShader     The base shader instance to configure (e.g., RendertypeEntitySolid).
     * @return The configured shader instance, or null if setup failed.
     */
    private ShaderInstance setupCommonRenderState(Matrix4f projectionMatrix, ShaderInstance targetShader) {
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.enableCull();

        if (targetShader == null) {
            VelGFX.LOGGER.error("Failed to render mesh batch: RendertypeEntitySolidShader is null.");
            return null;
        }

        RenderSystem.setShader(() -> targetShader);
        setupShaderUniforms(targetShader, projectionMatrix);
        return targetShader;
    }

    /**
     * Uploads standard Minecraft uniforms to the shader program.
     * These include projection matrices, fog parameters, and game time.
     *
     * @param shader           The shader instance receiving the uniforms.
     * @param projectionMatrix The current projection matrix.
     */
    private void setupShaderUniforms(ShaderInstance shader, Matrix4f projectionMatrix) {
        if (shader.PROJECTION_MATRIX != null) shader.PROJECTION_MATRIX.set(projectionMatrix);
        if (shader.TEXTURE_MATRIX != null) shader.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
        if (shader.COLOR_MODULATOR != null) shader.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
        if (shader.FOG_START != null) shader.FOG_START.set(RenderSystem.getShaderFogStart());
        if (shader.FOG_END != null) shader.FOG_END.set(RenderSystem.getShaderFogEnd());
        if (shader.FOG_COLOR != null) shader.FOG_COLOR.set(RenderSystem.getShaderFogColor());
        if (shader.FOG_SHAPE != null) shader.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
        if (shader.GAME_TIME != null) shader.GAME_TIME.set(RenderSystem.getShaderGameTime());
        if (shader.GLINT_ALPHA != null) shader.GLINT_ALPHA.set(RenderSystem.getShaderGlintAlpha());

        if (shader.SCREEN_SIZE != null) {
            shader.SCREEN_SIZE.set(
                    (float) Minecraft.getInstance().getWindow().getWidth(),
                    (float) Minecraft.getInstance().getWindow().getHeight()
            );
        }
        RenderSystem.setupShaderLights(shader);
    }
}