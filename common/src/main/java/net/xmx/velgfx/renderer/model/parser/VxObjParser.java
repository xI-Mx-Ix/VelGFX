/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.parser;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.xmx.velgfx.renderer.VelGFX;
import net.xmx.velgfx.renderer.gl.material.VxMaterial;
import net.xmx.velgfx.renderer.model.raw.VxRawGroup;
import net.xmx.velgfx.renderer.model.raw.VxRawMesh;
import net.xmx.velgfx.renderer.model.raw.VxRawModel;
import net.xmx.velgfx.resources.VxResourceLocation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A parser for OBJ model files that produces a {@link VxRawModel}.
 * <p>
 * This parser extracts all geometry data into an editable format using a Structure-of-Arrays
 * approach for performance. It does not perform buffer flattening, but it does handle index parsing.
 * <p>
 * Updated to use {@link VxResourceLocation} for file loading, enabling support
 * for non-standard file paths and bypassing Minecraft's Resource Manager constraints.
 *
 * @author xI-Mx-Ix
 */
public class VxObjParser {

    /**
     * Parses an OBJ model from a given {@link VxResourceLocation}.
     *
     * @param location The custom location of the .obj file.
     * @return A {@link VxRawModel} containing the raw geometry data.
     * @throws IOException If the file cannot be read.
     */
    public static VxRawModel parse(VxResourceLocation location) throws IOException {
        VxRawModel rawModel = new VxRawModel();

        String defaultGroup = "default";
        String defaultMaterial = "default";
        String currentGroupName = defaultGroup;
        String currentMaterialName = defaultMaterial;

        // Ensure default material exists in case the model has faces before any usemtl tag
        rawModel.materials.put(defaultMaterial, new VxMaterial(defaultMaterial));

        // Load stream via ClassLoader path lookup (using / prefix for absolute classpath root)
        String classpathPath = location.getPath().startsWith("/") ? location.getPath() : "/" + location.getPath();

        try (InputStream stream = VxObjParser.class.getResourceAsStream(classpathPath)) {
            if (stream == null) {
                throw new IOException("Model file not found in classpath: " + location);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith("#")) continue;

                    String[] tokens = line.trim().split("\\s+");
                    if (tokens.length == 0) continue;

                    String keyword = tokens[0];

                    switch (keyword) {
                        case "v" -> parseVertex(tokens, rawModel.positions, rawModel.colors);
                        case "vt" -> parseVec2(tokens, rawModel.texCoords);
                        case "vn" -> parseVec3(tokens, rawModel.normals);
                        case "g", "o" -> {
                            // Support group names with spaces by consuming the rest of the line
                            if (tokens.length > 1) {
                                // Extract substring starting from where the first token ends
                                currentGroupName = line.substring(line.indexOf(tokens[1])).trim();
                            } else {
                                currentGroupName = defaultGroup;
                            }
                        }
                        case "usemtl" -> {
                            if (tokens.length > 1) {
                                currentMaterialName = line.substring(line.indexOf(tokens[1])).trim();
                                rawModel.materials.computeIfAbsent(currentMaterialName, VxMaterial::new);
                            } else {
                                currentMaterialName = defaultMaterial;
                            }
                        }
                        case "mtllib" -> {
                            if (tokens.length > 1) {
                                // Extract the relative path to the MTL file (may contain spaces)
                                String rawMtlPath = line.substring(line.indexOf(tokens[1])).trim();
                                
                                // Create a new location relative to the model's directory
                                VxResourceLocation mtlLoc = new VxResourceLocation(location.getDirectory(), rawMtlPath);
                                Map<String, VxMaterial> loaded = loadMtl(mtlLoc, location);
                                rawModel.materials.putAll(loaded);
                            }
                        }
                        case "f" -> {
                            VxRawGroup group = rawModel.getGroup(currentGroupName);
                            // Get the flat index mesh for the current material
                            VxRawMesh mesh = group.getMesh(currentMaterialName);
                            parseFace(tokens, mesh);
                        }
                    }
                }
            }
        }

        return rawModel;
    }

    // --- Helper Methods ---

    /**
     * Parses a vertex line "v x y z [r g b]".
     * Supports optional vertex colors which are not standard OBJ but supported by some extensions.
     */
    private static void parseVertex(String[] tokens, FloatArrayList pos, FloatArrayList cols) {
        pos.add(Float.parseFloat(tokens[1]));
        pos.add(Float.parseFloat(tokens[2]));
        pos.add(Float.parseFloat(tokens[3]));

        if (tokens.length >= 7) {
            cols.add(Float.parseFloat(tokens[4]));
            cols.add(Float.parseFloat(tokens[5]));
            cols.add(Float.parseFloat(tokens[6]));
        }
    }

    /**
     * Parses a normal line "vn x y z".
     */
    private static void parseVec3(String[] tokens, FloatArrayList list) {
        list.add(Float.parseFloat(tokens[1]));
        list.add(Float.parseFloat(tokens[2]));
        list.add(Float.parseFloat(tokens[3]));
    }

    /**
     * Parses a UV line "vt u v". Inverts V for OpenGL compatibility.
     */
    private static void parseVec2(String[] tokens, FloatArrayList list) {
        list.add(Float.parseFloat(tokens[1]));
        list.add(1.0f - Float.parseFloat(tokens[2])); // Invert V
    }

    /**
     * Parses a face line "f v1/vt1/vn1 ...". Handles arbitrary polygons by triangulating them as a fan.
     */
    private static void parseFace(String[] tokens, VxRawMesh mesh) {
        int vertexCount = tokens.length - 1;
        if (vertexCount < 3) return;

        // Temporary arrays for fan triangulation indices
        int[] v = new int[vertexCount];
        int[] vt = new int[vertexCount];
        int[] vn = new int[vertexCount];

        for (int i = 0; i < vertexCount; i++) {
            String[] subIndices = tokens[i + 1].split("/");

            // OBJ indices are 1-based, convert to 0-based
            v[i] = Integer.parseInt(subIndices[0]) - 1;

            vt[i] = (subIndices.length > 1 && !subIndices[1].isEmpty())
                    ? Integer.parseInt(subIndices[1]) - 1 : -1;

            vn[i] = (subIndices.length > 2 && !subIndices[2].isEmpty())
                    ? Integer.parseInt(subIndices[2]) - 1 : -1;
        }

        // Triangulate (Triangle Fan)
        // Adds indices directly to the IntArrayList in VxRawMesh
        for (int i = 1; i < vertexCount - 1; ++i) {
            mesh.addFace(
                    v[0], v[i], v[i + 1],
                    vt[0], vt[i], vt[i + 1],
                    vn[0], vn[i], vn[i + 1]
            );
        }
    }

    /**
     * Loads and parses an MTL library from a relative path.
     */
    private static Map<String, VxMaterial> loadMtl(VxResourceLocation mtlLocation, VxResourceLocation modelLocation) {
        String classpathPath = mtlLocation.getPath().startsWith("/") ? mtlLocation.getPath() : "/" + mtlLocation.getPath();

        try (InputStream stream = VxObjParser.class.getResourceAsStream(classpathPath)) {
            if (stream == null) {
                VelGFX.LOGGER.warn("Material library not found: {}", mtlLocation);
                return Map.of();
            }
            return VxMtlParser.parse(stream, modelLocation);
        } catch (IOException e) {
            VelGFX.LOGGER.warn("Could not load material library: {}", mtlLocation, e);
            return Map.of();
        }
    }
}