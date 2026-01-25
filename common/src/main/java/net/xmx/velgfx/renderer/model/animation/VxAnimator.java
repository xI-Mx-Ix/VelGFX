/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.animation;

import net.xmx.velgfx.renderer.model.morph.VxMorphController;
import net.xmx.velgfx.renderer.model.skeleton.VxSkeleton;
import net.xmx.velgfx.renderer.util.VxTempCache;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Manages the playback and interpolation of array-based animations.
 * <p>
 * The Animator acts as the engine that drives the {@link VxSkeleton}. It tracks the current time,
 * handles cross-fading (blending) between animations, and samples data from the {@link VxAnimation}
 * arrays to update the skeleton's local transform state.
 *
 * @author xI-Mx-Ix
 */
public class VxAnimator {

    private final VxSkeleton skeleton;
    private final VxMorphController morphController;

    // --- Animation State ---
    private VxAnimation currentAnimation;
    private double currentTime;

    // --- Blending State ---
    private VxAnimation nextAnimation;
    private double nextAnimationTime;
    private boolean isBlending = false;
    private float blendFactor = 0.0f;
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

    /**
     * Constructs a new Animator.
     *
     * @param skeleton        The skeleton to drive.
     * @param morphController The morph controller (optional, can be null).
     */
    public VxAnimator(VxSkeleton skeleton, VxMorphController morphController) {
        this.skeleton = skeleton;
        this.morphController = morphController;
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
     * Starts playing the specified animation immediately.
     *
     * @param animation The animation to play.
     */
    public void playAnimation(VxAnimation animation) {
        playAnimation(animation, 0.0f);
    }

    /**
     * Advances the animation logic by the given delta time.
     * <p>
     * This updates the time cursors, calculates interpolation, writes the new local transforms
     * to the skeleton, and triggers the matrix update.
     *
     * @param dtSeconds Delta time in seconds.
     */
    public void update(float dtSeconds) {
        if (isPaused) dtSeconds = 0;

        // 1. Handle Transition Logic
        if (isBlending) {
            blendFactor += dtSeconds / blendDuration;
            if (blendFactor >= 1.0f) {
                // Transition complete
                currentAnimation = nextAnimation;
                currentTime = nextAnimationTime;
                nextAnimation = null;
                isBlending = false;
                blendFactor = 0.0f;
            }
        }

        // 2. Advance Time Cursors
        if (currentAnimation != null) {
            currentTime = advanceTime(currentAnimation, currentTime, dtSeconds);
        }
        if (isBlending && nextAnimation != null) {
            nextAnimationTime = advanceTime(nextAnimation, nextAnimationTime, dtSeconds);
        }

        // 3. Sample Animations and Write to Skeleton
        if (currentAnimation != null) {
            applyAnimationToSkeleton();
        } else {
            // If no animation is playing, reset the skeleton to its resting Bind Pose
            resetToBindPose();
        }

        // 4. Update Global Matrices (Hierarchy Calculation)
        // This propagates the local changes we just made down the bone hierarchy.
        skeleton.updateMatrices();
    }

    /**
     * Resets the skeleton to its bind pose.
     */
    private void resetToBindPose() {
        skeleton.resetToBindPose();
    }

    /**
     * Advances a time cursor, handling looping and bounds clamping.
     */
    private double advanceTime(VxAnimation anim, double timeCursor, float dt) {
        if (dt == 0) return timeCursor;
        double ticks = anim.getTicksPerSecond() * dt * playbackSpeed;
        timeCursor += ticks;

        if (shouldLoop) {
            timeCursor %= anim.getDuration();
            if (timeCursor < 0) timeCursor += anim.getDuration();
        } else {
            if (timeCursor > anim.getDuration()) timeCursor = anim.getDuration();
            else if (timeCursor < 0) timeCursor = 0;
        }
        return timeCursor;
    }

    /**
     * Iterates over all bones, samples the animation channels, and updates the skeleton.
     */
    private void applyAnimationToSkeleton() {
        int boneCount = skeleton.boneCount;
        VxTempCache cache = VxTempCache.get();

        // Get scratch objects
        Vector3f vA = cache.vec3_1;
        Vector3f vB = cache.vec3_2;
        Quaternionf qA = cache.quat_1;
        Quaternionf qB = cache.quat_2;

        for (int i = 0; i < boneCount; i++) {
            VxAnimation.NodeChannel chA = currentAnimation.getChannel(i);
            VxAnimation.NodeChannel chB = isBlending && nextAnimation != null ? nextAnimation.getChannel(i) : null;

            // --- Position ---
            skeleton.getLocalTranslation(i, vA); // Initialize with current state (or bind pose)

            if (chA != null && chA.posTimes.length > 0) {
                sampleVec3(chA.posTimes, chA.posValues, currentTime, vA, vB); // Pass vB as scratch
            }
            if (isBlending && chB != null && chB.posTimes.length > 0) {
                sampleVec3(chB.posTimes, chB.posValues, nextAnimationTime, vB, cache.vec3_3); // Need 3rd vector for scratch inside sample
                vA.lerp(vB, blendFactor); // Linear blend between Animation A and B
            }
            skeleton.setLocalTranslation(i, vA.x, vA.y, vA.z);

            // --- Rotation ---
            skeleton.getLocalRotation(i, qA);

            if (chA != null && chA.rotTimes.length > 0) {
                sampleQuat(chA.rotTimes, chA.rotValues, currentTime, qA, qB);
            }
            if (isBlending && chB != null && chB.rotTimes.length > 0) {
                sampleQuat(chB.rotTimes, chB.rotValues, nextAnimationTime, qB, cache.quat_2); // Reuse quat_2 carefully
                qA.slerp(qB, blendFactor); // Spherical blend between Animation A and B
            }
            skeleton.setLocalRotation(i, qA.x, qA.y, qA.z, qA.w);

            // --- Scale ---
            skeleton.getLocalScale(i, vA);

            if (chA != null && chA.scaleTimes.length > 0) {
                sampleVec3(chA.scaleTimes, chA.scaleValues, currentTime, vA, vB);
            }
            if (isBlending && chB != null && chB.scaleTimes.length > 0) {
                sampleVec3(chB.scaleTimes, chB.scaleValues, nextAnimationTime, vB, cache.vec3_3);
                vA.lerp(vB, blendFactor);
            }
            skeleton.setLocalScale(i, vA.x, vA.y, vA.z);

            // --- Morph Weights ---
            if (morphController != null) {
                applyMorphs(chA, chB);
            }
        }
    }

    /**
     * Handles sampling and blending of morph target weights.
     */
    private void applyMorphs(VxAnimation.NodeChannel chA, VxAnimation.NodeChannel chB) {
        // Typically, morph weights are stored on the node containing the mesh.
        // We check if the current channel has weight data.
        if (chA != null && chA.weightTimes.length > 0) {
            float[] wA = sampleWeights(chA.weightTimes, chA.weightValues, currentTime);

            if (isBlending && chB != null && chB.weightTimes.length > 0) {
                float[] wB = sampleWeights(chB.weightTimes, chB.weightValues, nextAnimationTime);

                // Blend individual weights
                int len = Math.min(wA.length, wB.length);
                for (int k = 0; k < len; k++) {
                    float blended = wA[k] + (wB[k] - wA[k]) * blendFactor;
                    morphController.setWeightByIndex(k, blended);
                }
            } else {
                // Direct application
                for (int k = 0; k < wA.length; k++) {
                    morphController.setWeightByIndex(k, wA[k]);
                }
            }
        }
    }

    // ============================================================================================
    // Sampling Logic (Linear Interpolation on Arrays)
    // ============================================================================================

    /**
     * Samples a Vec3 value from keyframes at a specific time.
     * <p>
     * Performs linear interpolation between the two surrounding keyframes.
     *
     * @param times  Array of timestamps.
     * @param values Flat array of values (Stride 3).
     * @param time   Current time.
     * @param result Vector to store the result.
     */
    private void sampleVec3(float[] times, float[] values, double time, Vector3f result, Vector3f scratch) {
        if (times.length == 1) {
            result.set(values[0], values[1], values[2]);
            return;
        }

        int idx = findKeyIndex(times, (float) time);
        int next = idx + 1;
        float t = getScaleFactor(times[idx], times[next], (float) time);

        int i1 = idx * 3;
        int i2 = next * 3;

        result.set(values[i1], values[i1 + 1], values[i1 + 2]);
        scratch.set(values[i2], values[i2 + 1], values[i2 + 2]);
        result.lerp(scratch, t);
    }

    /**
     * Samples a Quaternion value from keyframes at a specific time.
     * <p>
     * Performs spherical linear interpolation (SLERP) between the two surrounding keyframes.
     *
     * @param times  Array of timestamps.
     * @param values Flat array of values (Stride 4).
     * @param time   Current time.
     * @param result Quaternion to store the result.
     */
    private void sampleQuat(float[] times, float[] values, double time, Quaternionf result, Quaternionf scratch) {
        if (times.length == 1) {
            result.set(values[0], values[1], values[2], values[3]);
            return;
        }

        int idx = findKeyIndex(times, (float) time);
        int next = idx + 1;
        float t = getScaleFactor(times[idx], times[next], (float) time);

        int i1 = idx * 4;
        int i2 = next * 4;

        result.set(values[i1], values[i1 + 1], values[i1 + 2], values[i1 + 3]);
        scratch.set(values[i2], values[i2 + 1], values[i2 + 2], values[i2 + 3]);
        result.slerp(scratch, t);
    }

    /**
     * Samples an array of weights from keyframes at a specific time.
     * <p>
     * Returns a new float array (allocation involved). Ideally, this should be optimized further
     * to write into a pre-allocated buffer if morph counts are high.
     *
     * @param times  Array of timestamps.
     * @param values Flat array of weights.
     * @param time   Current time.
     * @return Interpolated weights array.
     */
    private float[] sampleWeights(float[] times, float[] values, double time) {
        int frames = times.length;
        int stride = values.length / frames;
        float[] res = new float[stride];

        if (frames == 1) {
            System.arraycopy(values, 0, res, 0, stride);
            return res;
        }

        int idx = findKeyIndex(times, (float) time);
        int next = idx + 1;
        float t = getScaleFactor(times[idx], times[next], (float) time);

        int i1 = idx * stride;
        int i2 = next * stride;

        for (int k = 0; k < stride; k++) {
            float v1 = values[i1 + k];
            float v2 = values[i2 + k];
            res[k] = v1 + (v2 - v1) * t;
        }
        return res;
    }

    /**
     * Finds the index of the keyframe strictly before the current time.
     * <p>
     * Currently performs a linear scan. Could be optimized to Binary Search for very long animations.
     */
    private int findKeyIndex(float[] times, float time) {
        for (int i = 0; i < times.length - 1; i++) {
            if (time < times[i + 1]) return i;
        }
        return times.length - 2; // Return second-to-last so that next is last
    }

    /**
     * Calculates the interpolation factor (0.0 to 1.0) between two timestamps.
     */
    private float getScaleFactor(float last, float next, float now) {
        float diff = next - last;
        return (diff != 0) ? (now - last) / diff : 0f;
    }

    // --- Setters ---

    public void setSpeed(float speed) {
        this.playbackSpeed = speed;
    }

    public void setPaused(boolean paused) {
        this.isPaused = paused;
    }

    public void setLooping(boolean loop) {
        this.shouldLoop = loop;
    }
}