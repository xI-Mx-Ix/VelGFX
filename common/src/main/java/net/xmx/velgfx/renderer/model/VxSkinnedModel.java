/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.xmx.velgfx.renderer.VelGFX;
import net.xmx.velgfx.renderer.gl.VxDrawCommand;
import net.xmx.velgfx.renderer.gl.layout.VxSkinnedResultVertexLayout;
import net.xmx.velgfx.renderer.gl.layout.VxSkinnedVertexLayout;
import net.xmx.velgfx.renderer.gl.mesh.arena.VxArenaMesh;
import net.xmx.velgfx.renderer.gl.mesh.arena.VxMemorySegment;
import net.xmx.velgfx.renderer.gl.mesh.arena.skinning.VxSkinnedResultMesh;
import net.xmx.velgfx.renderer.gl.mesh.arena.skinning.VxSkinningArena;
import net.xmx.velgfx.renderer.gl.shader.impl.VxSkinningShader;
import net.xmx.velgfx.renderer.model.animation.VxAnimation;
import net.xmx.velgfx.renderer.model.morph.VxMorphController;
import net.xmx.velgfx.renderer.model.skeleton.VxSkeleton;
import net.xmx.velgfx.renderer.util.VxGlGarbageCollector;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;

import java.lang.ref.Cleaner;
import java.util.Map;

/**
 * A specialized model that supports hardware vertex skinning and morph target animation.
 * <p>
 * This class utilizes a <b>Transform Feedback</b> pipeline to perform vertex deformation on the GPU.
 * It manages the lifecycle of dynamic memory within the {@link VxSkinningArena} and orchestrates
 * the compute pass that applies both Morph Deltas (read from a Texture Buffer Object) and
 * Skeletal Deformations (via Matrix Palette Skinning).
 * <p>
 * <b>Pipeline Overview:</b>
 * <ol>
 *     <li><b>Update:</b> Advances animation time, updates the node hierarchy, and sorts active morph targets.</li>
 *     <li><b>Compute Pass:</b>
 *         <ul>
 *             <li>Reads static vertices (Bind Pose) from the Source Arena Mesh.</li>
 *             <li>Reads Morph Deltas from the TBO.</li>
 *             <li>Applies Linear Blend Skinning.</li>
 *             <li>Writes transformed vertices to a dynamic segment in the {@link VxSkinningArena}.</li>
 *         </ul>
 *     </li>
 *     <li><b>Render Pass:</b> Submits a proxy mesh pointing to the transformed data in the Arena.</li>
 * </ol>
 *
 * @author xI-Mx-Ix
 */
public class VxSkinnedModel extends VxModel {

    private final VxSkeleton skeleton;

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
     * Flattened array holding 4x4 matrices for all bones (Max 100 bones * 16 floats).
     * Used to upload the matrix palette to the shader uniform.
     */
    private final float[] boneMatrices = new float[100 * 16];

    /**
     * Handle to the cleaner task.
     */
    private final Cleaner.Cleanable cleanable;

    /**
     * Constructs a new Skinned Model and initializes the skeletal state.
     *
     * @param skeleton        The skeleton hierarchy containing bones and nodes.
     * @param sourceMesh      The source mesh handle located in the Skinned Arena Buffer.
     * @param animations      The map of available animation clips.
     * @param morphController The controller for morph targets (optional, can be null).
     */
    public VxSkinnedModel(VxSkeleton skeleton, VxArenaMesh sourceMesh, Map<String, VxAnimation> animations, VxMorphController morphController) {
        super(skeleton.getRootNode(), sourceMesh, animations, morphController);
        this.skeleton = skeleton;
        this.sourceMesh = sourceMesh;
        this.morphController = morphController;

        // 1. Calculate the required size for the result buffer
        // Input Size (Vertices) * Output Stride (48 bytes)
        int vertexCount = (int) (sourceMesh.getVertexSegment().size / VxSkinnedVertexLayout.STRIDE);
        long requiredBytes = (long) vertexCount * VxSkinnedResultVertexLayout.STRIDE;

        // 2. Allocate memory segment in the global Skinning Arena
        this.resultSegment = VxSkinningArena.getInstance().allocate(requiredBytes);

        VxMemorySegment segmentToFree = this.resultSegment;

        this.cleanable = VxGlGarbageCollector.getInstance().track(this, () -> {
            VxSkinningArena.getInstance().free(segmentToFree);
        });

        // 3. Create Render Proxy
        // The proxy acts as a view into the Arena for this specific model instance
        this.renderProxy = new VxSkinnedResultMesh(this.resultSegment, sourceMesh);

        // 4. Initialize Hierarchy State
        // Immediately calculates the global transformations for the skeleton using the Bind Pose.
        // This ensures that the bone matrices are valid even before the first animation update.
        this.skeleton.getRootNode().updateHierarchy(null);
    }

