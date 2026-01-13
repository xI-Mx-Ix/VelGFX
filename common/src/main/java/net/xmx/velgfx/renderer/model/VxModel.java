/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model;

import com.mojang.blaze3d.vertex.PoseStack;
import net.xmx.velgfx.renderer.gl.mesh.IVxRenderableMesh;
import net.xmx.velgfx.renderer.model.animation.VxAnimation;
import net.xmx.velgfx.renderer.model.animation.VxAnimator;
import net.xmx.velgfx.renderer.model.skeleton.VxNode;

import java.util.Collections;
import java.util.Map;

/**
 * Represents the abstract base for 3D models in the engine.
 * <p>
 * A model acts as a container for:
 * <ul>
 *     <li><b>Geometry:</b> Handled by {@link IVxRenderableMesh} (VBO/VAO).</li>
 *     <li><b>Hierarchy:</b> A root {@link VxNode} representing the scene graph.</li>
 *     <li><b>Animations:</b> A library of available clips.</li>
 * </ul>
 *
 * @author xI-Mx-Ix
 */
public abstract class VxModel {

    protected final VxNode rootNode;
    protected final IVxRenderableMesh mesh;
    protected final Map<String, VxAnimation> animations;
    protected final VxAnimator animator;

    /**
     * Constructs a base model.
     *
     * @param rootNode   The root of the scene hierarchy.
     * @param mesh       The renderable mesh resource.
     * @param animations The map of animation clips.
     */
    protected VxModel(VxNode rootNode, IVxRenderableMesh mesh, Map<String, VxAnimation> animations) {
        this.rootNode = rootNode;
        this.mesh = mesh;
        this.animations = animations != null ? animations : Collections.emptyMap();
        this.animator = new VxAnimator(rootNode);
    }

    /**
     * Updates the animation state.
     *
     * @param dt Delta time in seconds.
     */
    public void update(float dt) {
        animator.update(dt);
    }

    /**
     * Renders the model into the pipeline.
     *
     * @param poseStack   The current transformation stack.
     * @param packedLight The packed light value.
     */
    public abstract void render(PoseStack poseStack, int packedLight);

    /**
     * Starts playing the specified animation.
     *
     * @param name The name of the animation clip.
     */
    public void playAnimation(String name) {
        VxAnimation anim = animations.get(name);
        if (anim != null) {
            animator.playAnimation(anim);
        }
    }

    /**
     * Creates an independent instance of this model.
     * <p>
     * The new instance shares heavy GPU resources (Mesh/Textures) but possesses
     * its own Scene Graph (Nodes/Skeleton) and Animator. This allows the instance
     * to play animations independently of others.
     *
     * @return A new model instance.
     */
    public abstract VxModel createInstance();

    /**
     * Releases GPU resources associated with this model.
     */
    public void delete() {
        mesh.delete();
    }

    public VxNode getRootNode() {
        return rootNode;
    }

    public IVxRenderableMesh getMesh() {
        return mesh;
    }

    /**
     * Retrieves the map of available animation clips.
     * <p>
     * This provides access to the raw animation data, allowing external controllers
     * (such as renderers) to inspect available animation names and select the appropriate
     * clip to play based on the entity's state.
     *
     * @return An unmodifiable view or direct reference to the animation map.
     */
    public Map<String, VxAnimation> getAnimations() {
        return animations;
    }

    /**
     * Retrieves the Animator instance controlling this model.
     * <p>
     * Use this to control playback speed, pause state, or manually set the animation time.
     *
     * @return The animator instance.
     */
    public VxAnimator getAnimator() {
        return animator;
    }
}