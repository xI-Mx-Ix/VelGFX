/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.animation;

import net.xmx.velgfx.renderer.model.skeleton.VxNode;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * Manages the playback, interpolation, and state of animations for a model hierarchy.
 * <p>
 * The Animator acts as the engine for skeletal animation. It tracks the current time,
 * interpolates between keyframes of the active {@link VxAnimation}, and updates the
 * local and global transforms of the {@link VxNode} hierarchy.
 * <p>
 * It provides a comprehensive API for controlling playback, including speed, looping,
 * pausing, and manual time scrubbing.
 *
 * @author xI-Mx-Ix
 */
public class VxAnimator {

    private final VxNode rootNode;
    private VxAnimation currentAnimation;
    private double currentTime;

    // --- Control Settings ---

    /**
     * Controls the playback speed.
     * <p>
     * 1.0 is normal speed. Values > 1.0 are faster, values < 1.0 are slower.
     * Negative values play the animation backwards.
     */
    private float playbackSpeed = 1.0f;

    /**
     * Controls whether the animation loops when it reaches the end.
     */
    private boolean shouldLoop = true;

    /**
     * If true, the animation time will not advance automatically via update().
     */
    private boolean isPaused = false;

    // --- Scratch Objects (Zero Allocation) ---

    private final Matrix4f parentTransformScratch = new Matrix4f();
    private final Matrix4f localTransformScratch = new Matrix4f();
    private final Vector3f interpPos = new Vector3f();
    private final Quaternionf interpRot = new Quaternionf();
    private final Vector3f interpScale = new Vector3f();

    /**
     * Constructs an animator for the given node hierarchy.
     *
     * @param rootNode The root node of the hierarchy to animate.
     */
    public VxAnimator(VxNode rootNode) {
        this.rootNode = rootNode;
    }

    /**
     * Starts playing the specified animation clip.
     * <p>
     * This resets the time cursor to 0.0 immediately.
     *
     * @param animation The animation to play.
     */
    public void playAnimation(VxAnimation animation) {
        this.currentAnimation = animation;
        this.currentTime = 0;
    }

    /**
     * Advances the animation state or enforces the bind pose.
     * <p>
     * This method handles the temporal update of the node hierarchy:
     * <ul>
     *     <li>If an animation is active, it increments the time cursor based on {@code dtSeconds}
     *     and the {@link #playbackSpeed}, then interpolates the position, rotation, and scale
     *     of nodes based on the keyframes.</li>
     *     <li>If <b>no</b> animation is active (or the animator is reset), it triggers a
     *     hierarchical update using the static local transforms (Bind Pose) of the nodes.
     *     This step is critical for skinned meshes to prevent geometry collapse.</li>
     * </ul>
     *
     * @param dtSeconds The time elapsed since the last frame in <b>seconds</b>.
     */
    public void update(float dtSeconds) {
        if (currentAnimation != null) {
            // 1. Advance Time
            if (!isPaused && dtSeconds != 0) {
                double ticksToAdvance = currentAnimation.getTicksPerSecond() * dtSeconds * playbackSpeed;
                currentTime += ticksToAdvance;

                // Handle Looping logic
                if (shouldLoop) {
                    currentTime %= currentAnimation.getDuration();
                    // Handle negative speed wrapping
                    if (currentTime < 0) {
                        currentTime += currentAnimation.getDuration();
                    }
                } else {
                    // Clamp to start/end if not looping
                    if (currentTime > currentAnimation.getDuration()) {
                        currentTime = currentAnimation.getDuration();
                    } else if (currentTime < 0) {
                        currentTime = 0;
                    }
                }
            }

            // 2. Animate hierarchy based on current time (interpolated)
            parentTransformScratch.identity();
            calculateBoneTransform(rootNode, parentTransformScratch);

        } else {
            // 3. Fallback: Update hierarchy using static Bind Pose
            // Since vertices are stored in local space, global transforms must be calculated
            // at least once to apply the bone offset matrices correctly.
            rootNode.updateHierarchy(null);
        }
    }

    /**
     * Recursively calculates transforms for the node tree based on the animation data.
     *
     * @param node            The current node being processed.
     * @param parentTransform The global transformation matrix of the parent node.
     */
    private void calculateBoneTransform(VxNode node, Matrix4f parentTransform) {
        String nodeName = node.getName();

        // Start with the node's bind pose (default local transform)
        localTransformScratch.set(node.getLocalTransform());

        VxAnimation.NodeChannel channel = currentAnimation.getChannel(nodeName);

        // If animation data exists for this node, interpolate and overwrite local transform
        if (channel != null) {
            interpolatePosition(channel.positions, currentTime, interpPos);
            interpolateRotation(channel.rotations, currentTime, interpRot);
            interpolateScaling(channel.scalings, currentTime, interpScale);

            localTransformScratch.translation(interpPos)
                    .rotate(interpRot)
                    .scale(interpScale);
        }

        // Calculate Global Transform: ParentGlobal * Local
        // We access the node's matrix directly to update it in-place
        Matrix4f globalTransform = node.getGlobalTransform();
        globalTransform.set(parentTransform).mul(localTransformScratch);

        // Propagate to children
        for (VxNode child : node.getChildren()) {
            calculateBoneTransform(child, globalTransform);
        }
    }

