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
 * This class handles the mapping of PBR Metallic-Roughness properties, texture extractions,
 * and render state configuration. It delegates complex texture conversions to {@link VxPBRGenerator}.
 *
 * @author xI-Mx-Ix
 */
public class VxGltfMaterial {

    /**
     * Iterates over all materials in the glTF model and converts them.
     *
     * @param model The source glTF model.
     * @return A list of populated VelGFX materials.
     */
    public static List<VxMaterial> parseMaterials(GltfModel model) {
        List<VxMaterial> list = new ArrayList<>();

        if (model.getMaterialModels().isEmpty()) {
            list.add(new VxMaterial("Default_Material"));
            return list;
        }

        for (MaterialModel matModel : model.getMaterialModels()) {
            String name = matModel.getName() != null ? matModel.getName() : "Mat_" + list.size();
            VxMaterial material = new VxMaterial(name);

            // Cast to V2 model to access PBR-specific properties conveniently
            if (matModel instanceof MaterialModelV2 materialV2) {

                // --- 1. Base Color (Albedo) ---
                float[] baseColor = materialV2.getBaseColorFactor();
                if (baseColor != null) {
                    System.arraycopy(baseColor, 0, material.baseColorFactor, 0, 4);
                }

                TextureModel baseColorTexture = materialV2.getBaseColorTexture();
                if (baseColorTexture != null) {
                    material.albedoMapGlId = uploadEmbeddedTexture(baseColorTexture);
                    material.albedoMap = null; // Indicates usage of direct GL ID
                }

                // --- 2. Metallic & Roughness ---
                Float metallic = materialV2.getMetallicFactor();
                material.metallicFactor = metallic;

                Float roughness = materialV2.getRoughnessFactor();
                material.roughnessFactor = roughness;

                // glTF packs Metallic (Blue) and Roughness (Green) into a single texture.
                // We map this to the engine's 'SpecularMap' slot after conversion.
                TextureModel mrTexture = materialV2.getMetallicRoughnessTexture();
                if (mrTexture != null) {
                    material.specularMapGlId = processAndUploadSpecular(mrTexture);
                }

                // --- 3. Normal Map ---
                TextureModel normalTexture = materialV2.getNormalTexture();
                if (normalTexture != null) {
                    material.normalMapGlId = uploadEmbeddedTexture(normalTexture);
                }

                // --- 4. Occlusion Map ---
                TextureModel occlusionTexture = materialV2.getOcclusionTexture();
                if (occlusionTexture != null) {
                    material.occlusionMapGlId = uploadEmbeddedTexture(occlusionTexture);
                }
                material.occlusionStrength = materialV2.getOcclusionStrength();

                // --- 5. Emissive ---
                float[] emissiveFactor = materialV2.getEmissiveFactor();
                if (emissiveFactor != null) {
                    System.arraycopy(emissiveFactor, 0, material.emissiveFactor, 0, 3);
                }

                TextureModel emissiveTexture = materialV2.getEmissiveTexture();
                if (emissiveTexture != null) {
                    material.emissiveMapGlId = uploadEmbeddedTexture(emissiveTexture);
                }

                // --- 6. Render State ---
                material.doubleSided = materialV2.isDoubleSided();

                Object alphaModeObj = materialV2.getAlphaMode();
                String alphaMode = String.valueOf(alphaModeObj);

                if ("MASK".equalsIgnoreCase(alphaMode)) {
                    material.renderType = VxRenderType.CUTOUT;
                    material.blendMode = VxBlendMode.OPAQUE;
                    material.alphaCutoff = materialV2.getAlphaCutoff();
                } else if ("BLEND".equalsIgnoreCase(alphaMode)) {
                    material.renderType = VxRenderType.TRANSLUCENT;
                    material.blendMode = VxBlendMode.ALPHA;
                } else {
                    material.renderType = VxRenderType.OPAQUE;
                    material.blendMode = VxBlendMode.OPAQUE;
                }
            }

            // Generate fallback textures for any missing PBR slots
            material.ensureGenerated();

            list.add(material);
        }
        return list;
    }

    /**
     * Helper to process the Metallic-Roughness map using the PBR utility.
     */
    private static int processAndUploadSpecular(TextureModel textureModel) {
        if (textureModel == null || textureModel.getImageModel() == null) {
            return -1;
        }
        ByteBuffer imageData = textureModel.getImageModel().getImageData();
        if (imageData == null) return -1;

        try (VxNativeImage image = VxNativeImage.read(imageData)) {
            return VxPBRGenerator.convertAndUploadMetallicRoughness(image);
        } catch (Exception e) {
            VelGFX.LOGGER.error("Failed to read Metallic-Roughness image for processing", e);
            return -1;
        }
    }

    /**
     * Extracts raw image data from a JglTF texture model and uploads it to the GPU.
     */
    private static int uploadEmbeddedTexture(TextureModel textureModel) {
        if (textureModel == null || textureModel.getImageModel() == null) {
            return -1;
        }

        ImageModel imageModel = textureModel.getImageModel();
        ByteBuffer imageData = imageModel.getImageData();

        if (imageData == null) {
            VelGFX.LOGGER.warn("Texture image data is missing for: " + textureModel);
            return -1;
        }

        try (VxNativeImage nativeImage = VxNativeImage.read(imageData)) {
            return VxTextureLoader.uploadTexture(nativeImage);
        } catch (Exception e) {
            VelGFX.LOGGER.error("Failed to upload glTF embedded texture", e);
            return -1;
        }
    }
}