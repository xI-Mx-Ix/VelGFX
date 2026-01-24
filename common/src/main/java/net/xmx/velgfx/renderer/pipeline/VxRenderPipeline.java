/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.pipeline;

import net.xmx.velgfx.renderer.util.VxShaderDetector;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.util.Arrays;
import java.util.Comparator;

/**
 * The core rendering pipeline manager (Singleton).
 * <p>
 * This class orchestrates the entire rendering process. It acts as the central hub that manages
 * the global {@link VxRenderDataStore} and determines which specific backend pipeline
 * ({@link VxVanillaRenderer} or {@link VxIrisRenderer}) should be used to execute the draw calls.
 * <p>
 * It also handles the necessary sorting logic for translucent geometry.
 *
 * @author xI-Mx-Ix
 */
public class VxRenderPipeline {

    /**
     * The singleton instance of the pipeline.
     */
    private static VxRenderPipeline instance;

    /**
     * The global data store containing all queued draw commands for the current frame.
     */
    private final VxRenderDataStore dataStore;

    /**
     * The pipeline implementation used for the standard Minecraft rendering environment.
     */
    private final VxVanillaRenderer vanillaPipeline;

    /**
     * The pipeline implementation used when a shader pack is detected.
     */
    private final VxIrisRenderer irisPipeline;

    /**
     * A reusable comparator instance used to sort translucent objects by depth.
     */
    private final TranslucentSorter translucentSorter;

    /**
     * Private constructor to enforce the Singleton pattern.
     * Initializes the sub-pipelines and the data store.
     */
    private VxRenderPipeline() {
        this.dataStore = new VxRenderDataStore();
        this.vanillaPipeline = new VxVanillaRenderer();
        this.irisPipeline = new VxIrisRenderer();
        this.translucentSorter = new TranslucentSorter(this.dataStore);
    }

    /**
     * Retrieves the global singleton instance of the {@code VxRenderPipeline}.
     *
     * @return The active pipeline instance.
     */
    public static synchronized VxRenderPipeline getInstance() {
        if (instance == null) {
            instance = new VxRenderPipeline();
        }
        return instance;
    }

    /**
     * Resets the entire pipeline state to prepare for a new frame.
     * <p>
     * This clears the {@link VxRenderDataStore} and resets the state tracking caches within
     * the sub-pipelines. Must be called exactly once at the beginning of the render frame.
     */
    public void reset() {
        dataStore.reset();
        // Reset pipelines blend state caches to ensure no state leakage between frames
        vanillaPipeline.reset();
        irisPipeline.reset();
    }

    /**
     * Provides access to the global data store.
     * This is used by external systems to record new draw calls into the queue.
     *
     * @return The current {@link VxRenderDataStore} instance.
     */
    public VxRenderDataStore getStore() {
        return dataStore;
    }

    /**
     * Flushes all opaque and cutout geometry to the GPU.
     * <p>
     * This method detects if a shader pack is active and dispatches the execution to the appropriate
     * pipeline. Opaque and Cutout objects are rendered first as they write to the depth buffer,
     * providing occlusion for subsequent passes.
     *
     * @param viewMatrix       The camera's view matrix (World Space -> Camera Space).
     * @param projectionMatrix The camera's projection matrix (Camera Space -> Clip Space).
     */
    public void flushOpaque(Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        boolean isIris = VxShaderDetector.isShaderpackActive();

        if (dataStore.opaqueBucket.size > 0) {
            dispatch(isIris, dataStore.opaqueBucket, viewMatrix, projectionMatrix, false);
        }
        if (dataStore.cutoutBucket.size > 0) {
            dispatch(isIris, dataStore.cutoutBucket, viewMatrix, projectionMatrix, false);
        }
    }

