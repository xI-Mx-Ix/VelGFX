/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.loader;

import net.xmx.velgfx.renderer.model.animation.VxAnimation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.assimp.*;

import java.util.*;

/**
 * Handles the extraction of Animation clips from Assimp.
 *
 * @author xI-Mx-Ix
 */
public class VxAssimpAnimation {

    /**
     * Parses all animations present in the scene.
     *
     * @param scene The imported scene.
     * @return A map of animation names to animation objects.
     */
    public static Map<String, VxAnimation> parseAnimations(AIScene scene) {
        if (scene.mNumAnimations() == 0) return Collections.emptyMap();
        Map<String, VxAnimation> animations = new HashMap<>();
        for (int i = 0; i < scene.mNumAnimations(); i++) {
            AIAnimation aiAnim = AIAnimation.create(scene.mAnimations().get(i));
            VxAnimation anim = processSingleAnimation(aiAnim);
            animations.put(anim.getName(), anim);
        }
        return animations;
    }

    private static VxAnimation processSingleAnimation(AIAnimation aiAnim) {
        String name = aiAnim.mName().dataString();
        double duration = aiAnim.mDuration();
        double tps = aiAnim.mTicksPerSecond() != 0 ? aiAnim.mTicksPerSecond() : 25.0;

        Map<String, VxAnimation.NodeChannel> channels = new HashMap<>();

        for (int c = 0; c < aiAnim.mNumChannels(); c++) {
            AINodeAnim ch = AINodeAnim.create(aiAnim.mChannels().get(c));

            List<VxAnimation.Key<Vector3f>> pos = new ArrayList<>();
            for (int k = 0; k < ch.mNumPositionKeys(); k++) {
                AIVectorKey key = ch.mPositionKeys().get(k);
                pos.add(new VxAnimation.Key<>(key.mTime(), new Vector3f(key.mValue().x(), key.mValue().y(), key.mValue().z())));
            }

            List<VxAnimation.Key<Quaternionf>> rot = new ArrayList<>();
            for (int k = 0; k < ch.mNumRotationKeys(); k++) {
                AIQuatKey key = ch.mRotationKeys().get(k);
                rot.add(new VxAnimation.Key<>(key.mTime(), new Quaternionf(key.mValue().x(), key.mValue().y(), key.mValue().z(), key.mValue().w())));
            }

            List<VxAnimation.Key<Vector3f>> scl = new ArrayList<>();
            for (int k = 0; k < ch.mNumScalingKeys(); k++) {
                AIVectorKey key = ch.mScalingKeys().get(k);
                scl.add(new VxAnimation.Key<>(key.mTime(), new Vector3f(key.mValue().x(), key.mValue().y(), key.mValue().z())));
            }

            channels.put(ch.mNodeName().dataString(), new VxAnimation.NodeChannel(pos, rot, scl));
        }
        return new VxAnimation(name, duration, tps, channels);
    }
}