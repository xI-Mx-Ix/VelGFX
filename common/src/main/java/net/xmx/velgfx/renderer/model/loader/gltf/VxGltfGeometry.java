/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.loader.gltf;

import de.javagl.jgltf.model.*;
import net.xmx.velgfx.renderer.gl.VxDrawCommand;
import net.xmx.velgfx.renderer.gl.VxIndexBuffer;
import net.xmx.velgfx.renderer.gl.layout.VxSkinnedVertexLayout;
import net.xmx.velgfx.renderer.gl.layout.VxStaticVertexLayout;
import net.xmx.velgfx.renderer.gl.material.VxMaterial;
import net.xmx.velgfx.renderer.model.loader.generator.VxNormalGenerator;
import net.xmx.velgfx.renderer.model.loader.generator.VxTangentGenerator;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Handles the extraction, processing, and generation of mesh geometry.
 * <p>
 * This class is responsible for:
 * <ul>
 *     <li>Reading vertex attributes (Position, Normal, UV, etc.) from Accessors.</li>
 *     <li>Flipping UV coordinates vertically (1.0 - V) for OpenGL.</li>
 *     <li>Delegating Normal generation to {@link VxNormalGenerator} if missing.</li>
 *     <li>Delegating Tangent generation to {@link VxTangentGenerator} if missing.</li>
 *     <li>Interleaving the data into the specific Vertex Layout (Static vs Skinned).</li>
 * </ul>
 *
 * @author xI-Mx-Ix
 */
public class VxGltfGeometry {

    /**
     * Holds the binary result of the geometry processing.
     */
    public static class GeometryResult {
        public final ByteBuffer vertices;
        public final ByteBuffer indices;

        public GeometryResult(ByteBuffer vertices, ByteBuffer indices) {
            this.vertices = vertices;
            this.indices = indices;
        }
    }

    /**
     * Internal container for unpacked vertex attributes.
     */
    private static class AttributeData {
        float[] positions;
        float[] normals;
        float[] tangents;
        float[] uvs;
        float[] colors;
        float[] weights; // Skinning weights (Vec4)
        int[] joints;    // Skinning joint indices (Vec4)
        int vertexCount;
    }

    /**
     * Processes geometry for a static model (Rigid Body).
     * Uses {@link VxStaticVertexLayout} (Packed bytes for Normals/Tangents).
     */
    public static GeometryResult processStaticGeometry(GltfModel model, List<VxMaterial> materials, List<VxDrawCommand> outCommands) {
        return processInternal(model, materials, outCommands, false, null);
    }

    /**
     * Processes geometry for a skinned model (Skeletal Animation).
     * Uses {@link VxSkinnedVertexLayout} (Full floats for all attributes).
     */
    public static GeometryResult processSkinnedGeometry(GltfModel model, List<VxMaterial> materials, List<VxDrawCommand> outCommands, List<VxGltfStructure.BoneDefinition> boneDefs) {
        return processInternal(model, materials, outCommands, true, boneDefs);
    }

