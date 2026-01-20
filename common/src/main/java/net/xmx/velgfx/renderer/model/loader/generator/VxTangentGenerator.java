/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.loader.generator;

import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * A utility class responsible for generating Tangent Space vectors.
 * <p>
 * This class implements a Triangle Basis approach (similar to MikkTSpace) to calculate
 * the tangent vector (XYZ) and the handedness (W) required for Normal Mapping.
 *
 * @author xI-Mx-Ix
 */
public class VxTangentGenerator {

    /**
     * Generates Tangent Space vectors using vertex positions, normals, and UV coordinates.
     * <p>
     * Calculates the tangent vector (XYZ) and the handedness (W) required for
     * Normal Mapping in the shader.
     *
     * @param positions The flat vertex position array (x, y, z).
     * @param normals   The flat vertex normal array (x, y, z).
     * @param uvs       The flat vertex UV array (u, v).
     * @param indices   The index array defining triangles.
     * @return A flat array of tangent vectors (x, y, z, w).
     */
    public static float[] generateTangents(float[] positions, float[] normals, float[] uvs, int[] indices) {
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

    /**
     * Reads a 3-component vector from a flat array at the given index.
     *
     * @param buffer The source array.
     * @param index  The vertex index (stride is 3).
     * @param out    The vector to store the result.
     */
    private static void readVec3(float[] buffer, int index, Vector3f out) {
        out.x = buffer[index * 3];
        out.y = buffer[index * 3 + 1];
        out.z = buffer[index * 3 + 2];
    }

    /**
     * Reads a 2-component vector from a flat array at the given index.
     *
     * @param buffer The source array.
     * @param index  The vertex index (stride is 2).
     * @param out    The vector to store the result.
     */
    private static void readVec2(float[] buffer, int index, Vector2f out) {
        out.x = buffer[index * 2];
        out.y = buffer[index * 2 + 1];
    }
}