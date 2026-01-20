/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model;

import net.xmx.velgfx.renderer.VelGFX;
import net.xmx.velgfx.renderer.gl.shader.VxSkinningShader;
import net.xmx.velgfx.renderer.model.loader.gltf.VxGltfLoader;
import net.xmx.velgfx.resources.VxResourceLocation;
import net.xmx.velgfx.resources.VxTextureLoader;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Central manager for loading and caching 3D models.
 * <p>
 * This class acts as a high-level factory for {@link VxStaticModel} and {@link VxSkinnedModel}.
 * It delegates the file parsing to {@link VxGltfLoader} and manages the lifecycle of
 * shared GPU resources.
 * <p>
 * <b>Supported Formats:</b> .obj, .fbx, .gltf, .glb, .dae (Collada), and others supported by Assimp.
 *
 * @author xI-Mx-Ix
 */
public final class VxModelManager {

    /**
     * Cache for loaded static models.
     * Key: Resource Location.
     */
    private static final Map<VxResourceLocation, VxStaticModel> STATIC_CACHE = new HashMap<>();

    /**
     * Cache for loaded skinned models.
     * Key: Resource Location.
     */
    private static final Map<VxResourceLocation, VxSkinnedModel> SKINNED_CACHE = new HashMap<>();

    private VxModelManager() {}

    /**
     * Loads or retrieves a {@link VxStaticModel}.
     * <p>
     * Static models support rigid body animation (Node Hierarchy) but no vertex deformation.
     * Ideal for machinery, vehicles, or architectural elements.
     *
     * @param location The resource location of the model file.
     * @return An optional containing the model if loaded successfully.
     */
    public static Optional<VxStaticModel> getStaticModel(VxResourceLocation location) {
        if (STATIC_CACHE.containsKey(location)) {
            return Optional.of(STATIC_CACHE.get(location));
        }

        try {
            // Pass the location directly to the loader to support JAR loading
            VxStaticModel model = VxGltfLoader.loadStatic(location);
            STATIC_CACHE.put(location, model);
            return Optional.of(model);
        } catch (Exception e) {
            VelGFX.LOGGER.error("Failed to load static model: {}", location, e);
            return Optional.empty();
        }
    }

    /**
     * Loads or retrieves a {@link VxSkinnedModel}.
     * <p>
     * Skinned models support vertex deformation via bones and weights.
     * Ideal for characters, creatures, or organic machinery.
     * <p>
     * The required {@link VxSkinningShader} is managed internally.
     *
     * @param location The resource location of the model file.
     * @return An optional containing the model if loaded successfully.
     */
    public static Optional<VxSkinnedModel> getSkinnedModel(VxResourceLocation location) {
        if (SKINNED_CACHE.containsKey(location)) {
            return Optional.of(SKINNED_CACHE.get(location));
        }

        try {
            // Pass the location directly to the loader
            VxSkinnedModel model = VxGltfLoader.loadSkinned(location);
            SKINNED_CACHE.put(location, model);
            return Optional.of(model);
        } catch (Exception e) {
            VelGFX.LOGGER.error("Failed to load skinned model: {}", location, e);
            return Optional.empty();
        }
    }

    /**
     * Clears all caches and releases GPU resources.
     * Should be called on resource reload or shutdown.
     */
    public static void clear() {
        VelGFX.LOGGER.info("Clearing VelGFX Model Caches...");

        STATIC_CACHE.values().forEach(VxStaticModel::delete);
        STATIC_CACHE.clear();

        SKINNED_CACHE.values().forEach(VxSkinnedModel::delete);
        SKINNED_CACHE.clear();

        // Texture loader should also be cleared as models hold references to textures
        VxTextureLoader.clear();

        // Release the skinning shader program
        VxSkinningShader.destroy();
    }
}