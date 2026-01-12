/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.loader;

import net.xmx.velgfx.renderer.gl.material.VxMaterial;
import net.xmx.velgfx.resources.VxResourceLocation;
import org.lwjgl.assimp.*;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles the extraction of Materials (textures, colors, and scalar properties) from Assimp.
 * <p>
 * This class is responsible for parsing PBR properties such as Roughness and Metallic.
 * It supports standard PBR extensions in MTL/OBJ files and provides a fallback mechanism
 * to convert legacy Phong 'Shininess' values into PBR Roughness.
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

            // Retrieve scalar PBR properties (Roughness, Metallic)
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
            }

            // Retrieve albedo texture
            AIString path = AIString.calloc();
            if (Assimp.aiGetMaterialTexture(aiMat, Assimp.aiTextureType_DIFFUSE, 0, path, (IntBuffer) null, null, null, null, null, null) == Assimp.aiReturn_SUCCESS) {
                String rawPath = path.dataString();
                if (!rawPath.isEmpty()) {
                    mat.albedoMap = new VxResourceLocation(modelDir, rawPath);
                }
            }

            // Retrieve normal map
            path.clear();
            if (Assimp.aiGetMaterialTexture(aiMat, Assimp.aiTextureType_NORMALS, 0, path, (IntBuffer) null, null, null, null, null, null) == Assimp.aiReturn_SUCCESS) {
                String rawPath = path.dataString();
                if (!rawPath.isEmpty()) {
                    mat.normalMap = new VxResourceLocation(modelDir, rawPath);
                }
            }
            path.free();

            result.add(mat);
        }
        return result;
    }
}