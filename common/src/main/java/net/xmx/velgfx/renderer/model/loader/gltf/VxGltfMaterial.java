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
import net.xmx.velgfx.renderer.gl.material.VxPBRTexturePipeline;
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
 * This class is responsible for loading embedded image data and delegating
 * the PBR texture baking process to the GPU via {@link VxPBRTexturePipeline}.
 *
 * @author xI-Mx-Ix
 */
public class VxGltfMaterial {

    /**
     * Parses all materials in a glTF model and converts them to VelGFX materials.
     * <p>
     * This process includes:
     * <ul>
     *     <li>Decoding embedded images into native memory.</li>
     *     <li>Extracting scalar factors (Metallic, Roughness, etc.).</li>
     *     <li>Invoking the GPU PBR generator to bake consolidated texture maps.</li>
     *     <li>Uploading normal maps directly.</li>
     * </ul>
     *
     * @param model The source glTF model.
     * @return A list of ready-to-render materials.
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

            if (matModel instanceof MaterialModelV2 materialV2) {
                // --- 1. Load Raw Images (RAM) ---
                // Using try-with-resources ensures native memory is freed after upload
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

                    material.roughnessFactor = materialV2.getRoughnessFactor();
                    material.metallicFactor = materialV2.getMetallicFactor();
                    material.occlusionStrength = materialV2.getOcclusionStrength();

                    // --- 3. GPU Bake Process ---
                    // Generates Albedo (Baked) and Specular (LabPBR) textures on the GPU
                    VxPBRTexturePipeline.createLabPBRTexture(material, albedoImg, mrImg, occlusionImg, emissiveImg);

                    // Mark source locations as null since we generated GL IDs directly
                    material.albedoMap = null;
                    material.specularMap = null;

                    // --- 4. Upload Normal Map (Direct) ---
                    if (normalImg != null) {
                        material.normalMapGlId = VxTextureLoader.uploadTexture(normalImg);
                    }

                    // --- 5. Configure Render State ---
                    material.doubleSided = materialV2.isDoubleSided();
                    configureBlendMode(material, materialV2);
                }
            }

            // Fallback generation if baking didn't occur (e.g. no V2 model)
            // or if specific maps (Normal) are still missing.
            material.ensureGenerated();
            list.add(material);
        }
        return list;
    }

    /**
     * Configures the material's blend mode based on the glTF Alpha Mode.
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