/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.animation;

import net.xmx.velgfx.renderer.model.morph.VxMorphController;
import net.xmx.velgfx.renderer.model.skeleton.VxNode;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * Manages the playback, interpolation, and state of animations for a model hierarchy.
 * <p>
 * The Animator acts as the central engine for skeletal and morph target animation. It tracks the current time,
 * interpolates between keyframes of the active {@link VxAnimation}, and updates:
 * <ul>
 *     <li><b>Skeletal:</b> The local and global transforms of the {@link VxNode} hierarchy.</li>
 *     <li><b>Morph:</b> The weights of the {@link VxMorphController}, including blending between animations.</li>
 * </ul>
 * <p>
 * This implementation supports smooth cross-fading (blending) between animations,
 * including complex blending of array-based morph weights.
 *
 * @author xI-Mx-Ix
 */
public class VxAnimator {

    private final VxNode rootNode;
    private final VxMorphController morphController; // Can be null if model is static or has no morphs

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
    // These objects are reused every frame to prevent Garbage Collection pressure.
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
     * @param rootNode        The root node of the hierarchy to animate.
     * @param morphController The morph controller to animate (optional, can be null).
     */
    public VxAnimator(VxNode rootNode, VxMorphController morphController) {
        this.rootNode = rootNode;
        this.morphController = morphController;
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
     * Advances the animation state.
     * <p>
     * This method handles the temporal update of the node hierarchy:
     * <ul>
     *     <li>Updates time cursors for current (and next) animations.</li>
     *     <li>Handles looping logic and playback speed.</li>
     *     <li>Interpolates TRS (Translate, Rotate, Scale) channels.</li>
     *     <li>Interpolates Morph Weight channels.</li>
     *     <li>Blends results if a cross-fade is active.</li>
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

        // 4. Calculate Transforms & Apply Morphs
        if (currentAnimation != null) {
            parentTransformScratch.identity();
            processNode(rootNode, parentTransformScratch);
        } else {
            // Fallback: Update hierarchy using static Bind Pose
            rootNode.updateHierarchy(null);

            // Optional: Reset morphs to default/zero if no animation is playing
            if (morphController != null) {
                // We could iterate active morphs and set to 0,
                // but keeping last state is often desired behavior.
            }
        }
    }

    /**
     * Helper to advance the time cursor for a specific animation.
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
     * Recursively processes the node tree, calculating transforms and applying morphs.
     * <p>
     * This ensures that the initial transform state is derived from the node's static Bind Pose,
     * which is then selectively overridden only by properties actually present in the animation.
     *
     * @param node            The current node being processed.
     * @param parentTransform The calculated global transform of the parent node.
     */
    private void processNode(VxNode node, Matrix4f parentTransform) {
        String nodeName = node.getName();
        Matrix4f bindPose = node.getLocalTransform();

        // 1. Initialize interpolation targets with the Node's static Bind Pose.
        // This preserves static properties (e.g., scale) if the animation track does not modify them.
        bindPose.getTranslation(interpPosA);
        bindPose.getUnnormalizedRotation(interpRotA);
        bindPose.getScale(interpScaleA);

        // --- Animation A (Current) ---
        VxAnimation.NodeChannel channelA = currentAnimation.getChannel(nodeName);
        if (channelA != null) {
            // Only interpolate if keyframes exist for the specific property
            if (!channelA.positions.isEmpty()) {
                interpolatePosition(channelA.positions, currentTime, interpPosA);
            }
            if (!channelA.rotations.isEmpty()) {
                interpolateRotation(channelA.rotations, currentTime, interpRotA);
            }
            if (!channelA.scalings.isEmpty()) {
                interpolateScaling(channelA.scalings, currentTime, interpScaleA);
            }

            // Apply morphs directly if not blending
            if (!isBlending) {
                applyMorphsDirectly(channelA, currentTime);
            }
        }

        // --- Animation B (Blending Target) ---
        if (isBlending && nextAnimation != null) {
            // Initialize targets for Animation B with Bind Pose as well
            bindPose.getTranslation(interpPosB);
            bindPose.getUnnormalizedRotation(interpRotB);
            bindPose.getScale(interpScaleB);

            VxAnimation.NodeChannel channelB = nextAnimation.getChannel(nodeName);
            if (channelB != null) {
                if (!channelB.positions.isEmpty()) {
                    interpolatePosition(channelB.positions, nextAnimationTime, interpPosB);
                }
                if (!channelB.rotations.isEmpty()) {
                    interpolateRotation(channelB.rotations, nextAnimationTime, interpRotB);
                }
                if (!channelB.scalings.isEmpty()) {
                    interpolateScaling(channelB.scalings, nextAnimationTime, interpScaleB);
                }
            }

            // Linear/Spherical Interpolation for TRS
            interpPosA.lerp(interpPosB, blendFactor);
            interpRotA.slerp(interpRotB, blendFactor);
            interpScaleA.lerp(interpScaleB, blendFactor);

            // Special blending logic for Morph Weights
            blendMorphs(channelA, channelB);
        }

        // --- Compose Matrix ---
        localTransformScratch.translation(interpPosA)
                .rotate(interpRotA)
                .scale(interpScaleA);

        // Calculate Global Transform: ParentGlobal * Local
        Matrix4f globalTransform = node.getGlobalTransform();
        globalTransform.set(parentTransform).mul(localTransformScratch);

        // Propagate to children
        for (VxNode child : node.getChildren()) {
            processNode(child, globalTransform);
        }
    }