    // --- Control API ---

    /**
     * Sets the playback speed multiplier.
     *
     * @param speed The speed factor (Default: 1.0). Negative values reverse playback.
     */
    public void setSpeed(float speed) {
        this.playbackSpeed = speed;
    }

    /**
     * Pauses or resumes the animation.
     * <p>
     * When paused, calling {@link #update(float)} will still recalculate matrices based on
     * the current time, but the time cursor will not advance.
     *
     * @param paused True to pause, false to resume.
     */
    public void setPaused(boolean paused) {
        this.isPaused = paused;
    }

    /**
     * Sets whether the animation should loop when it reaches the end.
     *
     * @param loop True to loop, false to clamp at the last frame.
     */
    public void setLooping(boolean loop) {
        this.shouldLoop = loop;
    }

    /**
     * Manually sets the current time cursor of the animation in ticks.
     * <p>
     * This allows for manual scrubbing or synchronization.
     *
     * @param timeInTicks The time in ticks (0 to Duration).
     */
    public void setTime(double timeInTicks) {
        if (currentAnimation != null) {
            this.currentTime = timeInTicks;

            // Handle wrapping manually here to ensure state consistency immediately
            if (shouldLoop) {
                this.currentTime %= currentAnimation.getDuration();
                if (this.currentTime < 0) this.currentTime += currentAnimation.getDuration();
            } else {
                if (this.currentTime > currentAnimation.getDuration()) this.currentTime = currentAnimation.getDuration();
                else if (this.currentTime < 0) this.currentTime = 0;
            }

            // Force an immediate update of the matrices with delta 0
            // This ensures visual feedback even if the game loop is paused
            update(0);
        }
    }

    /**
     * Gets the current playback time in ticks.
     * @return The time in ticks.
     */
    public double getCurrentTime() {
        return currentTime;
    }

    /**
     * Gets the currently playing animation.
     * @return The active animation, or null.
     */
    public VxAnimation getCurrentAnimation() {
        return currentAnimation;
    }

    // --- Interpolation Logic ---

    private void interpolatePosition(List<VxAnimation.Key<Vector3f>> keys, double time, Vector3f result) {
        if (keys.size() == 1) {
            result.set(keys.get(0).value());
            return;
        }
        int idx = findKeyIndex(keys, time);
        VxAnimation.Key<Vector3f> k0 = keys.get(idx);
        VxAnimation.Key<Vector3f> k1 = keys.get(idx + 1);
        float scale = getScaleFactor(k0.time(), k1.time(), time);
        k0.value().lerp(k1.value(), scale, result); // Linear Interpolation
    }

    private void interpolateRotation(List<VxAnimation.Key<Quaternionf>> keys, double time, Quaternionf result) {
        if (keys.size() == 1) {
            result.set(keys.get(0).value());
            return;
        }
        int idx = findKeyIndex(keys, time);
        VxAnimation.Key<Quaternionf> k0 = keys.get(idx);
        VxAnimation.Key<Quaternionf> k1 = keys.get(idx + 1);
        float scale = getScaleFactor(k0.time(), k1.time(), time);
        k0.value().slerp(k1.value(), scale, result); // Spherical Linear Interpolation
    }

    private void interpolateScaling(List<VxAnimation.Key<Vector3f>> keys, double time, Vector3f result) {
        if (keys.size() == 1) {
            result.set(keys.get(0).value());
            return;
        }
        int idx = findKeyIndex(keys, time);
        VxAnimation.Key<Vector3f> k0 = keys.get(idx);
        VxAnimation.Key<Vector3f> k1 = keys.get(idx + 1);
        float scale = getScaleFactor(k0.time(), k1.time(), time);
        k0.value().lerp(k1.value(), scale, result); // Linear Interpolation
    }

    /**
     * Calculates the normalized interpolation factor (0.0 to 1.0) between two keyframe times.
     */
    private float getScaleFactor(double last, double next, double now) {
        float midWay = (float) (now - last);
        float diff = (float) (next - last);
        return (diff != 0) ? midWay / diff : 0f;
    }

    /**
     * Finds the index of the keyframe immediately preceding the current time.
     */
    private <T> int findKeyIndex(List<VxAnimation.Key<T>> keys, double time) {
        for (int i = 0; i < keys.size() - 1; i++) {
            if (time < keys.get(i + 1).time()) {
                return i;
            }
        }
        return 0;
    }
}