    /**
     * Sorts and flushes all translucent geometry to the GPU.
     * <p>
     * Translucent objects must be rendered from furthest to nearest (Painter's Algorithm) to ensure
     * that alpha blending functions correctly. This method performs a CPU-side sort of the
     * indices in the translucent bucket before dispatching them.
     *
     * @param viewMatrix       The camera's view matrix.
     * @param projectionMatrix The camera's projection matrix.
     * @param cameraPosition   The absolute world position of the camera, used for distance calculations.
     */
    public void flushTranslucent(Matrix4f viewMatrix, Matrix4f projectionMatrix, Vector3f cameraPosition) {
        if (dataStore.translucentBucket.size == 0) return;

        // 1. Sort the bucket indices based on distance to camera
        VxRenderDataStore.IntList bucket = dataStore.translucentBucket;
        translucentSorter.setCameraPosition(cameraPosition);

        // We must box the primitives to Integer[] to use Arrays.sort with a custom comparator.
        // While boxing has overhead, it is acceptable for the typically smaller number of transparent items.
        Integer[] indices = new Integer[bucket.size];
        for (int i = 0; i < bucket.size; i++) indices[i] = bucket.data[i];

        Arrays.sort(indices, translucentSorter);

        // Write the sorted indices back into the primitive array for the renderer
        for (int i = 0; i < bucket.size; i++) bucket.data[i] = indices[i];

        // 2. Dispatch with the translucent flag set to true
        dispatch(VxShaderDetector.isShaderpackActive(), bucket, viewMatrix, projectionMatrix, true);
    }

    /**
     * Internal helper to delegate execution to the correct pipeline.
     *
     * @param isIris      True if a shader pack is active.
     * @param bucket      The list of draw call indices to execute.
     * @param view        The view matrix.
     * @param proj        The projection matrix.
     * @param translucent True if rendering the translucent pass (affects blending state).
     */
    private void dispatch(boolean isIris, VxRenderDataStore.IntList bucket, Matrix4f view, Matrix4f proj, boolean translucent) {
        if (isIris) {
            irisPipeline.render(dataStore, bucket, view, proj, translucent);
        } else {
            vanillaPipeline.render(dataStore, bucket, view, proj, translucent);
        }
    }

    /**
     * A Comparator implementation for Back-to-Front sorting of draw calls.
     * <p>
     * It compares two draw call indices by calculating the squared distance from the camera
     * to the translation component of their respective model matrices.
     */
    private static class TranslucentSorter implements Comparator<Integer> {
        private final VxRenderDataStore store;
        private float camX, camY, camZ;

        /**
         * Constructs the sorter with a reference to the data store.
         *
         * @param store The data store containing the model matrices.
         */
        public TranslucentSorter(VxRenderDataStore store) {
            this.store = store;
        }

        /**
         * Updates the reference camera position for the current frame's sort.
         *
         * @param pos The camera position vector.
         */
        public void setCameraPosition(Vector3f pos) {
            this.camX = pos.x;
            this.camY = pos.y;
            this.camZ = pos.z;
        }

        @Override
        public int compare(Integer idx1, Integer idx2) {
            // Extract translation components from the flattened 4x4 model matrices.
            // Matrix Layout: m00...m30, m31, m32 (Columns 0-3).
            // The translation vector is stored at indices 12, 13, 14 of the 16-float block.

            int base1 = idx1 * 16;
            int base2 = idx2 * 16;

            float x1 = store.modelMatrices[base1 + 12];
            float y1 = store.modelMatrices[base1 + 13];
            float z1 = store.modelMatrices[base1 + 14];

            float x2 = store.modelMatrices[base2 + 12];
            float y2 = store.modelMatrices[base2 + 13];
            float z2 = store.modelMatrices[base2 + 14];

            // Calculate squared distances (faster than sqrt)
            float distSq1 = (camX - x1) * (camX - x1) + (camY - y1) * (camY - y1) + (camZ - z1) * (camZ - z1);
            float distSq2 = (camX - x2) * (camX - x2) + (camY - y2) * (camY - y2) + (camZ - z2) * (camZ - z2);

            // Sort Descending (Larger distance -> Smaller distance) for Back-to-Front
            return Float.compare(distSq2, distSq1);
        }
    }

    /**
     * Captures the current OpenGL texture bindings for units 0, 1, and 2.
     * This allows us to modify them during rendering and restore them exactly later.
     *
     * @return An integer array containing the active texture unit and the IDs bound to units 0-2.
     */
    public int[] captureTextureState() {
        int[] state = new int[4];
        state[0] = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        state[1] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        state[2] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        state[3] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        return state;
    }

    /**
     * Restores the OpenGL texture bindings from a previously captured state.
     *
     * @param state The integer array returned by {@link #captureTextureState()}.
     */
    public void restoreTextureState(int[] state) {
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, state[3]);

        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, state[2]);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, state[1]);

        GL13.glActiveTexture(state[0]);
    }
}