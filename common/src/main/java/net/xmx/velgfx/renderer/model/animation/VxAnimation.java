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
 * An animation consists of multiple channels. Each channel corresponds to a specific {@link net.xmx.velgfx.renderer.model.skeleton.VxNode}
 * and contains keyframes for Position, Rotation, and Scaling over time.
 *
 * @author xI-Mx-Ix
 */
public class VxAnimation {

    private final String name;
    private final double duration;
    private final double ticksPerSecond;
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

    public String getName() {
        return name;
    }

    public double getDuration() {
        return duration;
    }

    public double getTicksPerSecond() {
        return ticksPerSecond;
    }

    /**
     * Retrieves the animation channel for a specific node name.
     *
     * @param nodeName The name of the node.
     * @return The channel containing keyframes, or null if this node is not animated.
     */
    public NodeChannel getChannel(String nodeName) {
        return channels.get(nodeName);
    }

    /**
     * Holds the keyframes for a single node's animation properties.
     */
    public static class NodeChannel {
        public final List<Key<Vector3f>> positions;
        public final List<Key<Quaternionf>> rotations;
        public final List<Key<Vector3f>> scalings;

        public NodeChannel(List<Key<Vector3f>> positions, List<Key<Quaternionf>> rotations, List<Key<Vector3f>> scalings) {
            this.positions = positions;
            this.rotations = rotations;
            this.scalings = scalings;
        }
    }

    /**
     * Represents a single keyframe at a specific time.
     *
     * @param <T>  The type of value (Vector3f or Quaternionf).
     * @param time The time stamp of this keyframe.
     * @param value The value at this time.
     */
    public record Key<T>(double time, T value) {}
}