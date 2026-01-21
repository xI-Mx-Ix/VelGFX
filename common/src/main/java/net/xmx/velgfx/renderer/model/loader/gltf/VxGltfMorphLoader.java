/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.loader.gltf;

import de.javagl.jgltf.model.AccessorModel;
import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.MeshModel;
import de.javagl.jgltf.model.MeshPrimitiveModel;
import net.xmx.velgfx.renderer.gl.mesh.arena.skinning.VxMorphTextureAtlas;
import net.xmx.velgfx.renderer.model.morph.VxMorphTarget;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles extraction of Morph Target deltas from glTF models.
 * <p>
 * This class performs the following complex operations:
 * <ol>
 *     <li>Iterates over all primitives in a mesh.</li>
 *     <li>Flattens Sparse Accessors into dense arrays suitable for Texture Buffers.</li>
 *     <li>Consolidates data from multiple primitives into a unified buffer layout.</li>
 *     <li>Uploads the processed deltas to the global {@link VxMorphTextureAtlas}.</li>
 *     <li>Extracts target names from mesh extras or generates fallback names.</li>
 * </ol>
 *
 * @author xI-Mx-Ix
 */
public class VxGltfMorphLoader {

    /**
     * Extracts all morph targets for the skinned model.
     * <p>
     * Note: This method currently assumes the Skinned Model consists of a single logical mesh
     * (split into primitives). If the glTF contains multiple Mesh nodes, it processes the first valid one
     * found in the skinning context, matching the behavior of {@link VxGltfGeometry}.
     *
     * @param gltfModel The loaded glTF model.
     * @return A list of MorphTarget definitions pointing to the data in the TBO.
     */
    public static List<VxMorphTarget> extractMorphTargets(GltfModel gltfModel) {
        List<VxMorphTarget> result = new ArrayList<>();

        // Find the mesh used for skinning. 
        // In this simplified loader, we iterate MeshModels. 
        // A robust implementation would follow the Node hierarchy or Skin definition.
        if (gltfModel.getMeshModels().isEmpty()) return result;

        // For this implementation, we process the first mesh found, as VxGltfGeometry
        // typically flattens the first mesh hierarchy it encounters for the main body.
        MeshModel mesh = gltfModel.getMeshModels().get(0);
        List<MeshPrimitiveModel> primitives = mesh.getMeshPrimitiveModels();
        
        if (primitives.isEmpty()) return result;

        // Check if there are any targets at all in the first primitive
        if (primitives.get(0).getTargets().isEmpty()) return result;
        
        // Determine number of targets based on the first primitive
        int targetCount = primitives.get(0).getTargets().size();
        
        // Extract names from Mesh Extras
        List<String> names = extractTargetNames(mesh, targetCount);

        // We must accumulate deltas for ALL primitives for EACH target index.
        // Because the vertices in the Arena are concatenated (Primitive 0 + Primitive 1 + ...),
        // the TBO for "Target 0" must also be (Deltas Prim 0 + Deltas Prim 1 + ...).
        
        for (int tIndex = 0; tIndex < targetCount; tIndex++) {
            // 1. Accumulate all float data for this target index across all primitives
            FloatArrayList combinedBuffer = new FloatArrayList();
            
            for (MeshPrimitiveModel primitive : primitives) {
                // Ensure primitive has targets and this index exists
                if (tIndex < primitive.getTargets().size()) {
                    Map<String, AccessorModel> attributes = primitive.getTargets().get(tIndex);
                    
                    // Vertex count for this primitive
                    // Access POSITION to know exact vertex count
                    AccessorModel posAccessor = primitive.getAttributes().get("POSITION");
                    int vertexCount = posAccessor.getCount();
                    
                    // Extract Dense Arrays (handling sparse internally via Jgltf util)
                    float[] posDeltas = getDenseArray(attributes.get("POSITION"), vertexCount, 3);
                    float[] normDeltas = getDenseArray(attributes.get("NORMAL"), vertexCount, 3);
                    float[] tanDeltas = getDenseArray(attributes.get("TANGENT"), vertexCount, 3);
                    
                    // Interleave and Append
                    for (int v = 0; v < vertexCount; v++) {
                        // Texel 0: Position
                        combinedBuffer.add(posDeltas != null ? posDeltas[v*3] : 0f);
                        combinedBuffer.add(posDeltas != null ? posDeltas[v*3+1] : 0f);
                        combinedBuffer.add(posDeltas != null ? posDeltas[v*3+2] : 0f);
                        
                        // Texel 1: Normal
                        combinedBuffer.add(normDeltas != null ? normDeltas[v*3] : 0f);
                        combinedBuffer.add(normDeltas != null ? normDeltas[v*3+1] : 0f);
                        combinedBuffer.add(normDeltas != null ? normDeltas[v*3+2] : 0f);
                        
                        // Texel 2: Tangent
                        combinedBuffer.add(tanDeltas != null ? tanDeltas[v*3] : 0f);
                        combinedBuffer.add(tanDeltas != null ? tanDeltas[v*3+1] : 0f);
                        combinedBuffer.add(tanDeltas != null ? tanDeltas[v*3+2] : 0f);
                    }
                } else {
                    // This primitive lacks this target index (rare but possible). 
                    // Fill with zeros for every vertex in this primitive to maintain TBO alignment.
                    int vertexCount = primitive.getAttributes().get("POSITION").getCount();
                    for(int k=0; k < vertexCount * 9; k++) {
                        combinedBuffer.add(0f);
                    }
                }
            }
            
            // 2. Upload the combined buffer to the TBO
            int tboOffset = VxMorphTextureAtlas.getInstance().upload(combinedBuffer.toArray());
            
            // 3. Register Target
            String name = (tIndex < names.size()) ? names.get(tIndex) : "Target_" + tIndex;
            result.add(new VxMorphTarget(name, tIndex, tboOffset));
        }

        return result;
    }

