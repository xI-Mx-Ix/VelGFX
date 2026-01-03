/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.resources;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * A custom resource location implementation for the VelGFX pipeline.
 * <p>
 * This class handles the resolution of file paths for models and textures.
 * It supports standard Minecraft-like namespaced locations (e.g., "modid" : "path")
 * as well as relative path resolution for internal asset loading.
 *
 * @author xI-Mx-Ix
 */
public class VxResourceLocation {

    private final String path;

    /**
     * Constructs a new VxResourceLocation from a full path.
     *
     * @param fullPath The full path to the resource (e.g., "assets/velgfx/models/ball.obj").
     */
    public VxResourceLocation(String fullPath) {
        this.path = fullPath.replace('\\', '/');
    }

    /**
     * Constructs a new VxResourceLocation using a namespace and a path, OR a parent directory and a relative path.
     * <p>
     * <b>Mode 1: Namespace (Standard)</b><br>
     * If {@code namespaceOrBase} is a simple identifier (contains no slashes),
     * the result is formatted as: {@code assets/<namespace>/<path>}.
     * <br><i>Example:</i> {@code new VxResourceLocation("velgfx", "models/ball.obj")} -> {@code "assets/velgfx/models/ball.obj"}
     * <p>
     * <b>Mode 2: Relative Directory</b><br>
     * If {@code namespaceOrBase} is a path (contains slashes), it is treated as a base directory.
     * <br><i>Example:</i> {@code new VxResourceLocation("assets/velgfx/models/", "../textures/skin.png")} -> {@code "assets/velgfx/textures/skin.png"}
     *
     * @param namespaceOrBase The namespace (e.g., "velthoric") or base directory.
     * @param path            The path to the resource or a relative path string.
     */
    public VxResourceLocation(String namespaceOrBase, String path) {
        String normalizedBase = namespaceOrBase.replace('\\', '/');
        String normalizedPath = path.replace('\\', '/');

        // Check if the first argument looks like a path (contains separators)
        boolean isDirectoryBase = normalizedBase.contains("/");

        if (isDirectoryBase) {
            // Logic for relative path resolution (used by model loaders to find textures)
            this.path = resolvePath(normalizedBase, normalizedPath);
        } else {
            // Logic for standard Namespace + Path definition
            // Prevents double slashes if the user accidentally provided one at the start of 'path'
            if (normalizedPath.startsWith("/")) {
                normalizedPath = normalizedPath.substring(1);
            }
            this.path = "assets/" + normalizedBase + "/" + normalizedPath;
        }
    }

    /**
     * Resolves a path string containing relative segments like ".." and ".".
     *
     * @param baseDir The base directory.
     * @param target  The target path relative to the base directory.
     * @return The resolved, clean path.
     */
    private static String resolvePath(String baseDir, String target) {
        // If the target is already absolute (starts with assets/), ignore the base
        if (target.startsWith("assets/")) {
            return target;
        }

        String fullPath;
        if (!baseDir.isEmpty() && !baseDir.endsWith("/") && !target.startsWith("/")) {
            fullPath = baseDir + "/" + target;
        } else {
            fullPath = baseDir + target;
        }

        String[] parts = fullPath.split("/");
        Deque<String> stack = new ArrayDeque<>();

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