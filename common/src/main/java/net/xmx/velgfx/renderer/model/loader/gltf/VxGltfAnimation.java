/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.loader.gltf;

import de.javagl.jgltf.model.AnimationModel;
import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.NodeModel;
import net.xmx.velgfx.renderer.model.animation.VxAnimation;
import net.xmx.velgfx.renderer.model.skeleton.VxSkeleton;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles the extraction of Animation Clips into the efficient array-based format.
 *
 * @author xI-Mx-Ix
 */
public class VxGltfAnimation {

    /**
     * Parses all animations in the model and converts them to array-based clips.
     *
     * @param model    The glTF model.
     * @param skeleton The skeleton used to map Node Names to Bone Indices.
     * @return A map of animation names to animation clips.
     */
    public static Map<String, VxAnimation> parseAnimations(GltfModel model, VxSkeleton skeleton) {
        Map<String, VxAnimation> animations = new HashMap<>();

        for (AnimationModel animModel : model.getAnimationModels()) {
            String name = animModel.getName() != null ? animModel.getName() : "Anim_" + animations.size();
            double maxTime = 0;

            // Use skeleton.boneCount directly
            VxAnimation.NodeChannel[] channels = new VxAnimation.NodeChannel[skeleton.boneCount];

            // Temporary collection map to accumulate channel data per bone
            Map<Integer, TempChannel> tempChannels = new HashMap<>();

            for (AnimationModel.Channel channel : animModel.getChannels()) {
                NodeModel node = channel.getNodeModel();
                if (node == null) continue;

                // Use skeleton.indexOf() directly
                int boneIndex = skeleton.indexOf(node.getName());
                if (boneIndex == -1) continue;

                TempChannel temp = tempChannels.computeIfAbsent(boneIndex, k -> new TempChannel());
                AnimationModel.Sampler sampler = channel.getSampler();

                // Read raw data using accessor utility
                float[] inputs = VxGltfAccessorUtil.readAccessorAsFloats(sampler.getInput());
                float[] outputs = VxGltfAccessorUtil.readAccessorAsFloats(sampler.getOutput());

                // Update animation duration
                if (inputs.length > 0 && inputs[inputs.length - 1] > maxTime) {
                    maxTime = inputs[inputs.length - 1];
                }

                String path = channel.getPath();
                if ("translation".equals(path)) {
                    temp.posTimes = inputs;
                    temp.posValues = outputs;
                } else if ("scale".equals(path)) {
                    temp.scaleTimes = inputs;
                    temp.scaleValues = outputs;
                } else if ("rotation".equals(path)) {
                    temp.rotTimes = inputs;
                    temp.rotValues = outputs;
                } else if ("weights".equals(path)) {
                    temp.weightTimes = inputs;
                    temp.weightValues = outputs;
                }
            }

            // Convert TempChannels to Final Array Structure for the Animation object
            for (Map.Entry<Integer, TempChannel> entry : tempChannels.entrySet()) {
                TempChannel t = entry.getValue();
                channels[entry.getKey()] = new VxAnimation.NodeChannel(
                        t.posTimes != null ? t.posTimes : new float[0],
                        t.posValues != null ? t.posValues : new float[0],
                        t.rotTimes != null ? t.rotTimes : new float[0],
                        t.rotValues != null ? t.rotValues : new float[0],
                        t.scaleTimes != null ? t.scaleTimes : new float[0],
                        t.scaleValues != null ? t.scaleValues : new float[0],
                        t.weightTimes != null ? t.weightTimes : new float[0],
                        t.weightValues != null ? t.weightValues : new float[0]
                );
            }

            // Store the final clip
            animations.put(name, new VxAnimation(name, maxTime, 1.0, channels));
        }
        return animations;
    }

    // Internal helper for gathering channels during parsing
    private static class TempChannel {
        float[] posTimes, posValues;
        float[] rotTimes, rotValues;
        float[] scaleTimes, scaleValues;
        float[] weightTimes, weightValues;
    }
}