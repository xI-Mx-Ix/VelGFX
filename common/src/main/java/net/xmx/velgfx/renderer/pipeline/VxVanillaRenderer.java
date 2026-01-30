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
import net.xmx.velgfx.renderer.pipeline.instancing.VxInstanceBuffer;
import net.xmx.velgfx.renderer.util.VxTempCache;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.*;

/**
 * The rendering backend responsible for the standard Vanilla Minecraft environment.
 * <p>
 * This renderer implements a high-performance <b>Hardware Instanced Rendering</b> pipeline.
 * Instead of issuing individual draw calls for every object in the scene, it aggregates identical
 * meshes (those sharing the same geometry and material) into batches and submits them to the GPU
 * in a single command (`glDrawElementsInstanced`).
 * <p>
 * This architecture significantly reduces the CPU overhead associated with driver validation
 * and JNI calls, allowing for the rendering of vast numbers of objects with minimal performance cost.
 * <p>
 * It relies on the {@link VxRenderDataStore} to be pre-sorted by Material and Mesh ID to maximize
 * the size of each instanced batch.
 *
 * @author xI-Mx-Ix
 */
public class VxVanillaRenderer {

    /**
     * The streaming buffer used to upload per-instance data (Model Matrices, Lightmap UVs, Overlay UVs) to the GPU.
     * This buffer is cleared and rebuilt every frame.
     */
    private final VxInstanceBuffer instanceBuffer;

    /**
     * Constructs a new Vanilla Renderer and initializes the associated instance buffer.
     */
    public VxVanillaRenderer() {
        this.instanceBuffer = new VxInstanceBuffer();
    }

    /**
     * Resets the internal state of the renderer.
     * <p>
     * This method clears the instance buffer and resets any cached blend modes.
     * It should be called at the beginning of a frame or when the renderer is reloaded.
     */
    public void reset() {
        VxBlendMode.resetState();
        instanceBuffer.clear();
    }

    /**
     * Executes the rendering logic for a specific list of draw calls.
     * <p>
     * This method orchestrates the entire rendering pass:
     * <ol>
     *     <li>Preserves the current OpenGL state to ensure compatibility with Minecraft.</li>
     *     <li>Calculates global environment data (Fog, Sun/Moon position, Dynamic Lighting).</li>
     *     <li>Configures the custom {@link VxVanillaExtendedShader}.</li>
     *     <li>Iterates through the sorted draw calls, aggregating compatible items into instanced batches.</li>
     *     <li>Streams instance data to the GPU and issues `glDrawElementsInstanced` commands.</li>
     * </ol>
     *
     * @param store       The global data store containing the mesh data and matrices.
     * @param bucket      The list of indices within the store to be rendered in this pass.
     * @param viewMatrix  The current Camera View Matrix (World Space -> View Space).
     * @param projMatrix  The current Camera Projection Matrix (View Space -> Clip Space).
     * @param translucent {@code true} if this pass is for translucent geometry (enables blending),
     *                    {@code false} for opaque or cutout geometry.
     */
    public void render(VxRenderDataStore store, VxRenderDataStore.IntList bucket, Matrix4f viewMatrix, Matrix4f projMatrix, boolean translucent) {
        // Ensure we are on the main thread to perform OpenGL operations.
        RenderSystem.assertOnRenderThread();

        // If the bucket is empty, there is nothing to render.
        if (bucket.size == 0) return;

        // 1. State Preservation
        // We capture the current state of Texture Units 0-2 so we can modify them freely
        // and restore them later, preventing conflicts with Minecraft's rendering system.
        VxGlState.saveCurrentState();
        int[] savedTextureState = VxRenderPipeline.getInstance().captureTextureState();

        // 2. Resource Acquisition
        VxVanillaExtendedShader shader = VelGFX.getShaderManager().getVanillaExtendedShader();

        // Use cached objects to avoid allocation in the hot loop.
        VxTempCache cache = VxTempCache.get();
        Vector3f auxLight0 = cache.vec3_1;
        Vector3f auxLight1 = cache.vec3_2;
        Vector3f auxColor0 = cache.vec3_3;
        Vector3f auxColor1 = cache.vec3_4;
        Matrix4f auxViewRotationOnly = cache.mat4_4;

        try {
            shader.bind();

            // 3. Global Uniform Upload

            // Projection and View matrices are constant for the entire pass.
            shader.setUniform("ProjMat", projMatrix);
            shader.setUniform("ViewMat", viewMatrix);

            // Upload standard Minecraft environment variables.
            shader.setUniform("ColorModulator", RenderSystem.getShaderColor());
            shader.setUniform("FogStart", RenderSystem.getShaderFogStart());
            shader.setUniform("FogEnd", RenderSystem.getShaderFogEnd());
            shader.setUniform("FogColor", RenderSystem.getShaderFogColor());
            shader.setUniform("FogShape", RenderSystem.getShaderFogShape().getIndex());

            // 4. Dynamic Lighting Calculation
            // Computes the direction and intensity of Sun and Moon light based on game time.
            calculateLighting(auxLight0, auxLight1, auxColor0, auxColor1, auxViewRotationOnly, viewMatrix);

            shader.setUniform("Light0_Direction", auxLight0);
            shader.setUniform("Light1_Direction", auxLight1);
            shader.setUniform("Light0_Color", auxColor0);
            shader.setUniform("Light1_Color", auxColor1);
            shader.setUniform("EmissiveGain", 4.0f);
            shader.setUniform("Exposure", 1.0f);

            // 5. Global Texture Configuration
            // Bind standard textures that are shared across most entities.

            // Texture Unit 1: Overlay Texture (Used for damage tinting/red flash).
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, RenderSystem.getShaderTexture(1));
            shader.setUniform("Sampler1", 1);

            // Texture Unit 2: Lightmap Texture (Block Light / Sky Light).
            Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, RenderSystem.getShaderTexture(2));
            shader.setUniform("Sampler2", 2);

