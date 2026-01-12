/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.loader;

import net.xmx.velgfx.renderer.gl.material.VxMaterial;
import net.xmx.velgfx.resources.VxResourceLocation;
import org.lwjgl.assimp.*;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles the extraction of Materials (textures, colors) from Assimp.
 *
 * @author xI-Mx-Ix
 */
public class VxAssimpMaterial {

    /**
     * Parses all materials in the scene.
     *
     * @param scene The imported scene.
     * @param modelLocation The location of the model file (used to resolve relative texture paths).
     * @return A list of parsed materials corresponding to mesh material indices.
     */
    public static List<VxMaterial> parseMaterials(AIScene scene, VxResourceLocation modelLocation) {
        int numMaterials = scene.mNumMaterials();
        List<VxMaterial> result = new ArrayList<>(numMaterials);
        String modelDir = modelLocation.getDirectory();

        for (int i = 0; i < numMaterials; i++) {
            AIMaterial aiMat = AIMaterial.create(scene.mMaterials().get(i));

            // Get Name
            AIString aiName = AIString.calloc();
            Assimp.aiGetMaterialString(aiMat, Assimp.AI_MATKEY_NAME, 0, 0, aiName);
            String matName = aiName.dataString();
            if (matName.isEmpty()) matName = "mat_" + i;
            aiName.free();

            VxMaterial mat = new VxMaterial(matName);

            // Get Color
            AIColor4D color = AIColor4D.calloc();
            if (Assimp.aiGetMaterialColor(aiMat, Assimp.AI_MATKEY_COLOR_DIFFUSE, Assimp.aiTextureType_NONE, 0, color) == Assimp.aiReturn_SUCCESS) {
                mat.baseColorFactor[0] = color.r();
                mat.baseColorFactor[1] = color.g();
                mat.baseColorFactor[2] = color.b();
                mat.baseColorFactor[3] = 1.0f;
            }
            color.free();

            // Get Albedo Texture
            AIString path = AIString.calloc();
            if (Assimp.aiGetMaterialTexture(aiMat, Assimp.aiTextureType_DIFFUSE, 0, path, (IntBuffer) null, null, null, null, null, null) == Assimp.aiReturn_SUCCESS) {
                String rawPath = path.dataString();
                if (!rawPath.isEmpty()) {
                    // Because Custom IO is active, Assimp might return just the filename from MTL.
                    // We resolve it relative to the model location.
                    mat.albedoMap = new VxResourceLocation(modelDir, rawPath);
                }
            }

            // Get Normal Map (sometimes stored as Height map in older formats)
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