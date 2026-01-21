/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.ref.Cleaner;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A centralized garbage collector for OpenGL and Arena resources.
 * <p>
 * This class bridges Java's Garbage Collection (which runs on background threads)
 * with the OpenGL context (which is confined to the Render Thread).
 * <p>
 * When a tracked object (like a {@link net.xmx.velgfx.renderer.model.VxSkinnedModel})
 * is phantom-reachable, the {@link Cleaner} enqueues a deallocation task.
 * This task is then executed safely on the Render Thread at the start of the next frame.
 *
 * @author xI-Mx-Ix
 */
public class VxGlGarbageCollector {

    private static final Logger LOGGER = LogManager.getLogger("VelGFX GC");
    private static final VxGlGarbageCollector INSTANCE = new VxGlGarbageCollector();

    /**
     * The Java Cleaner instance, which manages the reference queue and background thread.
     */
    private final Cleaner cleaner = Cleaner.create();

    /**
     * A thread-safe queue holding deallocation tasks that must run on the Render Thread.
     */
    private final Queue<Runnable> renderThreadQueue = new ConcurrentLinkedQueue<>();

    private VxGlGarbageCollector() {}

    public static VxGlGarbageCollector getInstance() {
        return INSTANCE;
    }

    /**
     * Registers an object for cleanup.
     * <p>
     * <b>IMPORTANT:</b> The {@code deallocator} Runnable must NOT hold any reference
     * to the {@code instance} being tracked, or the object will never be garbage collected.
     * It should only capture specific resource handles (like IDs or MemorySegments).
     *
     * @param instance    The high-level object to track (e.g., VxSkinnedModel).
     * @param deallocator The action to run on the Render Thread to free resources.
     * @return A {@link Cleaner.Cleanable} instance, allowing manual triggering of the cleanup.
     */
    public Cleaner.Cleanable track(Object instance, Runnable deallocator) {
        // We wrap the user's deallocator. When the Cleaner triggers (on a background thread),
        // we simply add the task to the queue. We do NOT run the deallocator immediately.
        return cleaner.register(instance, () -> renderThreadQueue.add(deallocator));
    }

    /**
     * Processes all pending deallocation tasks.
     * <p>
     * This method must be called exactly once per frame on the main Render Thread.
     */
    public void processQueue() {
        if (renderThreadQueue.isEmpty()) return;

        Runnable task;
        int count = 0;
        // Poll until empty to handle all pending cleanups
        while ((task = renderThreadQueue.poll()) != null) {
            try {
                task.run();
                count++;
            } catch (Exception e) {
                LOGGER.error("Error during OpenGL garbage collection", e);
            }
        }
        
        // Optional: Debug log if heavy cleanup occurred
        if (count > 10) {
            LOGGER.debug("VxGlGarbageCollector cleaned up {} resources this frame.", count);
        }
    }
}