            // Assign sampler units for Material textures.
            shader.setUniform("Sampler3", 3); // Specular/PBR
            shader.setUniform("Sampler4", 4); // Normal Map
            shader.setUniform("Sampler0", 0); // Albedo/Base Color

            // 6. Generic Attribute Reset
            // Reset the generic vertex color attribute (Attribute 1) to White.
            // This ensures that meshes which do not utilize vertex colors (and thus disable Attr 1)
            // are rendered with full brightness instead of being black.
            GL30.glVertexAttrib4f(1, 1.0f, 1.0f, 1.0f, 1.0f);

            // 7. Render State Configuration
            if (translucent) {
                // Enable blending for translucent objects (e.g., stained glass, water).
                RenderSystem.enableBlend();
                RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                // Disable depth writing to handle transparency correctly (though sorting is still needed).
                RenderSystem.depthMask(false);
                shader.setUniform("AlphaCutoff", 0.0f);
            } else {
                // Default opaque state.
                shader.setUniform("AlphaCutoff", 0.0f);
            }

            // --- Instanced Render Loop ---

            // We iterate through the bucket using a while loop to manually control the index.
            // This allows us to process batches ("runs") of identical items efficiently.
            int i = 0;
            while (i < bucket.size) {
                // Start of a new batch.
                // We peek at the first item to determine the state required for this run.
                int ptr = bucket.data[i];

                VxMaterial batchMat = store.frameMaterials.get(store.materialIndices[ptr]);
                int batchVao = store.vaoIds[ptr];
                int batchEbo = store.eboIds[ptr];
                int batchIndexCount = store.indexCounts[ptr];
                long batchIndexOffset = store.indexOffsets[ptr];
                int batchBaseVertex = store.baseVertices[ptr];

                // Apply the material state (Textures, Culling, Blending) for this batch.
                applyMaterialState(batchMat, translucent, shader);

                // Bind the Mesh Geometry (VAO and EBO).
                GL30.glBindVertexArray(batchVao);
                GL30.glBindBuffer(GL30.GL_ELEMENT_ARRAY_BUFFER, batchEbo);

                // Reset the instance buffer to prepare for accumulating new instance data.
                instanceBuffer.clear();

                int instancesInBatch = 0;

                // Inner Loop: Consume as many consecutive identical items as possible.
                // Since the bucket is sorted by Material -> VAO -> Offset, identical items are adjacent.
                while (i < bucket.size) {
                    int nextPtr = bucket.data[i];

                    // Check if the next item breaks the batch.
                    // A batch is broken if the Mesh (VAO/EBO/Offsets) changes OR the Material changes.
                    if (store.vaoIds[nextPtr] != batchVao ||
                            store.eboIds[nextPtr] != batchEbo ||
                            store.indexOffsets[nextPtr] != batchIndexOffset ||
                            // Quick reference check for material identity.
                            store.frameMaterials.get(store.materialIndices[nextPtr]) != batchMat) {
                        break;
                    }

                    // The item is compatible. Add its instance data to the buffer.
                    // We read directly from the DataStore's flat arrays to avoid object overhead.
                    instanceBuffer.add(
                            store.modelMatrices,
                            nextPtr * 16,
                            store.packedLights[nextPtr],
                            store.overlayUVs[nextPtr]
                    );

                    instancesInBatch++;
                    i++;

                    // If the instance buffer is full, we must flush (draw) and continue.
                    if (instancesInBatch >= instanceBuffer.getCapacity()) {
                        break;
                    }
                }

                // Execute the Draw Call if we collected any instances.
                if (instancesInBatch > 0) {
                    // Upload the accumulated instance data to the GPU.
                    instanceBuffer.upload();

                    // Enable the instanced vertex attributes on the currently bound VAO.
                    instanceBuffer.bindAttributes();

                    // Issue the Instanced Draw Call.
                    // This renders 'instancesInBatch' copies of the mesh in one go.
                    GL33.glDrawElementsInstancedBaseVertex(
                            GL11.GL_TRIANGLES,
                            batchIndexCount,
                            VxIndexBuffer.GL_INDEX_TYPE,
                            batchIndexOffset,
                            instancesInBatch,
                            batchBaseVertex
                    );

                    // Clean up the VAO state by disabling instanced attributes.
                    instanceBuffer.unbindAttributes();
                }
            }

