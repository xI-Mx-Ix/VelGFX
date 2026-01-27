/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.util;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

/**
 * A thread-local cache for reusable "scratch" objects to eliminate Garbage Collection pressure.
 * <p>
 * High-frequency operations like skeletal animation and rendering matrix calculations
 * require many intermediate objects (Vectors, Matrices, Buffers). Allocating these
 * on the heap every frame causes significant GC churn.
 * <p>
 * This class provides pre-allocated, mutable instances that can be reused within a single
 * method scope. <b>Data in this cache is volatile and persists only as long as the
 * caller holds the reference.</b>
 *
 * @author xI-Mx-Ix
 */
public class VxTempCache {

    private static final ThreadLocal<VxTempCache> THREAD_LOCAL = ThreadLocal.withInitial(VxTempCache::new);

    /**
     * Retrieves the cache instance for the current thread.
     *
     * @return The thread-local cache.
     */
    public static VxTempCache get() {
        return THREAD_LOCAL.get();
    }

    // --- Matrices (4x4) ---
    public final Matrix4f mat4_1 = new Matrix4f();
    public final Matrix4f mat4_2 = new Matrix4f();
    public final Matrix4f mat4_3 = new Matrix4f();
    public final Matrix4f mat4_4 = new Matrix4f();
    public final Matrix4f mat4_5 = new Matrix4f();

    // --- Matrices (3x3) ---
    public final Matrix3f mat3_1 = new Matrix3f();
    public final Matrix3f mat3_2 = new Matrix3f();
    public final Matrix3f mat3_3 = new Matrix3f();
    public final Matrix3f mat3_4 = new Matrix3f();
    public final Matrix3f mat3_5 = new Matrix3f();

    // --- Vectors ---
    public final Vector3f vec3_1 = new Vector3f();
    public final Vector3f vec3_2 = new Vector3f();
    public final Vector3f vec3_3 = new Vector3f();
    public final Vector3f vec3_4 = new Vector3f();
    public final Vector3f vec3_5 = new Vector3f();

    // --- Quaternions ---
    public final Quaternionf quat_1 = new Quaternionf();
    public final Quaternionf quat_2 = new Quaternionf();
    public final Quaternionf quat_3 = new Quaternionf();
    public final Quaternionf quat_4 = new Quaternionf();
    public final Quaternionf quat_5 = new Quaternionf();

    // --- Buffers ---
    /**
     * A reusable FloatBuffer with capacity 16 (sufficient for 4x4 matrices).
     * <b>Note:</b> Caller must call {@code .clear()} before writing and {@code .flip()} before reading.
     */
    public final FloatBuffer floatBuffer16 = BufferUtils.createFloatBuffer(16);

    /**
     * A reusable FloatBuffer with capacity 9 (sufficient for 3x3 matrices).
     * <b>Note:</b> Caller must call {@code .clear()} before writing and {@code .flip()} before reading.
     */
    public final FloatBuffer floatBuffer9 = BufferUtils.createFloatBuffer(9);

    /**
     * Private constructor to enforce ThreadLocal usage.
     */
    private VxTempCache() {
    }
}