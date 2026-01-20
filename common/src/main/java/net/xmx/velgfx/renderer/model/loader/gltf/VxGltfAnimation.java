/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.loader.gltf;

import de.javagl.jgltf.model.AnimationModel;
import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.NodeModel;
import net.xmx.velgfx.renderer.model.animation.VxAnimation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles the extraction of Animation Clips and Keyframes from glTF models.
 * <p>
 * This class iterates through all animations defined in the glTF file, reads the
 * Samplers (Input Time and Output Values) using the {@link VxGltfAccessorUtil}, and
 * converts them into the engine's internal keyframe format.
 *
 * @author xI-Mx-Ix
 */
public class VxGltfAnimation {

    /**
     * Parses all animations present in the glTF model.
     *
     * @param model The source glTF model.
     * @return A map of animation names to animation objects.
     */
    public static Map<String, VxAnimation> parseAnimations(GltfModel model) {
        Map<String, VxAnimation> animations = new HashMap<>();

        for (AnimationModel animModel : model.getAnimationModels()) {
            String name = animModel.getName() != null ? animModel.getName() : "Anim_" + animations.size();
            double maxTime = 0;
            Map<String, VxAnimation.NodeChannel> channels = new HashMap<>();

            for (AnimationModel.Channel channel : animModel.getChannels()) {
                NodeModel node = channel.getNodeModel();
                if (node == null) continue;
                String nodeName = node.getName();

                VxAnimation.NodeChannel vxChannel = channels.computeIfAbsent(nodeName, k ->
                        new VxAnimation.NodeChannel(new ArrayList<>(), new ArrayList<>(), new ArrayList<>())
                );

                AnimationModel.Sampler sampler = channel.getSampler();

                // Use the Accessor Utility to read floats directly
                float[] inputs = VxGltfAccessorUtil.readAccessorAsFloats(sampler.getInput());
                float[] outputs = VxGltfAccessorUtil.readAccessorAsFloats(sampler.getOutput());

                // Track the maximum duration of the animation
                if (inputs.length > 0 && inputs[inputs.length - 1] > maxTime) {
                    maxTime = inputs[inputs.length - 1];
                }

                String path = channel.getPath();
                for (int i = 0; i < inputs.length; i++) {
                    double time = inputs[i];

                    if ("translation".equals(path)) {
                        vxChannel.positions.add(new VxAnimation.Key<>(time,
                                new Vector3f(outputs[i * 3], outputs[i * 3 + 1], outputs[i * 3 + 2])));
                    } else if ("scale".equals(path)) {
                        vxChannel.scalings.add(new VxAnimation.Key<>(time,
                                new Vector3f(outputs[i * 3], outputs[i * 3 + 1], outputs[i * 3 + 2])));
                    } else if ("rotation".equals(path)) {
                        vxChannel.rotations.add(new VxAnimation.Key<>(time,
                                new Quaternionf(outputs[i * 4], outputs[i * 4 + 1], outputs[i * 4 + 2], outputs[i * 4 + 3])));
                    }
                }
            }

            // Create animation with Ticks Per Second set to 1.0 (since glTF uses Seconds)
            // This ensures that 'time' in keyframes is interpreted directly as seconds by the Animator.
            animations.put(name, new VxAnimation(name, maxTime, 1.0, channels));
        }
        return animations;
    }
}