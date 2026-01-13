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
import net.xmx.velgfx.renderer.gl.mesh.arena.skinning.VxSkinningArena;
import net.xmx.velgfx.renderer.gl.mesh.arena.skinning.VxSkinnedResultMesh;
import net.xmx.velgfx.renderer.gl.shader.VxSkinningShader;
import net.xmx.velgfx.renderer.model.animation.VxAnimation;
import net.xmx.velgfx.renderer.model.skeleton.VxSkeleton;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;

import java.util.Map;

/**
 * A model capable of hardware vertex skinning via Transform Feedback, utilizing the
 * centralized {@link VxSkinningArena} for memory management.
 * <p>
 * This class orchestrates the animation pipeline:
 * <ol>
 *     <li>Updates the {@link VxSkeleton} based on animation time.</li>
 *     <li>Allocates a dynamic segment in the global Skinning Arena (instead of owning a private VBO).</li>
 *     <li>Executes a Compute Pass using {@link VxSkinningShader}, reading from the {@code sourceMesh} (Arena)
 *     and writing into the allocated {@code resultSegment}.</li>
 *     <li>Submits a proxy mesh (which references the global Arena VAO) to the render queue.</li>
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
     * Flattened array holding 4x4 matrices for all bones (Max 100 bones * 16 floats).
     */
    private final float[] boneMatrices = new float[100 * 16];

    /**
     * Constructs a new Skinned Model and initializes the skeletal state.
     *
     * @param skeleton   The skeleton hierarchy containing bones and nodes.
     * @param sourceMesh The source mesh handle located in the Skinned Arena Buffer.
     * @param animations The map of available animation clips.
     */
    public VxSkinnedModel(VxSkeleton skeleton, VxArenaMesh sourceMesh, Map<String, VxAnimation> animations) {
        super(skeleton.getRootNode(), sourceMesh, animations);
        this.skeleton = skeleton;
        this.sourceMesh = sourceMesh;

        // 1. Calculate the required size for the result buffer
        // Input Size (Vertices) * Output Stride (48 bytes)
        int vertexCount = (int) (sourceMesh.getSizeBytes() / VxSkinnedVertexLayout.STRIDE);
        long requiredBytes = (long) vertexCount * VxSkinnedResultVertexLayout.STRIDE;

        // 2. Allocate memory segment in the global Skinning Arena
        this.resultSegment = VxSkinningArena.getInstance().allocate(requiredBytes);

        // 3. Create Render Proxy
        // The proxy acts as a view into the Arena for this specific model instance
        this.renderProxy = new VxSkinnedResultMesh(this.resultSegment, sourceMesh.getDrawCommands());

        // 4. Initialize Hierarchy State
        // Immediately calculates the global transformations for the skeleton using the Bind Pose.
        // This ensures that the bone matrices are valid even before the first animation update.
        this.skeleton.getRootNode().updateHierarchy(null);
    }

    @Override
    public VxSkinnedModel createInstance() {
        // 1. Copy Skeleton (Nodes + Bones)
        VxSkeleton newSkeleton = this.skeleton.deepCopy();

        // 2. Return new model with shared source geometry but unique output segment
        return new VxSkinnedModel(newSkeleton, this.sourceMesh, this.animations);
    }

    /**
     * Updates the animation state and executes the GPU skinning pass.
     *
     * @param dt The time elapsed since the last frame in seconds.
     */
    @Override
    public void update(float dt) {
        // 1. CPU Animation Update (interpolate keyframes, update node matrices)
        super.update(dt);

        // 2. Flatten bone matrices for upload
        skeleton.updateBoneMatrices(boneMatrices);

        // 3. GPU Skinning Pass (Transform Feedback)
        performSkinningPass();
    }

    /**
     * Executes the actual compute pass on the GPU.
     * Reads from Source Arena -> Writes to the allocated Segment in Skinning Arena.
     */
    private void performSkinningPass() {
        VxSkinningArena arena = VxSkinningArena.getInstance();
        VxSkinningShader shader = VxSkinningShader.getInstance();

        shader.bind();
        shader.loadJointTransforms(boneMatrices);

        // Disable rasterization because we are only processing vertices, not drawing pixels.
        GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);

        // 1. Bind Source Data (Bind Pose from Arena)
        sourceMesh.setupVaoState();

        // 2. Bind Output Data (Specific Segment in Giant Buffer)
        // This sets up the Global TFO to point to our specific slice of the VBO.
        arena.bindForFeedback(resultSegment);

        // 3. Begin Transform Feedback
        // We use GL_POINTS to process vertices one-by-one.
        GL40.glBeginTransformFeedback(GL11.GL_POINTS);

        // Calculate the absolute start vertex in the Source Arena Buffer
        int startVertex = sourceMesh.getFinalVertexOffset(new VxDrawCommand(null, 0, 0));
        int vertexCount = (int) (sourceMesh.getSizeBytes() / VxSkinnedVertexLayout.STRIDE);

        // Draw points to trigger the vertex shader for every vertex
        GL11.glDrawArrays(GL11.GL_POINTS, startVertex, vertexCount);

        // 4. End Feedback and Cleanup
        GL40.glEndTransformFeedback();
        arena.unbindFeedback();

        // Re-enable rasterization for the actual rendering pass
        GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
        shader.unbind();
    }

    /**
     * Renders the model.
     * <p>
     * Instead of queuing the source mesh (which holds bind pose), we queue the
     * {@code renderProxy} which points to the transformed data in the Skinning Arena.
     *
     * @param poseStack   The current transformation stack.
     * @param packedLight The packed light value.
     */
    @Override
    public void render(PoseStack poseStack, int packedLight) {
        renderProxy.queueRender(poseStack, packedLight);
    }

    /**
     * Releases all GPU resources associated with this model.
     */
    @Override
    public void delete() {
        // Return the allocated segment to the Arena pool
        VxSkinningArena.getInstance().free(resultSegment);

        // Mark the proxy as deleted to prevent rendering
        renderProxy.delete();

        // Note: The sourceMesh is shared among instances and managed by VxArenaManager,
        // so we do not delete it here.
    }

    public VxSkeleton getSkeleton() {
        return skeleton;
    }
}