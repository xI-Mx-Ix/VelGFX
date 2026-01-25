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
 * The central rendering pipeline manager (Singleton).
 * <p>
 * This class orchestrates the high-level rendering flow for VelGFX. It acts as the bridge
 * between the data collection phase (recording draw calls) and the execution phase (dispatching to GPU).
 * <p>
 * Key Responsibilities:
 * <ul>
 *     <li>Manages the global {@link VxRenderDataStore} which holds all frame data.</li>
 *     <li>Determines which backend pipeline to use (Vanilla Instanced or Iris Compatibility).</li>
 *     <li>Handles sorting of transparent geometry (Painter's Algorithm).</li>
 *     <li>Dispatches render passes (Opaque, Cutout, Translucent).</li>
 * </ul>
 *
 * @author xI-Mx-Ix
 */
public class VxRenderPipeline {

    /**
     * The singleton instance of the pipeline.
     */
    private static VxRenderPipeline instance;

    /**
     * The central data store for render commands.
     */
    private final VxRenderDataStore dataStore;

    /**
     * The optimized backend for Vanilla rendering (supports Instancing).
     */
    private final VxVanillaRenderer vanillaPipeline;

    /**
     * The compatibility backend for Iris/Shaderpacks.
     */
    private final VxIrisRenderer irisPipeline;

    /**
     * Helper object for sorting translucent draw calls by depth.
     */
    private final TranslucentSorter translucentSorter;

    /**
     * Private constructor to enforce Singleton pattern.
     * Initializes the sub-components of the pipeline.
     */
    private VxRenderPipeline() {
        this.dataStore = new VxRenderDataStore();
        this.vanillaPipeline = new VxVanillaRenderer();
        this.irisPipeline = new VxIrisRenderer();
        this.translucentSorter = new TranslucentSorter(this.dataStore);
    }

    /**
     * Retrieves the global instance of the rendering pipeline.
     *
     * @return The singleton instance.
     */
    public static synchronized VxRenderPipeline getInstance() {
        if (instance == null) {
            instance = new VxRenderPipeline();
        }
        return instance;
    }

    /**
     * Resets the entire pipeline state for the next frame.
     * <p>
     * This clears the data store and resets the internal state of the sub-renderers.
     * Must be called exactly once per frame, typically at the start of rendering.
     */
    public void reset() {
        dataStore.reset();
        vanillaPipeline.reset();
        irisPipeline.reset();
    }

    /**
     * Provides access to the render data store.
     * Used by meshes and entities to record their draw commands.
     *
     * @return The active {@link VxRenderDataStore}.
     */
    public VxRenderDataStore getStore() {
        return dataStore;
    }

    /**
     * Processes and renders all Opaque and Cutout geometry.
     * <p>
     * This method performs the following steps:
     * <ol>
     *     <li>Detects if a Shaderpack is active to select the correct backend.</li>
     *     <li>Sorts the Opaque and Cutout buckets to optimize for Instancing (grouping identical meshes).</li>
     *     <li>Dispatches the sorted buckets to the selected renderer.</li>
     * </ol>
     *
     * @param viewMatrix       The camera's View Matrix.
     * @param projectionMatrix The camera's Projection Matrix.
     */
    public void flushOpaque(Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        boolean isIris = VxShaderDetector.isShaderpackActive();

        // Sort the data store to group identical materials and meshes.
        // This is crucial for efficient Instanced Rendering in the Vanilla pipeline.
        dataStore.sortForInstancing();

        // Flush Opaque (Solid) geometry first (Optimal for Z-Buffer).
        if (dataStore.opaqueBucket.size > 0) {
            dispatch(isIris, dataStore.opaqueBucket, viewMatrix, projectionMatrix, false);
        }

        // Flush Cutout (Alpha Test) geometry second.
        if (dataStore.cutoutBucket.size > 0) {
            dispatch(isIris, dataStore.cutoutBucket, viewMatrix, projectionMatrix, false);
        }
    }

    /**
     * Processes and renders all Translucent geometry.
     * <p>
     * Translucent objects require strict Back-to-Front sorting to blend correctly.
     * This method sorts the translucent bucket based on distance to the camera before rendering.
     *
     * @param viewMatrix       The camera's View Matrix.
     * @param projectionMatrix The camera's Projection Matrix.
     * @param cameraPosition   The world position of the camera (used for sorting).
     */
    public void flushTranslucent(Matrix4f viewMatrix, Matrix4f projectionMatrix, Vector3f cameraPosition) {
        if (dataStore.translucentBucket.size == 0) return;

        // Prepare the sorter with the current camera position.
        VxRenderDataStore.IntList bucket = dataStore.translucentBucket;
        translucentSorter.setCameraPosition(cameraPosition);

        // Box indices to Integer[] to use Arrays.sort with a custom Comparator.
        // (Performance note: A primitive implementation would be faster, but this is sufficient for typical translucent counts).
        Integer[] indices = new Integer[bucket.size];
        for (int i = 0; i < bucket.size; i++) indices[i] = bucket.data[i];

        Arrays.sort(indices, translucentSorter);

        // Write sorted indices back to the primitive bucket.
        for (int i = 0; i < bucket.size; i++) bucket.data[i] = indices[i];

        // Dispatch with translucent flag = true.
        dispatch(VxShaderDetector.isShaderpackActive(), bucket, viewMatrix, projectionMatrix, true);
    }

    /**
     * Delegates the render command to the appropriate backend pipeline.
     *
     * @param isIris      True if Iris/Shaderpack is active.
     * @param bucket      The list of draw calls to execute.
     * @param view        The View Matrix.
     * @param proj        The Projection Matrix.
     * @param translucent True if rendering the translucent pass.
     */
    private void dispatch(boolean isIris, VxRenderDataStore.IntList bucket, Matrix4f view, Matrix4f proj, boolean translucent) {
        if (isIris) {
            irisPipeline.render(dataStore, bucket, view, proj, translucent);
        } else {
            vanillaPipeline.render(dataStore, bucket, view, proj, translucent);
        }
    }

    /**
     * A Comparator implementation that sorts draw calls based on distance from the camera.
     * Used for the Translucent pass (Painter's Algorithm).
     */
    private static class TranslucentSorter implements Comparator<Integer> {
        private final VxRenderDataStore store;
        private float camX, camY, camZ;

        /**
         * Constructs the sorter with a reference to the data store.
         *
         * @param store The data store containing model matrices.
         */
        public TranslucentSorter(VxRenderDataStore store) {
            this.store = store;
        }

        /**
         * Updates the camera position used for distance calculations.
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
            // Retrieve the translation components (Column 3) from the Model Matrix.
            // Layout: m00..m30, m31, m32. Translation starts at index 12.
            int base1 = idx1 * 16;
            int base2 = idx2 * 16;

            float x1 = store.modelMatrices[base1 + 12];
            float y1 = store.modelMatrices[base1 + 13];
            float z1 = store.modelMatrices[base1 + 14];

            float x2 = store.modelMatrices[base2 + 12];
            float y2 = store.modelMatrices[base2 + 13];
            float z2 = store.modelMatrices[base2 + 14];

            // Calculate squared Euclidean distance to camera.
            float distSq1 = (camX - x1) * (camX - x1) + (camY - y1) * (camY - y1) + (camZ - z1) * (camZ - z1);
            float distSq2 = (camX - x2) * (camX - x2) + (camY - y2) * (camY - y2) + (camZ - z2) * (camZ - z2);

            // Sort Descending (Furthest first).
            return Float.compare(distSq2, distSq1);
        }
    }

    /**
     * Captures the current OpenGL texture bindings for Units 0, 1, and 2.
     * <p>
     * This utility allows the pipeline to modify texture state during rendering
     * and restore it perfectly afterwards, ensuring compatibility with external code.
     *
     * @return An array containing the active texture unit index and the bound texture IDs.
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
     * Restores the OpenGL texture bindings from a captured state array.
     *
     * @param state The state array returned by {@link #captureTextureState()}.
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