    /**
     * Reads an accessor into a dense array, ensuring correct size.
     */
    private static float[] getDenseArray(AccessorModel accessor, int vertexCount, int components) {
        if (accessor == null) return null;

        // JglTF's readAccessorAsFloats handles Sparse expansion automatically.
        float[] data = VxGltfAccessorUtil.readAccessorAsFloats(accessor);

        if (data.length != vertexCount * components) {
            // Mismatch handling (e.g., corrupted file). Return null to zero-fill.
            return null;
        }
        return data;
    }

    /**
     * Extracts target names from the Mesh "extras" or "targetNames" property.
     */
    @SuppressWarnings("unchecked")
    private static List<String> extractTargetNames(MeshModel mesh, int count) {
        List<String> names = new ArrayList<>();
        
        // Try to read "extras.targetNames" (Standard glTF convention)
        Object extras = mesh.getExtras();
        if (extras instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) extras;
            if (map.containsKey("targetNames")) {
                Object targetNamesObj = map.get("targetNames");
                if (targetNamesObj instanceof List) {
                    List<?> list = (List<?>) targetNamesObj;
                    for (Object o : list) {
                        names.add(String.valueOf(o));
                    }
                }
            }
        }

        // Fill remaining with defaults if list is too short or missing
        while (names.size() < count) {
            names.add("Target_" + names.size());
        }
        
        return names;
    }
    
    /**
     * Simple float array list to avoid boxing overhead during accumulation.
     */
    private static class FloatArrayList {
        private float[] data = new float[1024];
        private int size = 0;
        
        public void add(float val) {
            if (size >= data.length) {
                float[] newData = new float[data.length * 2];
                System.arraycopy(data, 0, newData, 0, data.length);
                data = newData;
            }
            data[size++] = val;
        }
        
        public float[] toArray() {
            float[] result = new float[size];
            System.arraycopy(data, 0, result, 0, size);
            return result;
        }
    }
}