    /**
     * Internal method to process geometry.
     */
    private static GeometryResult processInternal(GltfModel model, List<VxMaterial> materials, List<VxDrawCommand> outCommands, boolean skinned, List<VxGltfStructure.BoneDefinition> boneDefs) {
        // 1. Build Skinning Definitions if needed
        if (skinned && boneDefs != null) {
            buildBoneDefinitions(model, boneDefs);
        }

        // 2. Calculate Total Buffer Requirements
        int totalVertices = 0;
        int totalIndices = 0;

        for (MeshModel mesh : model.getMeshModels()) {
            for (MeshPrimitiveModel primitive : mesh.getMeshPrimitiveModels()) {
                AccessorModel positionAccessor = primitive.getAttributes().get("POSITION");
                if (positionAccessor != null) {
                    totalVertices += positionAccessor.getCount();
                }
                AccessorModel indexAccessor = primitive.getIndices();
                if (indexAccessor != null) {
                    totalIndices += indexAccessor.getCount();
                }
            }
        }

        // 3. Allocate Direct ByteBuffers
        int stride = skinned ? VxSkinnedVertexLayout.STRIDE : VxStaticVertexLayout.STRIDE;
        ByteBuffer vertexBuffer = ByteBuffer.allocateDirect(totalVertices * stride).order(ByteOrder.nativeOrder());
        ByteBuffer indexBuffer = ByteBuffer.allocateDirect(totalIndices * VxIndexBuffer.BYTES_PER_INDEX).order(ByteOrder.nativeOrder());

        int vertexOffset = 0;
        long indexOffsetBytes = 0;

        // 4. Iterate and Process Primitives
        for (MeshModel mesh : model.getMeshModels()) {
            for (MeshPrimitiveModel primitive : mesh.getMeshPrimitiveModels()) {
                // Determine Material
                int materialIndex = model.getMaterialModels().indexOf(primitive.getMaterialModel());
                VxMaterial material = (materialIndex >= 0 && materialIndex < materials.size())
                        ? materials.get(materialIndex)
                        : materials.get(0);

                // Read Indices
                int[] indices = VxGltfAccessorUtil.readAccessorAsInts(primitive.getIndices());

                // Read and Process Vertex Attributes (Generates Normals/Tangents if missing)
                AttributeData attributes = readAndProcessAttributes(primitive, indices);

                // Interleave Vertices into Buffer
                for (int i = 0; i < attributes.vertexCount; i++) {

                    if (skinned) {
                        // --- Skinned Layout (Stride 80, Floats) ---

                        // 1. Position (vec3)
                        vertexBuffer.putFloat(attributes.positions[i * 3])
                                .putFloat(attributes.positions[i * 3 + 1])
                                .putFloat(attributes.positions[i * 3 + 2]);

                        // 2. UV0 (vec2)
                        if (attributes.uvs != null) {
                            vertexBuffer.putFloat(attributes.uvs[i * 2]).putFloat(attributes.uvs[i * 2 + 1]);
                        } else {
                            vertexBuffer.putFloat(0f).putFloat(0f);
                        }

                        // 3. Normal (vec3 - Float High Precision)
                        vertexBuffer.putFloat(attributes.normals[i * 3])
                                .putFloat(attributes.normals[i * 3 + 1])
                                .putFloat(attributes.normals[i * 3 + 2]);

                        // 4. Tangent (vec4 - Float High Precision)
                        vertexBuffer.putFloat(attributes.tangents[i * 4])
                                .putFloat(attributes.tangents[i * 4 + 1])
                                .putFloat(attributes.tangents[i * 4 + 2])
                                .putFloat(attributes.tangents[i * 4 + 3]);

                        // 5. Weights (vec4)
                        if (attributes.weights != null) {
                            vertexBuffer.putFloat(attributes.weights[i * 4])
                                    .putFloat(attributes.weights[i * 4 + 1])
                                    .putFloat(attributes.weights[i * 4 + 2])
                                    .putFloat(attributes.weights[i * 4 + 3]);
                        } else {
                            vertexBuffer.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
                        }

                        // 6. Indices (vec4)
                        if (attributes.joints != null) {
                            vertexBuffer.putFloat((float) attributes.joints[i * 4])
                                    .putFloat((float) attributes.joints[i * 4 + 1])
                                    .putFloat((float) attributes.joints[i * 4 + 2])
                                    .putFloat((float) attributes.joints[i * 4 + 3]);
                        } else {
                            vertexBuffer.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
                        }

                    } else {
                        // --- Static Layout (Stride 44, Packed) ---

                        // 1. Position (vec3)
                        vertexBuffer.putFloat(attributes.positions[i * 3])
                                .putFloat(attributes.positions[i * 3 + 1])
                                .putFloat(attributes.positions[i * 3 + 2]);

                        // 2. Color (Packed White or Attribute)
                        if (attributes.colors != null) {
                            // Convert float color to packed int
                            int r = (int) (attributes.colors[i * 4] * 255);
                            int g = (int) (attributes.colors[i * 4 + 1] * 255);
                            int b = (int) (attributes.colors[i * 4 + 2] * 255);
                            int a = (int) (attributes.colors[i * 4 + 3] * 255);
                            int packed = (a << 24) | (b << 16) | (g << 8) | r;
                            vertexBuffer.putInt(packed);
                        } else {
                            vertexBuffer.putInt(0xFFFFFFFF);
                        }

                        // 3. UV0 (vec2)
                        if (attributes.uvs != null) {
                            vertexBuffer.putFloat(attributes.uvs[i * 2]).putFloat(attributes.uvs[i * 2 + 1]);
                        } else {
                            vertexBuffer.putFloat(0f).putFloat(0f);
                        }

                        // 4. UV2 / Padding (short2)
                        vertexBuffer.putShort((short) 0).putShort((short) 0);

                        // 5. Normal (Packed Byte)
                        packVec3ToByte(vertexBuffer, attributes.normals, i, 127);

                        // 6. Tangent (Packed Byte)
                        packVec4ToByte(vertexBuffer, attributes.tangents, i, 127);

                        // 7. Padding
                        vertexBuffer.put((byte) 0);

                        // 8. MidTex (vec2 - Copy of UV0)
                        if (attributes.uvs != null) {
                            vertexBuffer.putFloat(attributes.uvs[i * 2]).putFloat(attributes.uvs[i * 2 + 1]);
                        } else {
                            vertexBuffer.putFloat(0f).putFloat(0f);
                        }
                    }
                }

                // Write Indices
                for (int idx : indices) {
                    indexBuffer.putInt(idx);
                }

                // Create Draw Command
                outCommands.add(new VxDrawCommand(material, indices.length, indexOffsetBytes, vertexOffset));

                vertexOffset += attributes.vertexCount;
                indexOffsetBytes += (long) indices.length * VxIndexBuffer.BYTES_PER_INDEX;
            }
        }

        vertexBuffer.flip();
        indexBuffer.flip();
        return new GeometryResult(vertexBuffer, indexBuffer);
    }

