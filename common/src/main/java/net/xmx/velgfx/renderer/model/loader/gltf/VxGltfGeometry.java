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
import org.joml.Vector2f;
import org.joml.Vector3f;

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
 *     <li>Generating smooth vertex Normals if they are missing.</li>
 *     <li>Generating Tangent Space vectors (using MikkTSpace approach) if missing.</li>
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
            data.normals = generateSmoothNormals(data.positions, indices);
        }

        // Tangents: Read or Generate
        if (primitive.getAttributes().containsKey("TANGENT")) {
            data.tangents = VxGltfAccessorUtil.readAccessorAsFloats(primitive.getAttributes().get("TANGENT"));
        } else {
            if (data.uvs != null) {
                data.tangents = generateTangents(data.positions, data.normals, data.uvs, indices);
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

    /**
     * Generates smooth vertex normals by accumulating face normals for shared vertices.
     *
     * @param positions The flat vertex position array.
     * @param indices   The index array defining triangles.
     * @return A flat array of normalized normal vectors.
     */
    private static float[] generateSmoothNormals(float[] positions, int[] indices) {
        float[] normals = new float[positions.length];

        Vector3f vertex0 = new Vector3f();
        Vector3f vertex1 = new Vector3f();
        Vector3f vertex2 = new Vector3f();
        Vector3f edge1 = new Vector3f();
        Vector3f edge2 = new Vector3f();
        Vector3f normal = new Vector3f();

        for (int i = 0; i < indices.length; i += 3) {
            int index0 = indices[i];
            int index1 = indices[i + 1];
            int index2 = indices[i + 2];

            readVec3(positions, index0, vertex0);
            readVec3(positions, index1, vertex1);
            readVec3(positions, index2, vertex2);

            // Calculate Face Normal
            vertex1.sub(vertex0, edge1);
            vertex2.sub(vertex0, edge2);
            edge1.cross(edge2, normal).normalize();

            // Accumulate normal to all vertices of the face
            addVec3(normals, index0, normal);
            addVec3(normals, index1, normal);
            addVec3(normals, index2, normal);
        }

        // Normalize the accumulated results
        for (int i = 0; i < normals.length; i += 3) {
            float x = normals[i];
            float y = normals[i + 1];
            float z = normals[i + 2];
            float length = (float) Math.sqrt(x * x + y * y + z * z);
            if (length > 0) {
                normals[i] /= length;
                normals[i + 1] /= length;
                normals[i + 2] /= length;
            } else {
                normals[i + 1] = 1; // Default to Up vector
            }
        }
        return normals;
    }

    /**
     * Generates Tangent Space vectors using a Triangle Basis approach (Standard MikkTSpace approx).
     * <p>
     * Calculates the tangent vector (XYZ) and the handedness (W) required for
     * Normal Mapping in the shader.
     *
     * @return A flat array of tangent vectors (Vec4).
     */
    private static float[] generateTangents(float[] positions, float[] normals, float[] uvs, int[] indices) {
        int vertexCount = positions.length / 3;
        float[] tangents = new float[vertexCount * 4];

        // Accumulators for tangent basis vectors
        Vector3f[] tan1Accumulator = new Vector3f[vertexCount];
        Vector3f[] tan2Accumulator = new Vector3f[vertexCount];
        for (int i = 0; i < vertexCount; i++) {
            tan1Accumulator[i] = new Vector3f();
            tan2Accumulator[i] = new Vector3f();
        }

        Vector3f v1 = new Vector3f(), v2 = new Vector3f(), v3 = new Vector3f();
        Vector2f w1 = new Vector2f(), w2 = new Vector2f(), w3 = new Vector2f();
        Vector3f sDirection = new Vector3f(), tDirection = new Vector3f();

        for (int i = 0; i < indices.length; i += 3) {
            int i1 = indices[i];
            int i2 = indices[i + 1];
            int i3 = indices[i + 2];

            readVec3(positions, i1, v1);
            readVec3(positions, i2, v2);
            readVec3(positions, i3, v3);
            readVec2(uvs, i1, w1);
            readVec2(uvs, i2, w2);
            readVec2(uvs, i3, w3);

            float x1 = v2.x - v1.x, x2 = v3.x - v1.x;
            float y1 = v2.y - v1.y, y2 = v3.y - v1.y;
            float z1 = v2.z - v1.z, z2 = v3.z - v1.z;

            float s1 = w2.x - w1.x, s2 = w3.x - w1.x;
            float t1 = w2.y - w1.y, t2 = w3.y - w1.y;

            float r = 1.0f / (s1 * t2 - s2 * t1);
            if (Float.isInfinite(r) || Float.isNaN(r)) r = 1.0f;

            sDirection.set((t2 * x1 - t1 * x2) * r, (t2 * y1 - t1 * y2) * r, (t2 * z1 - t1 * z2) * r);
            tDirection.set((s1 * x2 - s2 * x1) * r, (s1 * y2 - s2 * y1) * r, (s1 * z2 - s2 * z1) * r);

            tan1Accumulator[i1].add(sDirection);
            tan1Accumulator[i2].add(sDirection);
            tan1Accumulator[i3].add(sDirection);
            tan2Accumulator[i1].add(tDirection);
            tan2Accumulator[i2].add(tDirection);
            tan2Accumulator[i3].add(tDirection);
        }

        Vector3f normal = new Vector3f(), tangent = new Vector3f(), temp = new Vector3f();
        for (int i = 0; i < vertexCount; i++) {
            readVec3(normals, i, normal);
            tangent.set(tan1Accumulator[i]);

            // Gram-Schmidt Orthogonalization
            temp.set(normal).mul(normal.dot(tangent));
            tangent.sub(temp).normalize();

            tangents[i * 4] = tangent.x;
            tangents[i * 4 + 1] = tangent.y;
            tangents[i * 4 + 2] = tangent.z;

            // Calculate Handedness (W component)
            temp.set(normal).cross(tangent);
            tangents[i * 4 + 3] = (temp.dot(tan2Accumulator[i]) < 0.0f) ? -1.0f : 1.0f;
        }
        return tangents;
    }

    // --- Helper Methods ---

    private static void readVec3(float[] buffer, int index, Vector3f out) {
        out.x = buffer[index * 3];
        out.y = buffer[index * 3 + 1];
        out.z = buffer[index * 3 + 2];
    }

    private static void readVec2(float[] buffer, int index, Vector2f out) {
        out.x = buffer[index * 2];
        out.y = buffer[index * 2 + 1];
    }

    private static void addVec3(float[] buffer, int index, Vector3f value) {
        buffer[index * 3] += value.x;
        buffer[index * 3 + 1] += value.y;
        buffer[index * 3 + 2] += value.z;
    }

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