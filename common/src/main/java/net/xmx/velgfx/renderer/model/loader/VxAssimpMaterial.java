/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.loader;

import net.xmx.velgfx.renderer.VelGFX;
import net.xmx.velgfx.renderer.gl.material.VxMaterial;
import net.xmx.velgfx.resources.VxNativeImage;
import net.xmx.velgfx.resources.VxResourceLocation;
import net.xmx.velgfx.resources.VxTextureLoader;
import org.lwjgl.assimp.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles the extraction of Materials (textures, colors, and scalar properties) from Assimp.
 * <p>
 * This class is responsible for parsing PBR properties such as Roughness and Metallic.
 * It supports standard PBR extensions in MTL/OBJ files and provides a fallback mechanism
 * to convert legacy Phong 'Shininess' values into PBR Roughness.
 * <p>
 * It also handles embedded textures (e.g. in GLB files) by extracting them directly
 * from the AIScene and uploading them to the GPU immediately.
 *
 * @author xI-Mx-Ix
 */
public class VxAssimpMaterial {

    /**
     * Parses all materials in the imported scene.
     *
     * @param scene         The imported scene containing material data.
     * @param modelLocation The resource location of the model file. Used to resolve relative texture paths.
     * @return A list of parsed {@link VxMaterial} objects corresponding to the mesh material indices.
     */
    public static List<VxMaterial> parseMaterials(AIScene scene, VxResourceLocation modelLocation) {
        int numMaterials = scene.mNumMaterials();
        List<VxMaterial> result = new ArrayList<>(numMaterials);
        String modelDir = modelLocation.getDirectory();

        // Local cache for embedded textures (Texture Index -> OpenGL ID) to avoid duplicates
        // within the same model load cycle.
        Map<Integer, Integer> embeddedTextureCache = new HashMap<>();

        for (int i = 0; i < numMaterials; i++) {
            AIMaterial aiMat = AIMaterial.create(scene.mMaterials().get(i));

            // Retrieve material name
            AIString aiName = AIString.calloc();
            Assimp.aiGetMaterialString(aiMat, Assimp.AI_MATKEY_NAME, 0, 0, aiName);
            String matName = aiName.dataString();
            if (matName.isEmpty()) matName = "mat_" + i;
            aiName.free();

            VxMaterial mat = new VxMaterial(matName);

            // Retrieve diffuse color (Base Color)
            AIColor4D color = AIColor4D.calloc();
            if (Assimp.aiGetMaterialColor(aiMat, Assimp.AI_MATKEY_COLOR_DIFFUSE, Assimp.aiTextureType_NONE, 0, color) == Assimp.aiReturn_SUCCESS) {
                mat.baseColorFactor[0] = color.r();
                mat.baseColorFactor[1] = color.g();
                mat.baseColorFactor[2] = color.b();
                mat.baseColorFactor[3] = 1.0f;
            }
            color.free();

            // Retrieve scalar PBR properties (Roughness, Metallic, DoubleSided)
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer val = stack.mallocFloat(1);
                IntBuffer size = stack.mallocInt(1);

                // Check for explicit PBR roughness
                size.put(0, 1);
                if (Assimp.aiGetMaterialFloatArray(aiMat, Assimp.AI_MATKEY_ROUGHNESS_FACTOR, Assimp.aiTextureType_NONE, 0, val, size) == Assimp.aiReturn_SUCCESS) {
                    mat.roughnessFactor = val.get(0);
                }
                // Fallback to legacy shininess if roughness is missing
                else {
                    size.put(0, 1);
                    if (Assimp.aiGetMaterialFloatArray(aiMat, Assimp.AI_MATKEY_SHININESS, Assimp.aiTextureType_NONE, 0, val, size) == Assimp.aiReturn_SUCCESS) {
                        float shininess = val.get(0);
                        if (shininess > 0) {
                            // Map 0..1000 shininess to roughly 1.0..0.0 roughness
                            mat.roughnessFactor = 1.0f - (float) Math.sqrt(shininess / 1000.0f);
                        } else {
                            mat.roughnessFactor = 1.0f;
                        }
                    }
                }

                // Check for explicit PBR metallic factor
                size.put(0, 1);
                if (Assimp.aiGetMaterialFloatArray(aiMat, Assimp.AI_MATKEY_METALLIC_FACTOR, Assimp.aiTextureType_NONE, 0, val, size) == Assimp.aiReturn_SUCCESS) {
                    mat.metallicFactor = val.get(0);
                }

                // Check for double sided flag
                IntBuffer intVal = stack.mallocInt(1);
                size.put(0, 1);
                if (Assimp.aiGetMaterialIntegerArray(aiMat, Assimp.AI_MATKEY_TWOSIDED, Assimp.aiTextureType_NONE, 0, intVal, size) == Assimp.aiReturn_SUCCESS) {
                    mat.doubleSided = intVal.get(0) != 0;
                }
            }

            // Retrieve albedo texture
            AIString path = AIString.calloc();
            if (Assimp.aiGetMaterialTexture(aiMat, Assimp.aiTextureType_DIFFUSE, 0, path, (IntBuffer) null, null, null, null, null, null) == Assimp.aiReturn_SUCCESS) {
                String rawPath = path.dataString();
                if (!rawPath.isEmpty()) {
                    if (rawPath.startsWith("*")) {
                        // Embedded Texture (e.g., *0, *1)
                        try {
                            int textureIndex = Integer.parseInt(rawPath.substring(1));
                            mat.albedoMapGlId = loadEmbeddedTexture(scene, textureIndex, embeddedTextureCache);
                            // Set to null to prevent VxArenaMesh from trying to load it as a file later
                            mat.albedoMap = null;
                        } catch (NumberFormatException e) {
                            VelGFX.LOGGER.error("Failed to parse embedded texture index: " + rawPath);
                        }
                    } else {
                        // Regular External File
                        mat.albedoMap = new VxResourceLocation(modelDir, rawPath);
                    }
                }
            }

            // Retrieve normal map
            path.clear();
            if (Assimp.aiGetMaterialTexture(aiMat, Assimp.aiTextureType_NORMALS, 0, path, (IntBuffer) null, null, null, null, null, null) == Assimp.aiReturn_SUCCESS) {
                String rawPath = path.dataString();
                if (!rawPath.isEmpty()) {
                    if (rawPath.startsWith("*")) {
                        int textureIndex = Integer.parseInt(rawPath.substring(1));
                        mat.normalMapGlId = loadEmbeddedTexture(scene, textureIndex, embeddedTextureCache);
                        mat.normalMap = null;
                    } else {
                        mat.normalMap = new VxResourceLocation(modelDir, rawPath);
                    }
                }
            }
            path.free();

            result.add(mat);
        }
        return result;
    }

    /**
     * Extracts and uploads an embedded texture from the Assimp scene.
     *
     * @param scene        The Assimp scene.
     * @param textureIndex The index of the texture in the scene's texture array.
     * @param cache        The local cache to prevent duplicate uploads.
     * @return The OpenGL texture ID, or -1 if failed.
     */
    private static int loadEmbeddedTexture(AIScene scene, int textureIndex, Map<Integer, Integer> cache) {
        if (cache.containsKey(textureIndex)) {
            return cache.get(textureIndex);
        }

        if (scene.mTextures() == null || textureIndex < 0 || textureIndex >= scene.mNumTextures()) {
            VelGFX.LOGGER.error("Invalid embedded texture index: {}", textureIndex);
            return -1;
        }

        AITexture texture = AITexture.create(scene.mTextures().get(textureIndex));
        int glId = -1;

        // Check if compressed (mHeight == 0 means the buffer contains a file like PNG/JPG)
        if (texture.mHeight() == 0) {
            int sizeInBytes = texture.mWidth();

            // Manually get the pointer address and wrap it in a ByteBuffer.
            // This bypasses the AITexture.pcData() binding issues and treats the data as raw bytes.
            long pcDataAddr = MemoryUtil.memGetAddress(texture.address() + AITexture.PCDATA);
            ByteBuffer compressedData = MemoryUtil.memByteBuffer(pcDataAddr, sizeInBytes);

            try (VxNativeImage image = VxNativeImage.read(compressedData)) {
                glId = VxTextureLoader.uploadTexture(image);
            } catch (IOException e) {
                VelGFX.LOGGER.error("Failed to decode embedded texture #{}: {}", textureIndex, e.getMessage());
            }
        } else {
            // Uncompressed ARGB8888 data (Rare in GLB/GLTF, but standard for some formats)
            // Implementation for uncompressed embedded textures omitted for now.
            VelGFX.LOGGER.warn("Uncompressed embedded textures are not yet implemented for index {}", textureIndex);
        }

        if (glId != -1) {
            cache.put(textureIndex, glId);
        }
        return glId;
    }
}