    /**
     * Applies morph weights directly from a channel without blending.
     * <p>
     * This is an optimized path when {@code isBlending} is false.
     *
     * @param channel The animation channel containing weight keyframes.
     * @param time    The current time in ticks.
     */
    private void applyMorphsDirectly(VxAnimation.NodeChannel channel, double time) {
        if (channel.weights == null || channel.weights.isEmpty() || morphController == null) return;

        float[] weights = interpolateWeights(channel.weights, time);
        if (weights != null) {
            for (int i = 0; i < weights.length; i++) {
                morphController.setWeightByIndex(i, weights[i]);
            }
        }
    }

    /**
     * Blends morph weights between two animation channels.
     * <p>
     * Handles cases where one channel might be missing weights (e.g., blending from a pose
     * with no facial animation to one with facial animation).
     *
     * @param channelA The channel from the current animation (can be null).
     * @param channelB The channel from the next animation (can be null).
     */
    private void blendMorphs(VxAnimation.NodeChannel channelA, VxAnimation.NodeChannel channelB) {
        if (morphController == null) return;

        // Sample both animations at their respective times
        float[] wA = (channelA != null && channelA.weights != null && !channelA.weights.isEmpty())
                ? interpolateWeights(channelA.weights, currentTime) : null;

        float[] wB = (channelB != null && channelB.weights != null && !channelB.weights.isEmpty())
                ? interpolateWeights(channelB.weights, nextAnimationTime) : null;

        // Determine array length (usually they match, but we must handle mismatch safely)
        int length = 0;
        if (wA != null) length = Math.max(length, wA.length);
        if (wB != null) length = Math.max(length, wB.length);

        if (length == 0) return;

        // Linear interpolation of every weight index
        for (int i = 0; i < length; i++) {
            float valA = (wA != null && i < wA.length) ? wA[i] : 0f;
            float valB = (wB != null && i < wB.length) ? wB[i] : 0f;

            // Lerp: A + (B - A) * t
            float blended = valA + (valB - valA) * blendFactor;
            morphController.setWeightByIndex(i, blended);
        }
    }

    // --- Primitive Interpolation Helpers ---

