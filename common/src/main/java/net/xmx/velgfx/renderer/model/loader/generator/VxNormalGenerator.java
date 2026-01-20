/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.loader.generator;

import org.joml.Vector3f;

/**
 * A utility class responsible for generating smooth vertex normals.
 * <p>
 * This class calculates normals for meshes that lack them by computing face normals
 * for every triangle and accumulating them for shared vertices. The result is
 * normalized to produce smooth shading across the surface.
 *
 * @author xI-Mx-Ix
 */
public class VxNormalGenerator {

    /**
     * Generates smooth vertex normals by accumulating face normals for shared vertices.
     *
     * @param positions The flat vertex position array (x, y, z).
     * @param indices   The index array defining triangles.
     * @return A flat array of normalized normal vectors (x, y, z).
     */
    public static float[] generateSmoothNormals(float[] positions, int[] indices) {
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
     * Adds a 3-component vector to the values in a flat array at the given index.
     *
     * @param buffer The destination array.
     * @param index  The vertex index (stride is 3).
     * @param value  The vector to add.
     */
    private static void addVec3(float[] buffer, int index, Vector3f value) {
        buffer[index * 3] += value.x;
        buffer[index * 3 + 1] += value.y;
        buffer[index * 3 + 2] += value.z;
    }
}