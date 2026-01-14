/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.mesh.arena;

import net.xmx.velgfx.renderer.gl.layout.IVxVertexLayout;

import java.util.HashMap;
import java.util.Map;

/**
 * A central registry managing {@link VxArenaBuffer} instances keyed by their vertex layout.
 * <p>
 * This manager ensures that meshes sharing the same vertex layout are grouped into
 * the same physical Vertex Buffer Object (VBO). This minimizes state changes and
 * optimizes memory usage by creating, retrieving, and destroying arenas on demand.
 *
 * @author xI-Mx-Ix
 */
public class VxArenaManager {

    private static VxArenaManager instance;

    /**
     * Maps vertex layouts to their corresponding arena buffers.
     */
    private final Map<IVxVertexLayout, VxArenaBuffer> arenas = new HashMap<>();

    /**
     * Private constructor for singleton pattern.
     */
    private VxArenaManager() {}

    /**
     * Retrieves the global instance of the arena manager.
     *
     * @return The singleton instance.
     */
    public static synchronized VxArenaManager getInstance() {
        if (instance == null) {
            instance = new VxArenaManager();
        }
        return instance;
    }

    /**
     * Retrieves or creates the {@link VxArenaBuffer} for a specific vertex layout.
     * <p>
     * If an arena for the given layout does not exist, it is created with a default
     * initial capacity.
     *
     * @param layout The vertex layout definition.
     * @return The managed arena buffer for this layout.
     */
    public synchronized VxArenaBuffer getArena(IVxVertexLayout layout) {
        return arenas.computeIfAbsent(layout, k -> {
            // Initial capacity: 262,144 vertices, ~786k indices (3 per vertex avg)
            return new VxArenaBuffer(layout, 262144, 786432);
        });
    }

    /**
     * Destroys all managed arenas and releases their GPU resources.
     * <p>
     * This should be called during shutdown or when the render context is destroyed.
     */
    public synchronized void destroy() {
        for (VxArenaBuffer buffer : arenas.values()) {
            buffer.delete();
        }
        arenas.clear();
    }
}