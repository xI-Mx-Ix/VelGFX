/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.skeleton;

import com.mojang.blaze3d.vertex.PoseStack;
import net.xmx.velgfx.renderer.model.VxModel;
import org.joml.Matrix4f;

/**
 * Represents an attachment point on a model's skeleton.
 * <p>
 * A socket is linked to a specific {@link VxNode} (bone) and inherits its global transformation.
 * It allows attaching another {@link VxModel} (e.g., a weapon, armor, or particle emitter)
 * that will automatically move and rotate with the parent node.
 *
 * @author xI-Mx-Ix
 */
public class VxSocket {

    private final String name;
    private final VxNode parentNode;
    private final Matrix4f localOffset;
    private VxModel attachedModel;

    /**
     * Constructs a new socket.
     *
     * @param name       The unique name of this socket.
     * @param parentNode The node this socket is attached to.
     * @param localOffset A local transformation matrix offset relative to the parent node (can be null for identity).
     */
    public VxSocket(String name, VxNode parentNode, Matrix4f localOffset) {
        this.name = name;
        this.parentNode = parentNode;
        this.localOffset = localOffset != null ? new Matrix4f(localOffset) : new Matrix4f();
        this.attachedModel = null;
    }

    /**
     * Attaches a model to this socket.
     *
     * @param model The model to attach.
     */
    public void attach(VxModel model) {
        this.attachedModel = model;
    }

    /**
     * Detaches the currently attached model, if any.
     */
    public void detach() {
        this.attachedModel = null;
    }

    /**
     * Renders the attached model, transforming it into the socket's world space.
     *
     * @param poseStack   The global matrix stack.
     * @param packedLight The light value to use for rendering.
     */
    public void render(PoseStack poseStack, int packedLight) {
        if (attachedModel == null) return;

        poseStack.pushPose();

        // 1. Apply the parent node's global transform (World Space of the Bone)
        Matrix4f boneMatrix = parentNode.getGlobalTransform();
        poseStack.mulPose(boneMatrix);

        // 2. Apply the local socket offset (e.g. to position a sword hilt correctly in the hand)
        poseStack.mulPose(localOffset);

        // 3. Render the attached model
        attachedModel.render(poseStack, packedLight);

        poseStack.popPose();
    }

    /**
     * Gets the name of this socket.
     *
     * @return The socket name.
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the currently attached model.
     *
     * @return The model, or null if empty.
     */
    public VxModel getAttachedModel() {
        return attachedModel;
    }
}