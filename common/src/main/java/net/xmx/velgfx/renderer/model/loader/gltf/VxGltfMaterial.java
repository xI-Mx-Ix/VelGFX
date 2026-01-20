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
import net.xmx.velgfx.renderer.gl.state.VxBlendMode;
import net.xmx.velgfx.renderer.gl.state.VxRenderType;
import net.xmx.velgfx.resources.VxNativeImage;
import net.xmx.velgfx.resources.VxTextureLoader;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles the parsing of Materials from glTF models.
 * <p>
 * This class translates the glTF 2.0 Physically Based Rendering (PBR) Metallic-Roughness
 * model into the VelGFX material system. It handles:
 * <ul>
 *     <li>Base Color, Metallic, and Roughness factors.</li>
 *     <li>Alpha Modes (Opaque, Mask/Cutout, Blend/Translucent).</li>
 *     <li>Extraction and upload of embedded textures (Albedo, Normal).</li>
 * </ul>
 *
 * @author xI-Mx-Ix
 */
public class VxGltfMaterial {

    /**
     * Parses all materials present in the glTF model.
     *
     * @param model The source glTF model.
     * @return A list of parsed VelGFX materials.
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

            // JglTF handles version compatibility, but we cast to V2 to access PBR properties specifically.
            if (matModel instanceof MaterialModelV2 materialV2) {

                // 1. Base Color Factor (RGBA)
                float[] baseColor = materialV2.getBaseColorFactor();
                if (baseColor != null) {
                    System.arraycopy(baseColor, 0, material.baseColorFactor, 0, 4);
                }

                // 2. Metallic Factor
                Float metallic = materialV2.getMetallicFactor();
                material.metallicFactor = metallic;

                // 3. Roughness Factor
                Float roughness = materialV2.getRoughnessFactor();
                material.roughnessFactor = roughness;

                // 4. Double Sided
                material.doubleSided = materialV2.isDoubleSided();

                // 5. Alpha Mode (Render State)
                // We convert the object to String to handle potentially different return types (Enum/String) safely.
                Object alphaModeObj = materialV2.getAlphaMode();
                String alphaMode = String.valueOf(alphaModeObj);

                if ("MASK".equalsIgnoreCase(alphaMode)) {
                    material.renderType = VxRenderType.CUTOUT;
                    material.blendMode = VxBlendMode.OPAQUE;
                    Float cutoff = materialV2.getAlphaCutoff();
                    material.alphaCutoff = cutoff != null ? cutoff : 0.5f;
                } else if ("BLEND".equalsIgnoreCase(alphaMode)) {
                    material.renderType = VxRenderType.TRANSLUCENT;
                    material.blendMode = VxBlendMode.ALPHA;
                } else {
                    material.renderType = VxRenderType.OPAQUE;
                    material.blendMode = VxBlendMode.OPAQUE;
                }

                // 6. Textures
                TextureModel baseColorTexture = materialV2.getBaseColorTexture();
                if (baseColorTexture != null) {
                    material.albedoMapGlId = uploadEmbeddedTexture(baseColorTexture);
                    // Set resource location to null to indicate we are using a direct OpenGL ID
                    material.albedoMap = null;
                }

                TextureModel normalTexture = materialV2.getNormalTexture();
                if (normalTexture != null) {
                    material.normalMapGlId = uploadEmbeddedTexture(normalTexture);
                }
            }

            // Ensure necessary PBR maps (Normal/Specular) exist, generating defaults if missing.
            material.ensureGenerated();

            list.add(material);
        }
        return list;
    }

    /**
     * Extracts the image data from a texture model and uploads it to the GPU.
     *
     * @param textureModel The texture model containing the image.
     * @return The OpenGL texture ID, or -1 if the upload failed.
     */
    private static int uploadEmbeddedTexture(TextureModel textureModel) {
        if (textureModel == null || textureModel.getImageModel() == null) {
            return -1;
        }

        ImageModel imageModel = textureModel.getImageModel();
        ByteBuffer imageData = imageModel.getImageData();

        if (imageData == null) {
            VelGFX.LOGGER.warn("Texture image data is null for: " + textureModel);
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