/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.morph;

import net.xmx.velgfx.renderer.gl.shader.impl.VxSkinningShader;

import java.util.*;

/**
 * Manages the runtime state of Morph Targets for a specific model instance.
 * <p>
 * This class handles:
 * <ul>
 *     <li>Storing current weights for all available targets.</li>
 *     <li>Sorting targets by weight to determine the most significant ones (Level of Detail).</li>
 *     <li>Uploading the "Top N" targets to the shader via uniforms.</li>
 * </ul>
 *
 * @author xI-Mx-Ix
 */
public class VxMorphController {

    /**
     * The maximum number of active morph targets supported by the shader in a single pass.
     */
    private static final int MAX_ACTIVE = 8;

    /**
     * Map of Name -> Static Definition. Shared across instances, but stored locally for lookup.
     */
    private final Map<String, VxMorphTarget> targetsByName;

    /**
     * Map of Name -> Current Weight.
     */
    private final Map<String, Float> currentWeights = new HashMap<>();

    /**
     * Cached list of active targets for sorting. Refreshed on weight change.
     */
    private final List<ActiveMorph> activeList = new ArrayList<>();
    private boolean isDirty = false;

    // --- Shader Data Buffers ---
    // These arrays are pre-allocated to avoid garbage collection during the render loop.
    private final int[] shaderIndices = new int[MAX_ACTIVE];
    private final float[] shaderWeights = new float[MAX_ACTIVE];
    private int activeCount = 0;

    /**
     * Constructs a controller with a defined set of available targets.
     *
     * @param availableTargets The list of targets supported by the mesh.
     */
    public VxMorphController(List<VxMorphTarget> availableTargets) {
        this.targetsByName = new HashMap<>();
        for (VxMorphTarget t : availableTargets) {
            this.targetsByName.put(t.name(), t);
        }
    }

    /**
     * Sets the weight of a morph target by name.
     *
     * @param name   The target name (e.g., "MouthOpen").
     * @param weight The weight (usually 0.0 to 1.0).
     */
    public void setWeight(String name, float weight) {
        if (!targetsByName.containsKey(name)) return;

        float old = currentWeights.getOrDefault(name, 0f);
        if (Math.abs(old - weight) > 0.0001f) {
            currentWeights.put(name, weight);
            isDirty = true;
        }
    }

    /**
     * Sets the weight by glTF index (used by Animations).
     *
     * @param index  The logical glTF index of the target.
     * @param weight The new weight.
     */
    public void setWeightByIndex(int index, float weight) {
        // Reverse lookup (Optimization note: Could cache Index->Name map if perf critical)
        for (VxMorphTarget t : targetsByName.values()) {
            if (t.index() == index) {
                setWeight(t.name(), weight);
                return;
            }
        }
    }

    /**
     * Prepares the morph state for rendering.
     * <p>
     * If weights have changed, this method sorts all active targets by influence
     * and populates the internal arrays used for shader upload.
     */
    public void update() {
        if (!isDirty) return;

        activeList.clear();
        for (Map.Entry<String, Float> entry : currentWeights.entrySet()) {
            float w = entry.getValue();
            if (w > 0.001f) { // Culling threshold: Ignore very small weights
                VxMorphTarget def = targetsByName.get(entry.getKey());
                if (def != null) {
                    activeList.add(new ActiveMorph(def.tboOffsetTexels(), w));
                }
            }
        }

        // Sort Descending by Weight to prioritize most visible targets
        activeList.sort((a, b) -> Float.compare(b.weight, a.weight));

        // Fill Buffers
        activeCount = Math.min(activeList.size(), MAX_ACTIVE);
        for (int i = 0; i < activeCount; i++) {
            ActiveMorph am = activeList.get(i);
            shaderIndices[i] = am.offset;
            shaderWeights[i] = am.weight;
        }

        isDirty = false;
    }

    /**
     * Uploads the state to the shader uniforms.
     *
     * @param shader     The active skinning shader.
     * @param baseVertex The absolute start vertex of the mesh in the Arena (used to calculate Local ID).
     */
    public void applyToShader(VxSkinningShader shader, int baseVertex) {
        // Ensure update() is called before this if driven by animation
        shader.loadMorphState(shaderIndices, shaderWeights, activeCount, baseVertex);
    }

    /**
     * Creates a deep copy of this controller for model instancing.
     * <p>
     * The definitions (VxMorphTarget) are shared, but the weight state is independent.
     *
     * @return A new controller instance.
     */
    public VxMorphController copy() {
        VxMorphController copy = new VxMorphController(new ArrayList<>(targetsByName.values()));
        copy.currentWeights.putAll(this.currentWeights);
        copy.isDirty = true;
        return copy;
    }

    /**
     * Helper record for sorting.
     */
    private record ActiveMorph(int offset, float weight) {}
}