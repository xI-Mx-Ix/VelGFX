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
 * pausing, manual time scrubbing, and smooth transitions between animations.
 *
 * @author xI-Mx-Ix
 */
public class VxAnimator {

    private final VxNode rootNode;

    // --- Animation State ---
    private VxAnimation currentAnimation;
    private double currentTime;

    // --- Blending State ---
    private VxAnimation nextAnimation;
    private double nextAnimationTime;
    private boolean isBlending = false;
    private float blendFactor = 0.0f; // Range: 0.0 to 1.0
    private float blendDuration = 0.0f;

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

    // Matrices for hierarchy calculation
    private final Matrix4f parentTransformScratch = new Matrix4f();
    private final Matrix4f localTransformScratch = new Matrix4f();

    // Interpolation buffers for Set A (Current Animation)
    private final Vector3f interpPosA = new Vector3f();
    private final Quaternionf interpRotA = new Quaternionf();
    private final Vector3f interpScaleA = new Vector3f();

    // Interpolation buffers for Set B (Next Animation / Target)
    private final Vector3f interpPosB = new Vector3f();
    private final Quaternionf interpRotB = new Quaternionf();
    private final Vector3f interpScaleB = new Vector3f();

    /**
     * Constructs an animator for the given node hierarchy.
     *
     * @param rootNode The root node of the hierarchy to animate.
     */
    public VxAnimator(VxNode rootNode) {
        this.rootNode = rootNode;
    }

    /**
     * Starts playing the specified animation clip immediately.
     * <p>
     * This resets the time cursor to 0.0 immediately and cancels any active blending.
     *
     * @param animation The animation to play.
     */
    public void playAnimation(VxAnimation animation) {
        this.playAnimation(animation, 0.0f);
    }

    /**
     * Starts playing the specified animation with a smooth cross-fade transition.
     *
     * @param animation      The target animation to blend into.
     * @param transitionTime The duration of the transition in seconds.
     */
    public void playAnimation(VxAnimation animation, float transitionTime) {
        if (currentAnimation == null || transitionTime <= 0.0f) {
            // No current animation or immediate switch requested
            this.currentAnimation = animation;
            this.currentTime = 0;
            this.isBlending = false;
            this.nextAnimation = null;
        } else {
            // Initiate blending
            this.nextAnimation = animation;
            this.nextAnimationTime = 0;
            this.blendDuration = transitionTime;
            this.blendFactor = 0.0f;
            this.isBlending = true;
        }
    }

    /**
     * Advances the animation state or enforces the bind pose.
     * <p>
     * This method handles the temporal update of the node hierarchy:
     * <ul>
     *     <li>If an animation is active, it increments the time cursor based on {@code dtSeconds}
     *     and the {@link #playbackSpeed}.</li>
     *     <li>If blending is active, it advances both animation cursors and updates the blend factor.</li>
     *     <li>It then calculates the interpolated transforms (Position, Rotation, Scale) for every node
     *     and updates the scene graph.</li>
     *     <li>If <b>no</b> animation is active (or the animator is reset), it triggers a
     *     hierarchical update using the static local transforms (Bind Pose) of the nodes.
     *     This step is critical for skinned meshes to prevent geometry collapse.</li>
     * </ul>
     *
     * @param dtSeconds The time elapsed since the last frame in <b>seconds</b>.
     */
    public void update(float dtSeconds) {
        if (isPaused) {
            dtSeconds = 0;
        }

        // 1. Handle Transition Logic
        if (isBlending) {
            blendFactor += dtSeconds / blendDuration;
            if (blendFactor >= 1.0f) {
                // Transition complete: Swap next to current
                currentAnimation = nextAnimation;
                currentTime = nextAnimationTime;

                nextAnimation = null;
                isBlending = false;
                blendFactor = 0.0f;
            }
        }

        // 2. Advance Time for Current Animation
        if (currentAnimation != null) {
            currentTime = advanceTime(currentAnimation, currentTime, dtSeconds);
        }

        // 3. Advance Time for Next Animation (if blending)
        if (isBlending && nextAnimation != null) {
            nextAnimationTime = advanceTime(nextAnimation, nextAnimationTime, dtSeconds);
        }

        // 4. Calculate Transforms
        if (currentAnimation != null) {
            parentTransformScratch.identity();
            calculateBoneTransform(rootNode, parentTransformScratch);
        } else {
            // Fallback: Update hierarchy using static Bind Pose
            // Since vertices are stored in local space, global transforms must be calculated
            // at least once to apply the bone offset matrices correctly.
            rootNode.updateHierarchy(null);
        }
    }

