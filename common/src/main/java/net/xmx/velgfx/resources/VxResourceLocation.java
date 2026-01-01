/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.resources;

import java.util.Objects;

/**
 * A custom resource location implementation that bypasses Minecraft's strict
 * naming conventions (lowercase only, specific characters).
 * <p>
 * This class allows paths with uppercase letters, spaces, and other symbols
 * supported by the underlying file system or ZIP standard. It is used exclusively
 * within the VelGFX pipeline for loading models and textures.
 *
 * @author xI-Mx-Ix
 */
public class VxResourceLocation {

    private final String path;

    /**
     * Constructs a new VxResourceLocation.
     *
     * @param path The full path to the resource (e.g., "assets/velthoric/models/Car Model.obj").
     */
    public VxResourceLocation(String path) {
        // Normalize separators to forward slashes to ensure consistency across OS
        this.path = path.replace('\\', '/');
    }

    /**
     * Constructs a new VxResourceLocation relative to a parent directory.
     * <p>
     * This method handles relative path segments (e.g., "../") to resolve
     * the canonical path.
     *
     * @param directory The base directory (must end with '/').
     * @param rawPath   The relative path (e.g., "../textures/Image.png").
     */
    public VxResourceLocation(String directory, String rawPath) {
        this(resolvePath(directory, rawPath));
    }

    /**
     * Resolves a path string containing relative segments like ".." and ".".
     *
     * @param baseDir The base directory.
     * @param target  The target path relative to the base directory.
     * @return The resolved, clean path.
     */
    private static String resolvePath(String baseDir, String target) {
        String fullPath = baseDir + target.replace('\\', '/');
        String[] parts = fullPath.split("/");
        
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();

        for (String part : parts) {
            if (part.equals(".") || part.isEmpty()) {
                continue;
            } else if (part.equals("..")) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
            } else {
                stack.addLast(part);
            }
        }

        return String.join("/", stack);
    }

    public String getPath() {
        return path;
    }

    /**
     * Extracts the directory part of this path.
     *
     * @return The directory string including the trailing slash, or empty string if none.
     */
    public String getDirectory() {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash != -1 ? path.substring(0, lastSlash + 1) : "";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VxResourceLocation that = (VxResourceLocation) o;
        return Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path);
    }

    @Override
    public String toString() {
        return path;
    }
}