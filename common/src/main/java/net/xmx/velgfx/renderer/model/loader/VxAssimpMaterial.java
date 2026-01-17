/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.loader;

import net.xmx.velgfx.renderer.VelGFX;
import net.xmx.velgfx.renderer.gl.material.VxMaterial;
import net.xmx.velgfx.renderer.gl.state.VxBlendMode;
import net.xmx.velgfx.renderer.gl.state.VxRenderType;
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
 * <p>
 * <b>Update:</b> Now supports detection of Alpha Mode (Opaque, Mask, Blend) from standard
 * material properties (e.g. glTF AI_MATKEY_GLTF_ALPHAMODE or generic Opacity).
 *
 * @author xI-Mx-Ix
 */
public class VxAssimpMaterial {

    // Assimp glTF Alpha Mode constants
    private static final String KEY_GLTF_ALPHAMODE = "$mat.gltf.alphaMode";
    private static final String KEY_GLTF_ALPHACUTOFF = "$mat.gltf.alphaCutoff";
    private static final int ALPHAMODE_OPAQUE = 0; // "OPAQUE"
    private static final int ALPHAMODE_MASK = 1;   // "MASK"
    private static final int ALPHAMODE_BLEND = 2;  // "BLEND"

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
                mat.baseColorFactor[3] = color.a();
            }
            color.free();

            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer val = stack.mallocFloat(1);
                IntBuffer size = stack.mallocInt(1);
                AIString propString = AIString.malloc(stack);

                // --- PBR Properties ---
                size.put(0, 1);
                if (Assimp.aiGetMaterialFloatArray(aiMat, Assimp.AI_MATKEY_ROUGHNESS_FACTOR, Assimp.aiTextureType_NONE, 0, val, size) == Assimp.aiReturn_SUCCESS) {
                    mat.roughnessFactor = val.get(0);
                } else {
                    size.put(0, 1);
                    if (Assimp.aiGetMaterialFloatArray(aiMat, Assimp.AI_MATKEY_SHININESS, Assimp.aiTextureType_NONE, 0, val, size) == Assimp.aiReturn_SUCCESS) {
                        float shininess = val.get(0);
                        if (shininess > 0) {
                            mat.roughnessFactor = 1.0f - (float) Math.sqrt(shininess / 1000.0f);
                        } else {
                            mat.roughnessFactor = 1.0f;
                        }
                    }
                }

                size.put(0, 1);
                if (Assimp.aiGetMaterialFloatArray(aiMat, Assimp.AI_MATKEY_METALLIC_FACTOR, Assimp.aiTextureType_NONE, 0, val, size) == Assimp.aiReturn_SUCCESS) {
                    mat.metallicFactor = val.get(0);
                }

                IntBuffer intVal = stack.mallocInt(1);
                size.put(0, 1);
                if (Assimp.aiGetMaterialIntegerArray(aiMat, Assimp.AI_MATKEY_TWOSIDED, Assimp.aiTextureType_NONE, 0, intVal, size) == Assimp.aiReturn_SUCCESS) {
                    mat.doubleSided = intVal.get(0) != 0;
                }

                // --- Alpha Mode Detection ---
                // 1. Try to read explicit glTF alpha mode
                boolean alphaModeFound = false;
                if (Assimp.aiGetMaterialString(aiMat, KEY_GLTF_ALPHAMODE, 0, 0, propString) == Assimp.aiReturn_SUCCESS) {
                    String modeStr = propString.dataString();
                    // Assimp sometimes returns strings, sometimes integers for this property depending on version/format
                    // Checking string values if present
                    if ("MASK".equalsIgnoreCase(modeStr)) {
                        mat.renderType = VxRenderType.CUTOUT;
                        mat.blendMode = VxBlendMode.OPAQUE; // Cutout uses opaque blending logic but with discard
                        alphaModeFound = true;
                    } else if ("BLEND".equalsIgnoreCase(modeStr)) {
                        mat.renderType = VxRenderType.TRANSLUCENT;
                        mat.blendMode = VxBlendMode.ALPHA;
                        alphaModeFound = true;
                    }
                }

                // 2. Try to read integer alpha mode (common in Assimp 5+)
                if (!alphaModeFound) {
                    size.put(0, 1);
                    if (Assimp.aiGetMaterialIntegerArray(aiMat, KEY_GLTF_ALPHAMODE, 0, 0, intVal, size) == Assimp.aiReturn_SUCCESS) {
                        int mode = intVal.get(0);
                        if (mode == ALPHAMODE_MASK) {
                            mat.renderType = VxRenderType.CUTOUT;
                            mat.blendMode = VxBlendMode.OPAQUE;
                            alphaModeFound = true;
                        } else if (mode == ALPHAMODE_BLEND) {
                            mat.renderType = VxRenderType.TRANSLUCENT;
                            mat.blendMode = VxBlendMode.ALPHA;
                            alphaModeFound = true;
                        }
                    }
                }

                // 3. Retrieve Alpha Cutoff if applicable
                if (mat.renderType == VxRenderType.CUTOUT) {
                    size.put(0, 1);
                    if (Assimp.aiGetMaterialFloatArray(aiMat, KEY_GLTF_ALPHACUTOFF, 0, 0, val, size) == Assimp.aiReturn_SUCCESS) {
                        mat.alphaCutoff = val.get(0);
                    }
                }

                // 4. Fallback: Check Generic Opacity if no specific mode was set
                if (!alphaModeFound) {
                    size.put(0, 1);
                    // AI_MATKEY_OPACITY
                    if (Assimp.aiGetMaterialFloatArray(aiMat, Assimp.AI_MATKEY_OPACITY, Assimp.aiTextureType_NONE, 0, val, size) == Assimp.aiReturn_SUCCESS) {
                        float opacity = val.get(0);
                        if (opacity < 0.99f) {
                            mat.renderType = VxRenderType.TRANSLUCENT;
                            mat.blendMode = VxBlendMode.ALPHA;
                            // Apply opacity to base color alpha
                            mat.baseColorFactor[3] *= opacity;
                        }
                    }
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
            VelGFX.LOGGER.warn("Uncompressed embedded textures are not yet implemented for index {}", textureIndex);
        }

        if (glId != -1) {
            cache.put(textureIndex, glId);
        }
        return glId;
    }
}