    private void interpolatePosition(List<VxAnimation.Key<Vector3f>> keys, double time, Vector3f result) {
        if (keys.isEmpty()) return;
        if (keys.size() == 1) { result.set(keys.get(0).value()); return; }

        int idx = findKeyIndex(keys, time);
        VxAnimation.Key<Vector3f> k0 = keys.get(idx);
        VxAnimation.Key<Vector3f> k1 = keys.get(idx + 1);

        float scale = getScaleFactor(k0.time(), k1.time(), time);
        k0.value().lerp(k1.value(), scale, result);
    }

    private void interpolateRotation(List<VxAnimation.Key<Quaternionf>> keys, double time, Quaternionf result) {
        if (keys.isEmpty()) return;
        if (keys.size() == 1) { result.set(keys.get(0).value()); return; }

        int idx = findKeyIndex(keys, time);
        VxAnimation.Key<Quaternionf> k0 = keys.get(idx);
        VxAnimation.Key<Quaternionf> k1 = keys.get(idx + 1);

        float scale = getScaleFactor(k0.time(), k1.time(), time);
        k0.value().slerp(k1.value(), scale, result);
    }

    private void interpolateScaling(List<VxAnimation.Key<Vector3f>> keys, double time, Vector3f result) {
        if (keys.isEmpty()) return;
        if (keys.size() == 1) { result.set(keys.get(0).value()); return; }

        int idx = findKeyIndex(keys, time);
        VxAnimation.Key<Vector3f> k0 = keys.get(idx);
        VxAnimation.Key<Vector3f> k1 = keys.get(idx + 1);

        float scale = getScaleFactor(k0.time(), k1.time(), time);
        k0.value().lerp(k1.value(), scale, result);
    }

    /**
     * Interpolates arrays of floats linearly.
     * <p>
     * Used for morph weights. Allocates a new float array for the result.
     *
     * @param keys The list of weight keyframes.
     * @param time The current time in ticks.
     * @return A new float array containing the interpolated weights.
     */
    private float[] interpolateWeights(List<VxAnimation.Key<float[]>> keys, double time) {
        if (keys.size() == 1) return keys.get(0).value();

        int idx = findKeyIndex(keys, time);
        VxAnimation.Key<float[]> k0 = keys.get(idx);
        VxAnimation.Key<float[]> k1 = keys.get(idx + 1);

        float t = getScaleFactor(k0.time(), k1.time(), time);

        float[] a = k0.value();
        float[] b = k1.value();

        // Arrays should ideally be same size, but we handle potential mismatch
        int len = Math.min(a.length, b.length);
        float[] res = new float[len];

        for (int i = 0; i < len; i++) {
            res[i] = a[i] + (b[i] - a[i]) * t;
        }
        return res;
    }

    private float getScaleFactor(double last, double next, double now) {
        float diff = (float) (next - last);
        return (diff != 0) ? (float) (now - last) / diff : 0f;
    }

    private <T> int findKeyIndex(List<VxAnimation.Key<T>> keys, double time) {
        for (int i = 0; i < keys.size() - 1; i++) {
            if (time < keys.get(i + 1).time()) return i;
        }
        return 0;
    }

    // --- Accessors ---

    public void setSpeed(float speed) {
        this.playbackSpeed = speed;
    }

    public void setPaused(boolean paused) {
        this.isPaused = paused;
    }

    public void setLooping(boolean loop) {
        this.shouldLoop = loop;
    }

    public void setTime(double timeInTicks) {
        if (currentAnimation != null) {
            this.currentTime = timeInTicks;
            if (shouldLoop) {
                this.currentTime %= currentAnimation.getDuration();
                if (this.currentTime < 0) this.currentTime += currentAnimation.getDuration();
            } else {
                if (this.currentTime > currentAnimation.getDuration()) this.currentTime = currentAnimation.getDuration();
                else if (this.currentTime < 0) this.currentTime = 0;
            }
            update(0);
        }
    }

    public double getCurrentTime() {
        return currentTime;
    }

    public VxAnimation getCurrentAnimation() {
        return currentAnimation;
    }
}