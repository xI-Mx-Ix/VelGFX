/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.animation;

/**
 * Represents a single animation clip stored in a baked, array-based format.
 * <p>
 * Unlike traditional object-oriented animation systems that store lists of Keyframe objects,
 * this class stores animation data as flat float arrays. This optimizes memory usage
 * and allows for highly efficient sampling/interpolation loops.
 *
 * @author xI-Mx-Ix
 */
public class VxAnimation {

    private final String name;
    private final double duration;
    private final double ticksPerSecond;

    /**
     * An array of animation channels.
     * <p>
     * The array index corresponds directly to the <b>Bone Index</b> in the {@link net.xmx.velgfx.renderer.model.skeleton.VxSkeleton}.
     * If {@code channels[i]} is null, it means bone {@code i} is not animated by this clip.
     */
    private final NodeChannel[] channels;

    /**
     * Constructs a new animation clip.
     *
     * @param name           The name of the animation.
     * @param duration       The total duration in ticks.
     * @param ticksPerSecond The playback speed (ticks per second).
     * @param channels       The array of channels, matching the skeleton's bone count.
     */
    public VxAnimation(String name, double duration, double ticksPerSecond, NodeChannel[] channels) {
        this.name = name;
        this.duration = duration;
        this.ticksPerSecond = ticksPerSecond != 0 ? ticksPerSecond : 25.0;
        this.channels = channels;
    }

    /**
     * Gets the name of the animation.
     *
     * @return The name.
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the duration of the animation in ticks.
     *
     * @return Duration.
     */
    public double getDuration() {
        return duration;
    }

    /**
     * Gets the playback speed defined in the asset.
     *
     * @return Ticks per second.
     */
    public double getTicksPerSecond() {
        return ticksPerSecond;
    }

    /**
     * Retrieves the animation channel for a specific bone.
     *
     * @param boneIndex The index of the bone in the skeleton.
     * @return The channel, or null if the bone is not animated.
     */
    public NodeChannel getChannel(int boneIndex) {
        if (boneIndex < 0 || boneIndex >= channels.length) return null;
        return channels[boneIndex];
    }

    /**
     * Holds the raw keyframe data for a single bone as flat arrays.
     * <p>
     * Data is stored as parallel arrays: {@code times} and {@code values}.
     * If an array is empty or null, that property is not animated.
     */
    public static class NodeChannel {
        // --- Translation ---
        /**
         * Timestamps for position keys.
         */
        public final float[] posTimes;
        /**
         * Position values. Stride 3 (x,y,z). Length = posTimes.length * 3.
         */
        public final float[] posValues;

        // --- Rotation ---
        /**
         * Timestamps for rotation keys.
         */
        public final float[] rotTimes;
        /**
         * Rotation values (Quaternions). Stride 4 (x,y,z,w). Length = rotTimes.length * 4.
         */
        public final float[] rotValues;

        // --- Scale ---
        /**
         * Timestamps for scale keys.
         */
        public final float[] scaleTimes;
        /**
         * Scale values. Stride 3 (x,y,z). Length = scaleTimes.length * 3.
         */
        public final float[] scaleValues;

        // --- Morph Weights ---
        /**
         * Timestamps for morph weight keys.
         */
        public final float[] weightTimes;
        /**
         * Morph weight values.
         * Stride = Number of Morph Targets in the mesh.
         * Length = weightTimes.length * TargetCount.
         */
        public final float[] weightValues;

        /**
         * Constructs a channel with raw data arrays.
         *
         * @param posTimes     Timestamps for position.
         * @param posValues    Flat values for position.
         * @param rotTimes     Timestamps for rotation.
         * @param rotValues    Flat values for rotation.
         * @param scaleTimes   Timestamps for scale.
         * @param scaleValues  Flat values for scale.
         * @param weightTimes  Timestamps for weights.
         * @param weightValues Flat values for weights.
         */
        public NodeChannel(float[] posTimes, float[] posValues,
                           float[] rotTimes, float[] rotValues,
                           float[] scaleTimes, float[] scaleValues,
                           float[] weightTimes, float[] weightValues) {
            this.posTimes = posTimes;
            this.posValues = posValues;
            this.rotTimes = rotTimes;
            this.rotValues = rotValues;
            this.scaleTimes = scaleTimes;
            this.scaleValues = scaleValues;
            this.weightTimes = weightTimes;
            this.weightValues = weightValues;
        }
    }
}