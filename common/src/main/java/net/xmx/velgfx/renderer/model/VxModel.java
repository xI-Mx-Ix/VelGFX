/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model;

import com.mojang.blaze3d.vertex.PoseStack;
import net.xmx.velgfx.renderer.gl.mesh.IVxRenderableMesh;
import net.xmx.velgfx.renderer.model.animation.VxAnimation;
import net.xmx.velgfx.renderer.model.animation.VxAnimator;
import net.xmx.velgfx.renderer.model.morph.VxMorphController;
import net.xmx.velgfx.renderer.model.skeleton.VxNode;
import net.xmx.velgfx.renderer.model.skeleton.VxSocket;
import org.joml.Matrix4f;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents the abstract base for 3D models in the engine.
 * <p>
 * A model acts as a container for:
 * <ul>
 *     <li><b>Geometry:</b> Handled by {@link IVxRenderableMesh} (VBO/VAO).</li>
 *     <li><b>Hierarchy:</b> A root {@link VxNode} representing the scene graph.</li>
 *     <li><b>Animations:</b> A library of available clips.</li>
 *     <li><b>Sockets:</b> Defined attachment points linked to specific nodes (bones) for equipping items or attachments.</li>
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
     * A map of defined sockets, keyed by their unique name.
     */
    protected final Map<String, VxSocket> sockets = new HashMap<>();

    /**
     * Constructs a base model.
     *
     * @param rootNode   The root of the scene hierarchy.
     * @param mesh       The renderable mesh resource.
     * @param animations The map of animation clips.
     * @param morphController The controller for morph targets.
     */
    protected VxModel(VxNode rootNode, IVxRenderableMesh mesh, Map<String, VxAnimation> animations, VxMorphController morphController) {
        this.rootNode = rootNode;
        this.mesh = mesh;
        this.animations = animations != null ? animations : Collections.emptyMap();
        this.animator = new VxAnimator(rootNode, morphController);
    }

    /**
     * Updates the animation state of this model and any attached models.
     * <p>
     * This advances the animator's time cursor and recalculates the node hierarchy
     * matrices. It then propagates the update to all models currently attached to sockets.
     *
     * @param dt Delta time in seconds.
     */
    public void update(float dt) {
        animator.update(dt);

        // Propagate update to attached models (e.g., weapons, equipment)
        for (VxSocket socket : sockets.values()) {
            if (socket.getAttachedModel() != null) {
                socket.getAttachedModel().update(dt);
            }
        }
    }

    /**
     * Renders the model into the pipeline.
     * <p>
     * Implementations must ensure that {@link #renderAttachments(PoseStack, int)} is called
     * to draw any objects attached to sockets.
     *
     * @param poseStack   The current transformation stack.
     * @param packedLight The packed light value.
     */
    public abstract void render(PoseStack poseStack, int packedLight);

    /**
     * Renders all attached models on their respective sockets.
     * <p>
     * This iterates through all defined sockets, applies the global transform of the
     * parent bone, and renders the attached model in the correct position and orientation.
     *
     * @param poseStack   The current transformation stack.
     * @param packedLight The packed light value.
     */
    protected void renderAttachments(PoseStack poseStack, int packedLight) {
        if (!sockets.isEmpty()) {
            for (VxSocket socket : sockets.values()) {
                socket.render(poseStack, packedLight);
            }
        }
    }

    /**
     * Creates and registers a new attachment socket on a specific bone/node.
     *
     * @param socketName The unique identifier for this socket (e.g., "Hand_R_Weapon").
     * @param boneName   The name of the node (bone) to attach to (e.g., "Hand_R").
     * @param offset     An optional local offset matrix relative to the bone. Passing null results in an identity transform.
     * @return The created socket, or null if the specified bone name was not found in the hierarchy.
     */
    public VxSocket createSocket(String socketName, String boneName, Matrix4f offset) {
        VxNode targetNode = rootNode.findByName(boneName);
        if (targetNode == null) {
            return null;
        }
        VxSocket socket = new VxSocket(socketName, targetNode, offset);
        sockets.put(socketName, socket);
        return socket;
    }

    /**
     * Attaches a model to an existing socket.
     *
     * @param socketName The name of the socket.
     * @param model      The model to attach.
     */
    public void attachToSocket(String socketName, VxModel model) {
        VxSocket socket = sockets.get(socketName);
        if (socket != null) {
            socket.attach(model);
        }
    }

    /**
     * Starts playing the specified animation immediately.
     * <p>
     * This switches the animator to the target animation instantly without blending.
     *
     * @param name The name of the animation clip.
     */
    public void playAnimation(String name) {
        playAnimation(name, 0.0f);
    }

    /**
     * Starts playing the specified animation with a blend transition.
     * <p>
     * The animator will cross-fade from the current state to the new animation
     * over the specified duration.
     *
     * @param name           The name of the animation clip.
     * @param transitionTime The time in seconds to blend from the current animation to the new one.
     */
    public void playAnimation(String name, float transitionTime) {
        VxAnimation anim = animations.get(name);
        if (anim != null) {
            animator.playAnimation(anim, transitionTime);
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
     * <p>
     * This deletes the underlying mesh resources and recursively deletes any
     * models currently attached to sockets.
     */
    public void delete() {
        mesh.delete();

        // Recursively cleanup attached models
        for (VxSocket socket : sockets.values()) {
            if (socket.getAttachedModel() != null) {
                socket.getAttachedModel().delete();
            }
        }
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