/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model;

import com.mojang.blaze3d.vertex.PoseStack;
import net.xmx.velgfx.renderer.gl.VxDrawCommand;
import net.xmx.velgfx.renderer.gl.layout.VxSkinnedResultVertexLayout;
import net.xmx.velgfx.renderer.gl.layout.VxSkinnedVertexLayout;
import net.xmx.velgfx.renderer.gl.mesh.arena.VxArenaMesh;
import net.xmx.velgfx.renderer.gl.mesh.arena.VxMemorySegment;
import net.xmx.velgfx.renderer.gl.mesh.arena.skinning.VxSkinnedResultMesh;
import net.xmx.velgfx.renderer.gl.mesh.arena.skinning.VxSkinningArena;
import net.xmx.velgfx.renderer.gl.mesh.arena.skinning.VxSkinningBatcher;
import net.xmx.velgfx.renderer.gl.shader.impl.VxSkinningShader;
import net.xmx.velgfx.renderer.model.animation.VxAnimation;
import net.xmx.velgfx.renderer.model.morph.VxMorphController;
import net.xmx.velgfx.renderer.model.skeleton.VxSkeleton;
import net.xmx.velgfx.renderer.util.VxGlGarbageCollector;
import net.xmx.velgfx.renderer.util.VxTempCache;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL40;

import java.lang.ref.Cleaner;
import java.util.Map;

/**
 * A specialized model that supports hardware vertex skinning and morph target animation
 * using the SoA Skeleton architecture.
 * <p>
 * This class coordinates the GPU Compute Pass (Transform Feedback) by uploading the
 * flattened matrix arrays directly from the {@link VxSkeleton}.
 *
 * @author xI-Mx-Ix
 */
public class VxSkinnedModel extends VxModel {

    /**
     * The static source data (Bind Pose + Bone Weights) stored in the high-capacity Arena.
     * This is used as the <b>Input</b> for the skinning shader.
     */
    private final VxArenaMesh sourceMesh;

    /**
     * The allocated memory segment in the global {@link VxSkinningArena}.
     * This acts as the destination for the Transform Feedback operation for the current frame.
     */
    private final VxMemorySegment resultSegment;

    /**
     * A lightweight proxy object submitted to the render queue.
     * It redirects draw calls to the Arena's shared VAO with offsets calculated from the {@code resultSegment}.
     */
    private final VxSkinnedResultMesh renderProxy;

    /**
     * Manages the runtime state of morph targets (weights, sorting, active selection).
     * Can be null if the model has no morph targets.
     */
    private final VxMorphController morphController;

    /**
     * Handle to the cleaner task.
     */
    private final Cleaner.Cleanable cleanable;

    /**
     * Constructs a new skinned model.
     * <p>
     * Initializes the render proxy and allocates the necessary memory in the
     * global Skinning Arena for the transform feedback results.
     *
     * @param skeleton        The runtime skeleton containing the dynamic pose.
     * @param sourceMesh      The static source geometry residing in the Arena.
     * @param animations      The map of available animation clips.
     * @param morphController The controller for morph target weights (optional).
     */
    public VxSkinnedModel(VxSkeleton skeleton, VxArenaMesh sourceMesh, Map<String, VxAnimation> animations, VxMorphController morphController) {
        super(skeleton, sourceMesh, animations, morphController);
        this.sourceMesh = sourceMesh;
        this.morphController = morphController;

        // Allocate a segment in the global skinning arena to store the transformed vertices.
        // The size is calculated based on the vertex count and the stride of the result layout.
        int vertexCount = (int) (sourceMesh.getVertexSegment().size / VxSkinnedVertexLayout.STRIDE);
        long requiredBytes = (long) vertexCount * VxSkinnedResultVertexLayout.STRIDE;
        this.resultSegment = VxSkinningArena.getInstance().allocate(requiredBytes);

        // Register a cleaner to free the arena memory when this model instance is garbage collected.
        VxMemorySegment segmentToFree = this.resultSegment;
        this.cleanable = VxGlGarbageCollector.getInstance().track(this, () -> {
            VxSkinningArena.getInstance().free(segmentToFree);
        });

        // Create the proxy mesh that renders the transformed data using the original topology.
        this.renderProxy = new VxSkinnedResultMesh(this.resultSegment, sourceMesh);
    }