    /**
     * Creates an independent instance of this model.
     * <p>
     * The new instance shares the heavy GPU geometry (Source Mesh) and Animation definitions,
     * but possesses its own unique:
     * <ul>
     *     <li><b>Skeleton:</b> Allows independent skeletal animation.</li>
     *     <li><b>Morph Controller:</b> Allows independent facial expressions.</li>
     *     <li><b>Output Buffer:</b> Allocates a new segment in the Skinning Arena.</li>
     * </ul>
     *
     * @return A new model instance.
     */
    @Override
    public VxSkinnedModel createInstance() {
        // 1. Copy Skeleton (Nodes + Bones)
        VxSkeleton newSkeleton = this.skeleton.deepCopy();

        // 2. Copy Morph Controller (if present) to maintain independent weights
        VxMorphController newMorphs = (this.morphController != null) ? this.morphController.copy() : null;

        // 3. Return new model
        return new VxSkinnedModel(newSkeleton, this.sourceMesh, this.animations, newMorphs);
    }

    /**
     * Updates the animation state and executes the GPU skinning pass.
     *
     * @param dt The time elapsed since the last frame in seconds.
     */
    @Override
    public void update(float dt) {
        // 1. CPU Animation Update
        // This advances the Animator, which updates Node Transforms (Skeleton)
        // and Morph Weights (via the MorphController).
        super.update(dt);

        // 2. Update Morph Controller State
        // Sorts active targets by weight and prepares data for the shader.
        if (morphController != null) {
            morphController.update();
        }

        // 3. Flatten bone matrices for upload
        skeleton.updateBoneMatrices(boneMatrices);

        // 4. GPU Skinning Pass (Transform Feedback)
        performSkinningPass();
    }

    /**
     * Executes the actual compute pass on the GPU.
     * <p>
     * <b>Process:</b>
     * <ol>
     *     <li>Binds the Skinning Shader.</li>
     *     <li>Uploads Bone Matrices and Active Morph Targets.</li>
     *     <li>Binds the Source VAO (Input) and Destination TFO (Output).</li>
     *     <li>Dispatches a {@code glDrawArrays(GL_POINTS)} command to process vertices linearly.</li>
     * </ol>
     */
    private void performSkinningPass() {
        VxSkinningArena arena = VxSkinningArena.getInstance();
        VxSkinningShader shader = VelGFX.getShaderManager().getSkinningShader();

        try {
            shader.bind();
            shader.loadJointTransforms(boneMatrices);

            // Disable rasterization because we are only processing vertices, not drawing pixels.
            GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);

            // 1. Bind Source Data (Bind Pose from Static Arena)
            sourceMesh.setupVaoState();

            // 2. Upload Morph State (if applicable)
            if (morphController != null) {
                int baseVertex = sourceMesh.resolveCommand(
                        new VxDrawCommand(null, 0, 0, 0)
                ).baseVertex;

                morphController.applyToShader(shader, baseVertex);
            } else {
                shader.loadMorphState(null, null, 0, 0);
            }

            // 3. Bind Output Data (Specific Segment in Giant Buffer)
            arena.bindForFeedback(resultSegment);

            // 4. Begin Transform Feedback
            GL40.glBeginTransformFeedback(GL11.GL_POINTS);

            int totalVertexCount = (int) (sourceMesh.getVertexSegment().size / VxSkinnedVertexLayout.STRIDE);
            int firstVertex = sourceMesh.resolveCommand(
                    new VxDrawCommand(null, 0, 0, 0)
            ).baseVertex;

            GL11.glDrawArrays(GL11.GL_POINTS, firstVertex, totalVertexCount);

            GL40.glEndTransformFeedback();
            arena.unbindFeedback();

        } finally {
            // Restore State for the main render pass
            GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
            shader.unbind();
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        }
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
        // 1. Render the main skinned character mesh (Output of Transform Feedback)
        renderProxy.queueRender(poseStack, packedLight);

        // 2. Render any attachments (weapons, items) linked to the sockets
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