    /**
     * Helper to advance the time cursor for a specific animation, handling looping and speed.
     *
     * @param anim       The animation definition.
     * @param timeCursor The current time in ticks.
     * @param dt         Delta time in seconds.
     * @return The new time in ticks.
     */
    private double advanceTime(VxAnimation anim, double timeCursor, float dt) {
        if (dt == 0) return timeCursor;

        double ticksToAdvance = anim.getTicksPerSecond() * dt * playbackSpeed;
        timeCursor += ticksToAdvance;

        // Handle Looping logic
        if (shouldLoop) {
            timeCursor %= anim.getDuration();
            // Handle negative speed wrapping
            if (timeCursor < 0) {
                timeCursor += anim.getDuration();
            }
        } else {
            // Clamp to start/end if not looping
            if (timeCursor > anim.getDuration()) {
                timeCursor = anim.getDuration();
            } else if (timeCursor < 0) {
                timeCursor = 0;
            }
        }
        return timeCursor;
    }

    /**
     * Recursively calculates transforms for the node tree based on the animation data.
     * <p>
     * If blending is active, it samples both the current and next animations and
     * linearly interpolates (or spherically interpolates for rotation) the results.
     *
     * @param node            The current node being processed.
     * @param parentTransform The global transformation matrix of the parent node.
     */
    private void calculateBoneTransform(VxNode node, Matrix4f parentTransform) {
        String nodeName = node.getName();

        // 1. Retrieve the default Bind Pose (Local Transform)
        // We use this as a base or fallback if animation channels are missing.
        Matrix4f bindPose = node.getLocalTransform();

        // --- Sample Animation A (Current) ---
        VxAnimation.NodeChannel channelA = currentAnimation.getChannel(nodeName);
        if (channelA != null) {
            interpolatePosition(channelA.positions, currentTime, interpPosA);
            interpolateRotation(channelA.rotations, currentTime, interpRotA);
            interpolateScaling(channelA.scalings, currentTime, interpScaleA);
        } else {
            // Fallback to bind pose components if channel is missing
            bindPose.getTranslation(interpPosA);
            bindPose.getUnnormalizedRotation(interpRotA);
            bindPose.getScale(interpScaleA);
        }

        // --- Sample Animation B (Next) and Blend ---
        if (isBlending && nextAnimation != null) {
            VxAnimation.NodeChannel channelB = nextAnimation.getChannel(nodeName);
            if (channelB != null) {
                interpolatePosition(channelB.positions, nextAnimationTime, interpPosB);
                interpolateRotation(channelB.rotations, nextAnimationTime, interpRotB);
                interpolateScaling(channelB.scalings, nextAnimationTime, interpScaleB);
            } else {
                bindPose.getTranslation(interpPosB);
                bindPose.getUnnormalizedRotation(interpRotB);
                bindPose.getScale(interpScaleB);
            }

            // Perform blending (A -> B)
            interpPosA.lerp(interpPosB, blendFactor);
            interpRotA.slerp(interpRotB, blendFactor);
            interpScaleA.lerp(interpScaleB, blendFactor);
        }

        // --- Compose Local Matrix ---
        localTransformScratch.translation(interpPosA)
                .rotate(interpRotA)
                .scale(interpScaleA);

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
                if (this.currentTime > currentAnimation.getDuration())
                    this.currentTime = currentAnimation.getDuration();
                else if (this.currentTime < 0) this.currentTime = 0;
            }

            // Force an immediate update of the matrices with delta 0
            // This ensures visual feedback even if the game loop is paused
            update(0);
        }
    }

    /**
     * Gets the current playback time in ticks.
     *
     * @return The time in ticks.
     */
    public double getCurrentTime() {
        return currentTime;
    }

    /**
     * Gets the currently playing animation.
     *
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