/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.skeleton;

import com.mojang.blaze3d.vertex.PoseStack;
import net.xmx.velgfx.renderer.model.VxModel;
import org.joml.Matrix4f;

/**
 * Represents an attachment point on a model's skeleton using the SoA architecture.
 * <p>
 * A socket defines a transform space relative to a specific bone. Unlike previous iterations
 * that held references to node objects, this implementation holds an integer index into
 * the skeleton's global matrix array.
 *
 * @author xI-Mx-Ix
 */
public class VxSocket {

    /**
     * The unique name of this socket (e.g., "Hand_R_Weapon").
     */
    private final String name;

    /**
     * The index of the bone in the {@link VxSkeleton} that drives this socket.
     */
    private final int boneIndex;

    /**
     * The skeleton instance this socket belongs to.
     */
    private final VxSkeleton skeleton;

    /**
     * A local offset matrix relative to the parent bone.
     * <p>
     * Useful for adjusting the position/rotation of the attached item without
     * modifying the bone itself (e.g., rotating a sword 90 degrees to fit in the hand).
     */
    private final Matrix4f localOffset;

    /**
     * Cached matrix to avoid allocation when reading from the skeleton.
     */
    private final Matrix4f boneMatrixCache = new Matrix4f();

    /**
     * The model currently attached to this socket (optional).
     */
    private VxModel attachedModel;

    /**
     * Constructs a new socket.
     *
     * @param name        The unique name of this socket.
     * @param skeleton    The parent skeleton instance.
     * @param boneIndex   The index of the bone in the skeleton.
     * @param localOffset A local transformation matrix offset relative to the bone (can be null for identity).
     */
    public VxSocket(String name, VxSkeleton skeleton, int boneIndex, Matrix4f localOffset) {
        this.name = name;
        this.skeleton = skeleton;
        this.boneIndex = boneIndex;
        this.localOffset = localOffset != null ? new Matrix4f(localOffset) : new Matrix4f();
        this.attachedModel = null;
    }

    /**
     * Attaches a model to this socket.
     * <p>
     * The attached model will automatically inherit the transform of the socket during rendering.
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
     * @param poseStack   The global matrix stack from the renderer.
     * @param packedLight The light value to use for rendering.
     */
    public void render(PoseStack poseStack, int packedLight) {
        if (attachedModel == null) return;

        poseStack.pushPose();

        // 1. Fetch the parent bone's global transform directly from the skeleton's SoA
        skeleton.getGlobalTransform(boneIndex, boneMatrixCache);
        poseStack.mulPose(boneMatrixCache);

        // 2. Apply the local socket offset
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