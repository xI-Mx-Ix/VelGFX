/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.raw;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.xmx.velgfx.renderer.gl.material.VxMaterial;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents the raw, editable data of a 3D model in CPU memory.
 * <p>
 * This class exposes the structure of the model (vertices, faces, groups) in a mutable way.
 * It also manages the lifecycle of {@link VxMaterial} instances associated with this model.
 *
 * @author xI-Mx-Ix
 */
public class VxRawModel {

    // --- Global Data Pools ---
    public final FloatArrayList positions = new FloatArrayList();
    public final FloatArrayList texCoords = new FloatArrayList();
    public final FloatArrayList normals = new FloatArrayList();
    public final FloatArrayList colors = new FloatArrayList();

    /**
     * The structural grouping of the model.
     * Key: Group Name (e.g., "body", "wheel").
     */
    public final Map<String, VxRawGroup> groups = new LinkedHashMap<>();

    /**
     * A registry of materials used by this model.
     * These materials own generated OpenGL resources (normal/specular maps) that must be freed.
     */
    public final Map<String, VxMaterial> materials = new LinkedHashMap<>();

    /**
     * Creates a new empty raw model.
     */
    public VxRawModel() {
        // Default white color fallback
        colors.add(1.0f); colors.add(1.0f); colors.add(1.0f);
    }

    /**
     * Gets or creates a group by name.
     * @param name The name of the group.
     * @return The raw group instance.
     */
    public VxRawGroup getGroup(String name) {
        return groups.computeIfAbsent(name, VxRawGroup::new);
    }

    /**
     * Calculates the total number of vertices required if this model were to be triangulated and flattened.
     *
     * @return The vertex count.
     */
    public int calculateTotalVertexCount() {
        int count = 0;
        for (VxRawGroup group : groups.values()) {
            for (VxRawMesh mesh : group.meshesByMaterial.values()) {
                // Each face has 3 vertices
                count += mesh.getFaceCount() * 3;
            }
        }
        return count;
    }

    /**
     * Frees all GPU resources associated with this model's materials and clears CPU data.
     * <p>
     * This <b>must</b> be called when the model is evicted from the cache to prevent
     * memory leaks of generated PBR textures.
     */
    public void destroy() {
        // Free generated OpenGL textures in materials
        for (VxMaterial material : materials.values()) {
            material.delete();
        }
        materials.clear();

        // Clear large data arrays to help GC
        positions.clear();
        texCoords.clear();
        normals.clear();
        colors.clear();
        groups.clear();
    }
}