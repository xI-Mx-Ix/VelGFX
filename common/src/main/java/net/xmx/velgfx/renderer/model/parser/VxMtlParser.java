/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.parser;

import net.xmx.velgfx.renderer.gl.VxMaterial;
import net.xmx.velgfx.resources.VxResourceLocation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * A parser for .mtl files that extracts PBR material properties.
 * <p>
 * This implementation parses raw paths from the MTL file and resolves them relative
 * to the OBJ model's directory using {@link VxResourceLocation}. It supports
 * spaces in filenames and ignores common exporter flags (like -o).
 *
 * @author xI-Mx-Ix
 */
public class VxMtlParser {

    /**
     * Parses an input stream of a .mtl file.
     * <p>
     * This method iterates through the file line by line. It handles standard PBR extensions
     * (Pr/Pm) as well as legacy Phong shading parameters (Ns/Ks). Legacy parameters are
     * mathematically approximated to their PBR counterparts to ensure models exported
     * with older software still render correctly with roughness and metallic properties.
     *
     * @param inputStream   The stream containing the .mtl file data.
     * @param modelLocation The custom resource location of the parent .obj model.
     * @return A map of material names to their corresponding {@link VxMaterial} objects.
     * @throws IOException If an I/O error occurs.
     */
    public static Map<String, VxMaterial> parse(InputStream inputStream, VxResourceLocation modelLocation) throws IOException {
        Map<String, VxMaterial> materials = new HashMap<>();
        // Use UTF-8 to ensure special characters in texture names are preserved
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            VxMaterial currentMaterial = null;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // Split into exactly two parts: the keyword and the rest of the line.
                // This preserves spaces in texture paths (e.g., "map_Kd Metal Chassis.png").
                String[] parts = line.split("\\s+", 2);
                if (parts.length < 2) continue;

                String keyword = parts[0];
                String data = parts[1].trim();

                if (keyword.equals("newmtl")) {
                    currentMaterial = new VxMaterial(data);
                    materials.put(data, currentMaterial);
                    continue;
                }

                if (currentMaterial == null) {
                    continue;
                }

                switch (keyword) {
                    // --- Base Color / Albedo ---
                    case "Kd" -> parseColor(data, currentMaterial.baseColorFactor);
                    case "d" -> currentMaterial.baseColorFactor[3] = parseFloat(data, 1.0f);
                    case "Tr" -> currentMaterial.baseColorFactor[3] = 1.0f - parseFloat(data, 0.0f);

                    // --- Explicit PBR Extensions (Modern Export) ---
                    case "Pm", "metallic" -> currentMaterial.metallicFactor = parseFloat(data, 0.0f);
                    case "Pr", "roughness" -> currentMaterial.roughnessFactor = parseFloat(data, 1.0f);

                    // --- Legacy Phong to PBR Conversion ---

                    // 'Ns' represents the Specular Exponent (Shininess), typically ranging from 0 to 1000 in Blender.
                    // We convert this to PBR Roughness (0 to 1) using the Blinn-Phong to GGX approximation formula:
                    // Roughness = (2 / (Ns + 2))^0.25.
                    case "Ns" -> {
                        float shininess = parseFloat(data, 0.0f);
                        shininess = Math.max(0.0f, shininess);
                        currentMaterial.roughnessFactor = (float) Math.pow(2.0 / (shininess + 2.0), 0.25);
                    }

                    // 'Ks' represents the Specular Color.
                    // For PBR, we use a simple heuristic: if the specular color is extremely bright (> 0.9),
                    // we assume the material is metallic. Otherwise, we default to dielectric (metallic = 0.0).
                    case "Ks" -> {
                        float[] ks = new float[3];
                        parseColor(data, ks);
                        float brightness = (ks[0] + ks[1] + ks[2]) / 3.0f;
                        if (brightness > 0.95f) {
                            currentMaterial.metallicFactor = 1.0f;
                        }
                    }

                    // --- Texture Maps ---
                    // Resolves the texture path relative to the OBJ file.
                    case "map_Kd" -> {
                        String cleanPath = stripTextureOptions(data);
                        // Construct the location using the directory of the model as the base.
                        currentMaterial.albedoMap = new VxResourceLocation(modelLocation.getDirectory(), cleanPath);
                    }
                }
            }
        }
        return materials;
    }

    /**
     * Removes exporter flags (like -o, -s) from the texture line string.
     * <p>
     * Some exporters format lines like {@code map_Kd -o 1.1 1.0 texture.png}.
     * This method heuristically attempts to extract just the filename.
     *
     * @param data The line data after the keyword.
     * @return The clean filename.
     */
    private static String stripTextureOptions(String data) {
        // If data contains spaces, it might be "filename with spaces.png" OR "-o 1.1 1.1 filename.png"
        // Heuristic: If it starts with '-', parse tokens. Otherwise assume full string is path.
        if (data.startsWith("-")) {
            String[] tokens = data.split("\\s+");
            // Return the last token, assuming it is the filename.
            // Note: This naive approach works for most standard Wavefront exporters.
            return tokens[tokens.length - 1];
        }
        return data;
    }

    /**
     * Parses a string of color components (R G B) into a float array.
     *
     * @param data   The string containing space-separated float values.
     * @param target The float array (at least size 3) to store the result.
     */
    private static void parseColor(String data, float[] target) {
        String[] parts = data.split("\\s+");
        if (parts.length >= 3) {
            target[0] = parseFloat(parts[0], 1.0f);
            target[1] = parseFloat(parts[1], 1.0f);
            target[2] = parseFloat(parts[2], 1.0f);
        }
    }

    /**
     * Safely parses a float from a string, returning a default value on failure.
     *
     * @param s            The string to parse.
     * @param defaultValue The value to return if parsing fails.
     * @return The parsed float or the default value.
     */
    private static float parseFloat(String s, float defaultValue) {
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}