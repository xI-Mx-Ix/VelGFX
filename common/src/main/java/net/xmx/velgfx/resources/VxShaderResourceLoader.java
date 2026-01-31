/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.resources;

import net.xmx.velgfx.VelGFX;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * A specialized loader for text-based resources, primarily GLSL shader source code.
 * <p>
 * This class abstracts the stream handling and character set decoding, ensuring
 * that shader files are read correctly from the classpath using {@link VxResourceLocation}.
 *
 * @author xI-Mx-Ix
 */
public class VxShaderResourceLoader {

    /**
     * Reads the content of a resource file into a String.
     *
     * @param location The custom resource location of the file.
     * @return The complete file content as a String, including newlines.
     * @throws IOException If the resource cannot be found or an I/O error occurs.
     */
    public static String loadShaderSource(VxResourceLocation location) throws IOException {
        String path = location.getPath();
        // Ensure absolute path for classpath resource loading
        String classpathPath = path.startsWith("/") ? path : "/" + path;

        try (InputStream stream = VxShaderResourceLoader.class.getResourceAsStream(classpathPath)) {
            if (stream == null) {
                throw new IOException("Shader resource not found in classpath: " + location);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            VelGFX.LOGGER.error("Failed to load shader source: {}", location, e);
            throw e;
        }
    }
}