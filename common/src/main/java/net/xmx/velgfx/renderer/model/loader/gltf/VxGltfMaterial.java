/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.loader.gltf;

import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.ImageModel;
import de.javagl.jgltf.model.MaterialModel;
import de.javagl.jgltf.model.TextureModel;
import de.javagl.jgltf.model.v2.MaterialModelV2;
import net.xmx.velgfx.renderer.VelGFX;
import net.xmx.velgfx.renderer.gl.material.VxMaterial;
import net.xmx.velgfx.renderer.gl.material.VxPBRGenerator;
import net.xmx.velgfx.renderer.gl.state.VxBlendMode;
import net.xmx.velgfx.renderer.gl.state.VxRenderType;
import net.xmx.velgfx.resources.VxNativeImage;
import net.xmx.velgfx.resources.VxTextureLoader;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A parser that converts raw glTF material definitions into the engine's internal {@link VxMaterial} format.
 * <p>
 * This class is responsible for:
 * <ul>
 *     <li>Decoding embedded texture data from the model file.</li>
 *     <li>Extracting PBR factors (Metallic, Roughness, Emissive, etc.).</li>
 *     <li>Delegating the generation of GPU-ready packed textures to {@link VxPBRGenerator}.</li>
 *     <li>Configuring the blend mode and render state based on the material definition.</li>
 * </ul>
 *
 * @author xI-Mx-Ix
 */
public class VxGltfMaterial {

    /**
     * Iterates over all materials in the glTF model and converts them into VelGFX materials.
     *
     * @param model The source glTF model.
     * @return A list of populated and GPU-uploaded VelGFX materials.
     */
    public static List<VxMaterial> parseMaterials(GltfModel model) {
        List<VxMaterial> list = new ArrayList<>();

        if (model.getMaterialModels().isEmpty()) {
            // Provide a default material if the model has none
            list.add(new VxMaterial("Default_Material"));
            return list;
        }

        for (MaterialModel matModel : model.getMaterialModels()) {
            String name = matModel.getName() != null ? matModel.getName() : "Mat_" + list.size();
            VxMaterial material = new VxMaterial(name);

            if (matModel instanceof MaterialModelV2 materialV2) {
                // --- 1. Load Raw Images (Off-Heap) ---
                // We load the data into RAM first using try-with-resources.
                // This ensures the native memory is freed immediately after upload.
                try (
                        VxNativeImage albedoImg = loadTextureImage(materialV2.getBaseColorTexture());
                        VxNativeImage mrImg = loadTextureImage(materialV2.getMetallicRoughnessTexture());
                        VxNativeImage emissiveImg = loadTextureImage(materialV2.getEmissiveTexture());
                        VxNativeImage occlusionImg = loadTextureImage(materialV2.getOcclusionTexture());
                        VxNativeImage normalImg = loadTextureImage(materialV2.getNormalTexture())
                ) {
                    // --- 2. Extract Factors ---
                    float[] baseColorFactor = materialV2.getBaseColorFactor();
                    if (baseColorFactor != null) {
                        System.arraycopy(baseColorFactor, 0, material.baseColorFactor, 0, 4);
                    }

                    float[] emissiveFactor = materialV2.getEmissiveFactor();
                    if (emissiveFactor != null) {
                        System.arraycopy(emissiveFactor, 0, material.emissiveFactor, 0, 3);
                    }

                    Float roughFactor = materialV2.getRoughnessFactor();
                    material.roughnessFactor = roughFactor;

                    Float metalFactor = materialV2.getMetallicFactor();
                    material.metallicFactor = metalFactor;

                    Float occlusionStrength = materialV2.getOcclusionStrength();
                    material.occlusionStrength = occlusionStrength;

                    // --- 3. Generate & Upload Textures ---

                    // A) Albedo Map
                    // Combines Base Color + Baked Ambient Occlusion + Baked Emissive Color
                    material.albedoMapGlId = VxPBRGenerator.createAlbedoMap(
                            albedoImg,
                            occlusionImg,
                            emissiveImg,
                            material.baseColorFactor,
                            material.occlusionStrength,
                            material.emissiveFactor
                    );
                    // Mark as loaded to prevent reloading from disk later
                    material.albedoMap = null;

                    // B) Specular Map
                    // Generates LabPBR format: Smoothness, Metallic, Emissive Strength
                    material.specularMapGlId = VxPBRGenerator.createSpecularMap(
                            mrImg,
                            emissiveImg,
                            material.roughnessFactor,
                            material.metallicFactor,
                            material.emissiveFactor
                    );

                    // C) Normal Map
                    if (normalImg != null) {
                        material.normalMapGlId = VxTextureLoader.uploadTexture(normalImg);
                    } else {
                        // Explicitly generate a 1x1 flat normal map if missing
                        material.normalMapGlId = VxPBRGenerator.generateFlatNormalMap();
                    }

                    // D) Optional Standalone Maps
                    // We upload these anyway for shaders that might access them directly via custom uniforms.
                    if (emissiveImg != null) {
                        material.emissiveMapGlId = VxTextureLoader.uploadTexture(emissiveImg);
                    }
                    if (occlusionImg != null) {
                        material.occlusionMapGlId = VxTextureLoader.uploadTexture(occlusionImg);
                    }

                    // --- 4. Configure Render State ---
                    material.doubleSided = materialV2.isDoubleSided();
                    configureBlendMode(material, materialV2);
                }
            }

            // Calls ensureGenerated() to fill in any remaining gaps (fallbacks for null textures)
            // if the previous logic skipped something or if the model was not V2.
            material.ensureGenerated();
            list.add(material);
        }
        return list;
    }

    /**
     * Maps glTF Alpha Mode to internal Engine Blend Mode.
     *
     * @param material The target material.
     * @param v2       The source glTF material definition.
     */
    private static void configureBlendMode(VxMaterial material, MaterialModelV2 v2) {
        String alphaMode = String.valueOf(v2.getAlphaMode());
        if ("MASK".equalsIgnoreCase(alphaMode)) {
            material.renderType = VxRenderType.CUTOUT;
            material.blendMode = VxBlendMode.OPAQUE;
            material.alphaCutoff = v2.getAlphaCutoff();
        } else if ("BLEND".equalsIgnoreCase(alphaMode)) {
            material.renderType = VxRenderType.TRANSLUCENT;
            material.blendMode = VxBlendMode.ALPHA;
        } else {
            material.renderType = VxRenderType.OPAQUE;
            material.blendMode = VxBlendMode.OPAQUE;
        }
    }

    /**
     * Loads the raw image data from a texture model without uploading it to the GPU.
     * <p>
     * This utility reads the binary data stream and decodes it using STBImage via {@link VxNativeImage}.
     *
     * @param textureModel The glTF texture model.
     * @return A native image wrapping the data, or null if loading failed.
     */
    private static VxNativeImage loadTextureImage(TextureModel textureModel) {
        if (textureModel == null || textureModel.getImageModel() == null) {
            return null;
        }

        ImageModel imageModel = textureModel.getImageModel();
        ByteBuffer imageData = imageModel.getImageData();

        if (imageData == null) {
            return null;
        }

        try {
            // Read into native memory (decodes PNG/JPG/etc.)
            return VxNativeImage.read(imageData);
        } catch (Exception e) {
            VelGFX.LOGGER.error("Failed to decode embedded texture image", e);
            return null;
        }
    }
}