    /**
     * Creates a new independent instance of this model.
     * <p>
     * The new instance shares the static geometry and animation data but maintains
     * its own Skeleton state (for independent posing) and Morph Controller.
     *
     * @return A new VxSkinnedModel instance.
     */
    @Override
    public VxSkinnedModel createInstance() {
        // Create a deep copy of the skeleton (sharing structure, new dynamic arrays)
        VxSkeleton newSkeleton = new VxSkeleton(this.skeleton);

        // Create a copy of the morph controller if it exists
        VxMorphController newMorphs = (this.morphController != null) ? this.morphController.copy() : null;

        return new VxSkinnedModel(newSkeleton, this.sourceMesh, this.animations, newMorphs);
    }

    /**
     * Updates the animation state and queues the GPU skinning task.
     *
     * @param dt The time elapsed since the last frame in seconds.
     */
    @Override
    public void update(float dt) {
        super.update(dt);

        if (morphController != null) {
            morphController.update();
        }

        // Queue this model for the GPU skinning pass
        VxSkinningBatcher.getInstance().queue(this);
    }

    /**
     * Executes the hardware skinning compute pass.
     * <p>
     * This method uploads the current skeleton matrices and morph weights to the GPU
     * and triggers a Transform Feedback draw call to calculate the final vertex positions.
     * <p>
     *
     * @param shader The active skinning shader program.
     */
    public void dispatchCompute(VxSkinningShader shader) {
        // 1. Upload the flattened skinning matrices (Global Space * Inverse Bind Matrix).
        shader.loadJointTransforms(skeleton.getSkinningMatrices());

        // 2. Upload the Root Transform (Model Matrix) for unskinned vertices.
        // Index 0 represents the root node in the topological sort.
        Matrix4f rootTransform = VxTempCache.get().mat4_1;
        skeleton.getGlobalTransform(0, rootTransform);
        shader.loadBaseTransform(rootTransform);

        // 3. Configure Morph Targets and upload active weights.
        // We resolve the base vertex offset to ensure the shader reads the correct TBO data.
        int baseVertex = sourceMesh.resolveCommand(new VxDrawCommand(null, 0, 0, 0)).baseVertex;

        if (morphController != null) {
            morphController.applyToShader(shader, baseVertex);
        } else {
            shader.loadMorphState(null, null, 0, baseVertex);
        }

        // 4. Bind the destination buffer and execute the compute pass.
        VxSkinningArena.getInstance().bindForFeedback(resultSegment);

        // Render as GL_POINTS to process vertices individually without primitive assembly.
        GL40.glBeginTransformFeedback(GL11.GL_POINTS);

        int totalVertexCount = (int) (sourceMesh.getVertexSegment().size / VxSkinnedVertexLayout.STRIDE);
        GL11.glDrawArrays(GL11.GL_POINTS, baseVertex, totalVertexCount);

        GL40.glEndTransformFeedback();
    }

    /**
     * Renders the model.
     * <p>
     * Instead of queuing the source mesh (which holds the bind pose), we queue the
     * {@code renderProxy} which points to the transformed data in the Skinning Arena.
     *
     * @param poseStack   The current transformation stack.
     * @param packedLight The packed light value.
     */
    @Override
    public void render(PoseStack poseStack, int packedLight) {
        // Render the result proxy (output of transform feedback)
        renderProxy.queueRender(poseStack, packedLight);

        // Render any attachments
        renderAttachments(poseStack, packedLight);
    }

    /**
     * Releases all GPU resources specific to this model instance.
     * <p>
     * Note: Shared resources (Source Mesh, Morph Atlas data) are NOT deleted here
     * as they are managed by the global Managers.
     */
    @Override
    public void delete() {
        // Return the allocated segment to the Arena pool immediately
        cleanable.clean();

        renderProxy.delete();

        // Call super to clean up attachments (Sockets)
        super.delete();
    }

    /**
     * Retrieves the skeleton controlling this model.
     *
     * @return The skeleton instance.
     */
    public VxSkeleton getSkeleton() {
        return skeleton;
    }

    /**
     * Retrieves the controller for morph targets.
     *
     * @return The morph controller, or null if not available.
     */
    public VxMorphController getMorph() {
        return morphController;
    }
}