/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.shader;

import net.xmx.velgfx.resources.VxResourceLocation;
import net.xmx.velgfx.resources.VxShaderResourceLoader;
import net.xmx.velgfx.renderer.VelGFX;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Handles the preprocessing of GLSL source strings before compilation.
 * <p>
 * This class is responsible for resolving {@code #import} directives recursively.
 * It supports both namespaced absolute paths (e.g., "namespace:path") and relative paths,
 * ensuring maximum flexibility for modular shader development.
 *
 * @author xI-Mx-Ix
 */
public class VxGlslPreprocessor {

    /**
     * Regex to match {@code #import <string>} or {@code #import "string"}.
     * Captures the content between the quotes or brackets.
     */
    private static final Pattern IMPORT_PATTERN = Pattern.compile("^\\s*#import\\s+[<\"](.+)[>\"]", Pattern.MULTILINE);

    /**
     * Regex to detect existing version directives to prevent duplication.
     */
    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\s*#version\\s+\\d+", Pattern.MULTILINE);

    private static final String NEWLINE = "\n";

    /**
     * Processes a raw shader source string, resolving imports and enforcing version definition.
     *
     * @param source          The raw GLSL source code.
     * @param callingLocation The resource location of the file being processed.
     *                        This is used as the context for resolving relative imports.
     * @return The fully resolved shader source code ready for compilation.
     */
    public String process(String source, VxResourceLocation callingLocation) {
        StringBuilder buffer = new StringBuilder();
        
        // Ensure #version is the very first line if not present
        if (!VERSION_PATTERN.matcher(source).find()) {
            buffer.append("#version 150").append(NEWLINE);
        }

        Set<String> includedFiles = new HashSet<>();
        resolveImports(source, buffer, includedFiles, callingLocation);

        return buffer.toString();
    }

    /**
     * Recursively scans the source for import directives and appends content to the buffer.
     *
     * @param source          The source code fragment to scan.
     * @param buffer          The string builder accumulating the final code.
     * @param includedFiles   A set of already included file paths to prevent cyclic dependencies.
     * @param contextLocation The location of the file currently being processed.
     */
    private void resolveImports(String source, StringBuilder buffer, Set<String> includedFiles, VxResourceLocation contextLocation) {
        List<String> lines = source.lines().collect(Collectors.toList());

        for (String line : lines) {
            Matcher matcher = IMPORT_PATTERN.matcher(line);

            if (matcher.find()) {
                String importString = matcher.group(1);
                VxResourceLocation importLoc;

                // Determine if the import is Namespaced (Absolute) or Relative
                if (importString.contains(":")) {
                    // Format: "namespace:path/to/file"
                    String[] parts = importString.split(":", 2);
                    importLoc = new VxResourceLocation(parts[0], parts[1]);
                } else {
                    // Format: "relative/path/file.glsl" or "../lib.glsl"
                    // Resolve relative to the current file's directory using the helper in VxResourceLocation.
                    importLoc = new VxResourceLocation(contextLocation.getDirectory(), importString);
                }

                String canonicalPath = importLoc.getPath();

                if (includedFiles.contains(canonicalPath)) {
                    // Skip already included files to prevent double declaration errors
                    continue;
                }

                try {
                    String importedSource = VxShaderResourceLoader.loadShaderSource(importLoc);
                    includedFiles.add(canonicalPath);
                    
                    buffer.append("// --- BEGIN IMPORT: ").append(canonicalPath).append(" ---").append(NEWLINE);
                    // Recursively resolve imports within the imported file.
                    // Pass the new location as the context for relative imports inside that file.
                    resolveImports(importedSource, buffer, includedFiles, importLoc);
                    buffer.append("// --- END IMPORT: ").append(canonicalPath).append(" ---").append(NEWLINE);
                } catch (IOException e) {
                    VelGFX.LOGGER.error("Failed to resolve #import '{}' in shader '{}'", importString, contextLocation, e);
                    buffer.append("#error Failed to import ").append(importString).append(NEWLINE);
                }
            } else {
                buffer.append(line).append(NEWLINE);
            }
        }
    }
}