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
 * Manages the playback and interpolation of animations for a model hierarchy.
 * <p>
 * The Animator tracks the current time, interpolates between keyframes of the active {@link VxAnimation},
 * and updates the local and global transforms of the {@link VxNode} hierarchy.
 * <p>
 * This class operates directly on {@link VxNode} and is compatible with both skeletal and rigid-body animations.
 *
 * @author xI-Mx-Ix
 */
public class VxAnimator {

    private final VxNode rootNode;
    private VxAnimation currentAnimation;
    private double currentTime;

    // Scratch objects to avoid allocation during the update loop
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
     * Starts playing an animation clip. Resets time to 0.
     *
     * @param animation The animation to play.
     */
    public void playAnimation(VxAnimation animation) {
        this.currentAnimation = animation;
        this.currentTime = 0;
    }

    /**
     * Advances the animation state.
     *
     * @param dt The time delta in seconds since the last frame.
     */
    public void update(float dt) {
        if (currentAnimation == null) return;

        // Advance time
        currentTime += currentAnimation.getTicksPerSecond() * dt;
        currentTime %= currentAnimation.getDuration(); // Loop

        // Begin recursive update from root with Identity matrix as parent
        parentTransformScratch.identity();
        calculateBoneTransform(rootNode, parentTransformScratch);
    }

    /**
     * Recursively calculates transforms for the node tree.
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
        Matrix4f globalTransform = node.getGlobalTransform(); // Direct access to mutable matrix
        globalTransform.set(parentTransform).mul(localTransformScratch);

        // Propagate to children
        for (VxNode child : node.getChildren()) {
            calculateBoneTransform(child, globalTransform);
        }
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
        k0.value().lerp(k1.value(), scale, result); // Linear
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
        k0.value().slerp(k1.value(), scale, result); // Spherical Linear
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
        k0.value().lerp(k1.value(), scale, result); // Linear
    }

    private float getScaleFactor(double last, double next, double now) {
        float midWay = (float) (now - last);
        float diff = (float) (next - last);
        return (diff != 0) ? midWay / diff : 0f;
    }

    private <T> int findKeyIndex(List<VxAnimation.Key<T>> keys, double time) {
        for (int i = 0; i < keys.size() - 1; i++) {
            if (time < keys.get(i + 1).time()) return i;
        }
        return 0;
    }
}