            // 8. State Restoration
            // Restore Face Culling if it was disabled by a material.
            RenderSystem.enableCull();

            // Restore Depth Mask and Blending if we were in translucent mode.
            if (translucent) {
                RenderSystem.depthMask(true);
                RenderSystem.disableBlend();
            }

        } finally {
            // Unbind the shader program.
            shader.unbind();

            // Restore the original texture bindings to avoid side effects in Minecraft.
            VxRenderPipeline.getInstance().restoreTextureState(savedTextureState);

            // Restore generic OpenGL state.
            VxGlState.restorePreviousState();
        }
    }

    /**
     * Calculates the dynamic lighting vectors and colors for the Sun and Moon.
     * <p>
     * This method computes the direction of celestial bodies based on the World Time
     * and determines their light intensity/color based on their elevation above the horizon.
     *
     * @param light0  Output vector for the Sun direction (View Space).
     * @param light1  Output vector for the Moon direction (View Space).
     * @param color0  Output vector for the Sun color/intensity.
     * @param color1  Output vector for the Moon color/intensity.
     * @param viewRot Scratch matrix used for rotation calculations.
     * @param viewMat The current Camera View Matrix.
     */
    private void calculateLighting(Vector3f light0, Vector3f light1, Vector3f color0, Vector3f color1, Matrix4f viewRot, Matrix4f viewMat) {
        float sunAngle = 0.0f;
        if (Minecraft.getInstance().level != null) {
            // Get the sun angle in radians (0 = Noon).
            sunAngle = Minecraft.getInstance().level.getSunAngle(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true));
        }

        // Calculate light directions in World Space.
        float rawSunX = (float) Math.sin(sunAngle);
        float rawSunY = (float) Math.cos(sunAngle);

        light0.set(-rawSunX, rawSunY, 0.0f).normalize(); // Sun
        light1.set(rawSunX, -rawSunY, 0.0f).normalize(); // Moon (Opposite)

        // Calculate brightness based on elevation (Y component).
        float sunHeight = light0.y;
        float moonHeight = light1.y;

        // Clamp intensity to avoid negative light from below the horizon.
        // A small bias (+0.1) provides twilight illumination.
        float sunBrightness = Math.max(0.0f, sunHeight + 0.1f) * 1f;
        float moonBrightness = Math.max(0.0f, moonHeight + 0.1f) * 0.5f;

        color0.set(sunBrightness, sunBrightness, sunBrightness);
        // Moon light gets a slight blue tint (Z component + 0.1).
        color1.set(moonBrightness, moonBrightness, moonBrightness + 0.1f);

        // Transform light directions to View Space.
        // We take the View Matrix, strip the translation, and apply it to the vectors.
        viewRot.set(viewMat).setTranslation(0, 0, 0);
        viewRot.transformDirection(light0);
        viewRot.transformDirection(light1);
    }

    /**
     * Configures the GPU state for the specified material.
     * <p>
     * This includes binding textures, setting the alpha cutoff threshold,
     * and configuring face culling and blend modes.
     *
     * @param mat         The material to apply.
     * @param translucent True if the current pass is translucent.
     * @param shader      The active shader program.
     */
    private void applyMaterialState(VxMaterial mat, boolean translucent, VxVanillaExtendedShader shader) {
        // Face Culling: Disable for double-sided materials (e.g., foliage).
        if (!mat.doubleSided) RenderSystem.enableCull();
        else RenderSystem.disableCull();

        // Alpha Cutoff: Used for Cutout rendering (e.g., grass blocks).
        if (mat.renderType == VxRenderType.CUTOUT) {
            shader.setUniform("AlphaCutoff", mat.alphaCutoff);
        } else if (!translucent) {
            // For standard opaque objects, disable alpha testing.
            shader.setUniform("AlphaCutoff", 0.0f);
        }

        // Apply custom blending equation if specified.
        mat.blendMode.apply();

        // Bind Material Textures to their respective units.

        // Unit 0: Albedo / Base Color
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, mat.albedoMapGlId != -1 ? mat.albedoMapGlId : 0);

        // Unit 3: Specular / LabPBR Data
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, mat.specularMapGlId != -1 ? mat.specularMapGlId : 0);

        // Unit 4: Normal Map
        GL13.glActiveTexture(GL13.GL_TEXTURE4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, mat.normalMapGlId != -1 ? mat.normalMapGlId : 0);

        // Reset active unit to 0 for safety.
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }
}