    /**
     * Reads raw accessors and processes them into a unified AttributeData structure.
     * This method handles the logic for missing attributes and UV flipping.
     */
    private static AttributeData readAndProcessAttributes(MeshPrimitiveModel primitive, int[] indices) {
        AttributeData data = new AttributeData();

        // Position is mandatory
        data.positions = VxGltfAccessorUtil.readAccessorAsFloats(primitive.getAttributes().get("POSITION"));
        data.vertexCount = data.positions.length / 3;

        // Texture Coordinates (Flip V)
        if (primitive.getAttributes().containsKey("TEXCOORD_0")) {
            data.uvs = VxGltfAccessorUtil.readAccessorAsFloats(primitive.getAttributes().get("TEXCOORD_0"));
        }

        // Vertex Colors
        if (primitive.getAttributes().containsKey("COLOR_0")) {
            data.colors = VxGltfAccessorUtil.readAccessorAsFloats(primitive.getAttributes().get("COLOR_0"));
        }

        // Normals: Read or Generate
        if (primitive.getAttributes().containsKey("NORMAL")) {
            data.normals = VxGltfAccessorUtil.readAccessorAsFloats(primitive.getAttributes().get("NORMAL"));
        } else {
            // Delegate to the specialized generator
            data.normals = VxNormalGenerator.generateSmoothNormals(data.positions, indices);
        }

        // Tangents: Read or Generate
        if (primitive.getAttributes().containsKey("TANGENT")) {
            data.tangents = VxGltfAccessorUtil.readAccessorAsFloats(primitive.getAttributes().get("TANGENT"));
        } else {
            if (data.uvs != null) {
                // Delegate to the specialized generator
                data.tangents = VxTangentGenerator.generateTangents(data.positions, data.normals, data.uvs, indices);
            } else {
                // Fallback tangents if no UVs exist (Default to 1,0,0,1)
                data.tangents = new float[data.vertexCount * 4];
                for (int i = 0; i < data.tangents.length; i += 4) {
                    data.tangents[i] = 1;
                    data.tangents[i + 3] = 1;
                }
            }
        }

        // Skinning Data
        if (primitive.getAttributes().containsKey("WEIGHTS_0")) {
            data.weights = VxGltfAccessorUtil.readAccessorAsFloats(primitive.getAttributes().get("WEIGHTS_0"));
        }
        if (primitive.getAttributes().containsKey("JOINTS_0")) {
            data.joints = VxGltfAccessorUtil.readAccessorAsInts(primitive.getAttributes().get("JOINTS_0"));
        }

        return data;
    }

    // --- Helper Methods ---

    private static void packVec3ToByte(ByteBuffer buffer, float[] data, int index, int scale) {
        buffer.put((byte) (data[index * 3] * scale))
                .put((byte) (data[index * 3 + 1] * scale))
                .put((byte) (data[index * 3 + 2] * scale));
    }

    private static void packVec4ToByte(ByteBuffer buffer, float[] data, int index, int scale) {
        buffer.put((byte) (data[index * 4] * scale))
                .put((byte) (data[index * 4 + 1] * scale))
                .put((byte) (data[index * 4 + 2] * scale))
                .put((byte) (data[index * 4 + 3] * scale));
    }

    private static void buildBoneDefinitions(GltfModel model, List<VxGltfStructure.BoneDefinition> defs) {
        if (model.getSkinModels().isEmpty()) return;
        SkinModel skin = model.getSkinModels().get(0);

        for (int i = 0; i < skin.getJoints().size(); i++) {
            NodeModel node = skin.getJoints().get(i);
            org.joml.Matrix4f inverseBindMatrix = new org.joml.Matrix4f();

            AccessorModel accessor = skin.getInverseBindMatrices();
            if (accessor != null) {
                float[] m = VxGltfAccessorUtil.readAccessorAsFloats(accessor);
                float[] matrixData = new float[16];
                System.arraycopy(m, i * 16, matrixData, 0, 16);
                inverseBindMatrix.set(matrixData);
            }
            defs.add(new VxGltfStructure.BoneDefinition(i, node.getName(), inverseBindMatrix));
        }
    }
}