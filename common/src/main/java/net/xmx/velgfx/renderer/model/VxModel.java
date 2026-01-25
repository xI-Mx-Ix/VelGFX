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
import net.xmx.velgfx.renderer.model.skeleton.VxSkeleton;
import net.xmx.velgfx.renderer.model.skeleton.VxSocket;
import org.joml.Matrix4f;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents the abstract base for 3D models in the engine using the SoA architecture.
 * <p>
 * A model aggregates:
 * <ul>
 *     <li>A {@link VxSkeleton} (SoA Runtime State).</li>
 *     <li>A {@link IVxRenderableMesh} (GPU Geometry).</li>
 *     <li>A {@link VxAnimator} (Animation Engine).</li>
 *     <li>A collection of {@link VxSocket}s (Attachments).</li>
 * </ul>
 *
 * @author xI-Mx-Ix
 */
public abstract class VxModel {

    protected final VxSkeleton skeleton;
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
     * @param skeleton        The runtime skeleton instance.
     * @param mesh            The geometry mesh.
     * @param animations      The available animations map.
     * @param morphController The morph controller (optional).
     */
    protected VxModel(VxSkeleton skeleton, IVxRenderableMesh mesh, Map<String, VxAnimation> animations, VxMorphController morphController) {
        this.skeleton = skeleton;
        this.mesh = mesh;
        this.animations = animations != null ? animations : Collections.emptyMap();
        this.animator = new VxAnimator(skeleton, morphController);
    }

    /**
     * Updates the animation state and propagates updates to attachments.
     *
     * @param dt Delta time in seconds.
     */
    public void update(float dt) {
        animator.update(dt);

        // Update attachments
        for (VxSocket socket : sockets.values()) {
            if (socket.getAttachedModel() != null) {
                socket.getAttachedModel().update(dt);
            }
        }
    }

    /**
     * Renders the model.
     *
     * @param poseStack   The current matrix stack.
     * @param packedLight The light value.
     */
    public abstract void render(PoseStack poseStack, int packedLight);

    /**
     * Helper to render all active sockets.
     */
    protected void renderAttachments(PoseStack poseStack, int packedLight) {
        for (VxSocket socket : sockets.values()) {
            socket.render(poseStack, packedLight);
        }
    }

    /**
     * Creates and registers a new attachment socket on a specific bone.
     *
     * @param socketName The unique identifier for this socket.
     * @param boneName   The name of the bone to attach to.
     * @param offset     Optional local offset matrix.
     * @return The created socket, or null if the bone name is invalid.
     */
    public VxSocket createSocket(String socketName, String boneName, Matrix4f offset) {
        // Resolve bone name to index using the skeleton definition
        int index = skeleton.indexOf(boneName);
        if (index == -1) return null;

        VxSocket socket = new VxSocket(socketName, skeleton, index, offset);
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
     * Plays an animation immediately.
     *
     * @param name The animation name.
     */
    public void playAnimation(String name) {
        playAnimation(name, 0.0f);
    }

    /**
     * Plays an animation with blending.
     *
     * @param name           The animation name.
     * @param transitionTime Blend duration in seconds.
     */
    public void playAnimation(String name, float transitionTime) {
        VxAnimation anim = animations.get(name);
        if (anim != null) {
            animator.playAnimation(anim, transitionTime);
        }
    }

    /**
     * Creates a new independent instance of this model (new Skeleton, shared Mesh).
     *
     * @return A new instance.
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

    public VxSkeleton getSkeleton() {
        return skeleton;
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