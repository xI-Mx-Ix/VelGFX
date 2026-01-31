/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.shader;

import net.xmx.velgfx.VelGFX;
import net.xmx.velgfx.resources.VxResourceLocation;
import net.xmx.velgfx.resources.VxShaderResourceLoader;

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
 * It ensures that the {@code #version} directive appears exactly once at the beginning
 * of the file, stripping any version directives found inside imported files.
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
     * Regex to detect version directives.
     * Used to extract the main version and strip redundant versions from imports.
     */
    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\s*#version\\s+\\d+(\\s+.*)?", Pattern.MULTILINE);

    private static final String NEWLINE = "\n";
    private static final String DEFAULT_VERSION = "#version 150";

    /**
     * Processes a raw shader source string, resolving imports and enforcing valid version definition.
     *
     * @param source          The raw GLSL source code.
     * @param callingLocation The resource location of the file being processed.
     *                        This is used as the context for resolving relative imports.
     * @return The fully resolved shader source code ready for compilation.
     */
    public String process(String source, VxResourceLocation callingLocation) {
        StringBuilder buffer = new StringBuilder();

        // 1. Determine the GLSL version.
        // We scan the root source for a #version directive.
        // If found, we use it as the global version. If not, we default to 150.
        String versionLine = detectVersion(source);
        buffer.append(versionLine).append(NEWLINE);

        // 2. Resolve imports and append the body content.
        // Any #version lines encountered during this process (including the one we just read)
        // will be skipped to prevent syntax errors.
        Set<String> includedFiles = new HashSet<>();
        resolveImports(source, buffer, includedFiles, callingLocation);

        return buffer.toString();
    }

    /**
     * Scans the source string for a version directive.
     *
     * @param source The shader source code.
     * @return The entire line containing the version directive, or the default version if none is found.
     */
    private String detectVersion(String source) {
        Matcher matcher = VERSION_PATTERN.matcher(source);
        if (matcher.find()) {
            return matcher.group();
        }
        return DEFAULT_VERSION;
    }

    /**
     * Recursively scans the source for import directives and appends content to the buffer.
     * Ignores #version directives to ensure the final shader only has one version header.
     *
     * @param source          The source code fragment to scan.
     * @param buffer          The string builder accumulating the final code.
     * @param includedFiles   A set of already included file paths to prevent cyclic dependencies.
     * @param contextLocation The location of the file currently being processed.
     */
    private void resolveImports(String source, StringBuilder buffer, Set<String> includedFiles, VxResourceLocation contextLocation) {
        List<String> lines = source.lines().collect(Collectors.toList());

        for (String line : lines) {
            // Check for #import
            Matcher importMatcher = IMPORT_PATTERN.matcher(line);

            if (importMatcher.find()) {
                String importString = importMatcher.group(1);
                processImport(importString, buffer, includedFiles, contextLocation);
            }
            // Check for #version
            else if (VERSION_PATTERN.matcher(line).find()) {
                // We skip #version lines here because the correct version
                // has already been written to the top of the buffer in the process() method.
                // This allows included files to have their own #version (for IDE support)
                // without breaking the final compiled shader.
                continue;
            }
            // Normal code line
            else {
                buffer.append(line).append(NEWLINE);
            }
        }
    }

    /**
     * Resolves a specific import string and recursively processes the imported file.
     *
     * @param importString    The path string found in the #import directive.
     * @param buffer          The string builder accumulating the final code.
     * @param includedFiles   A set of already included file paths.
     * @param contextLocation The location of the file triggering this import.
     */
    private void processImport(String importString, StringBuilder buffer, Set<String> includedFiles, VxResourceLocation contextLocation) {
        VxResourceLocation importLoc;

        // Determine if the import is Namespaced (Absolute) or Relative
        if (importString.contains(":")) {
            // Format: "namespace:path/to/file"
            String[] parts = importString.split(":", 2);
            importLoc = new VxResourceLocation(parts[0], parts[1]);
        } else {
            // Format: "relative/path/file.glsl" or "../lib.glsl"
            importLoc = new VxResourceLocation(contextLocation.getDirectory(), importString);
        }

        String canonicalPath = importLoc.getPath();

        if (includedFiles.contains(canonicalPath)) {
            // Skip already included files to prevent double declaration errors
            return;
        }

        try {
            String importedSource = VxShaderResourceLoader.loadShaderSource(importLoc);
            includedFiles.add(canonicalPath);

            buffer.append("// --- BEGIN IMPORT: ").append(canonicalPath).append(" ---").append(NEWLINE);

            // Recursively resolve imports within the imported file.
            resolveImports(importedSource, buffer, includedFiles, importLoc);

            buffer.append("// --- END IMPORT: ").append(canonicalPath).append(" ---").append(NEWLINE);
        } catch (IOException e) {
            VelGFX.LOGGER.error("Failed to resolve #import '{}' in shader '{}'", importString, contextLocation, e);
            buffer.append("#error Failed to import ").append(importString).append(NEWLINE);
        }
    }
}