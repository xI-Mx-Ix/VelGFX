/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.animation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/**
 * Represents a single animation clip (e.g., "Walk", "Run", "Idle").
 * <p>
 * An animation consists of multiple channels. Each channel corresponds to a specific
 * {@link net.xmx.velgfx.renderer.model.skeleton.VxNode} and contains time-stamped keyframes
 * for Position, Rotation, Scaling, and Morph Weights.
 *
 * @author xI-Mx-Ix
 */
public class VxAnimation {

    private final String name;
    private final double duration;
    private final double ticksPerSecond;

    /**
     * Map linking Node Names to their specific animation data channels.
     */
    private final Map<String, NodeChannel> channels;

    /**
     * Constructs a new animation clip.
     *
     * @param name           The name of the animation.
     * @param duration       The total duration in ticks.
     * @param ticksPerSecond The playback speed (ticks per second).
     * @param channels       A map linking Node Names to their animation channels.
     */
    public VxAnimation(String name, double duration, double ticksPerSecond, Map<String, NodeChannel> channels) {
        this.name = name;
        this.duration = duration;
        this.ticksPerSecond = ticksPerSecond != 0 ? ticksPerSecond : 25.0; // Default Assimp fallback
        this.channels = channels;
    }

    /**
     * Gets the name of the animation.
     * @return The name.
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the total duration of the animation in ticks.
     * @return The duration.
     */
    public double getDuration() {
        return duration;
    }

    /**
     * Gets the number of ticks per second defined in the asset.
     * @return Ticks per second.
     */
    public double getTicksPerSecond() {
        return ticksPerSecond;
    }

    /**
     * Retrieves the animation channel for a specific node name.
     *
     * @param nodeName The name of the node (bone).
     * @return The channel containing keyframes, or null if this node is not animated.
     */
    public NodeChannel getChannel(String nodeName) {
        return channels.get(nodeName);
    }

    /**
     * Holds the keyframes for a single node's animation properties.
     * <p>
     * Includes channels for:
     * <ul>
     *     <li>Translation (Position)</li>
     *     <li>Rotation (Quaternion)</li>
     *     <li>Scale (Vector3)</li>
     *     <li>Morph Weights (Float Array)</li>
     * </ul>
     */
    public static class NodeChannel {
        /**
         * Keyframes for position changes.
         */
        public final List<Key<Vector3f>> positions;

        /**
         * Keyframes for rotation changes.
         */
        public final List<Key<Quaternionf>> rotations;

        /**
         * Keyframes for scale changes.
         */
        public final List<Key<Vector3f>> scalings;

        /**
         * Keyframes for morph target weight changes.
         * <p>
         * The float array corresponds to the sequence of morph targets defined in the glTF mesh.
         * For example, index 0 in the array maps to Target 0 of the mesh.
         */
        public final List<Key<float[]>> weights;

        /**
         * Constructs a new channel with the specified keyframes.
         *
         * @param positions List of position keys.
         * @param rotations List of rotation keys.
         * @param scalings  List of scaling keys.
         * @param weights   List of morph weight keys (can be null or empty).
         */
        public NodeChannel(List<Key<Vector3f>> positions,
                           List<Key<Quaternionf>> rotations,
                           List<Key<Vector3f>> scalings,
                           List<Key<float[]>> weights) {
            this.positions = positions;
            this.rotations = rotations;
            this.scalings = scalings;
            this.weights = weights;
        }
    }

    /**
     * Represents a single keyframe at a specific time.
     *
     * @param <T>   The type of value stored (Vector3f, Quaternionf, or float[]).
     * @param time  The time stamp of this keyframe in ticks.
     * @param value The value at this time.
     */
    public record Key<T>(double time, T value) {}
}