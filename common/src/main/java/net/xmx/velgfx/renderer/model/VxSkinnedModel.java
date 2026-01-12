/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model;

import com.mojang.blaze3d.vertex.PoseStack;
import net.xmx.velgfx.renderer.gl.VxDrawCommand;
import net.xmx.velgfx.renderer.gl.VxVertexBuffer;
import net.xmx.velgfx.renderer.gl.layout.VxSkinnedResultVertexLayout;
import net.xmx.velgfx.renderer.gl.layout.VxSkinnedVertexLayout;
import net.xmx.velgfx.renderer.gl.mesh.skinning.VxSkinnedResultMesh;
import net.xmx.velgfx.renderer.gl.mesh.arena.VxArenaMesh;
import net.xmx.velgfx.renderer.gl.shader.VxSkinningShader;
import net.xmx.velgfx.renderer.model.animation.VxAnimation;
import net.xmx.velgfx.renderer.model.skeleton.VxSkeleton;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;

import java.util.Map;

/**
 * A model capable of hardware vertex skinning via Transform Feedback.
 * <p>
 * This class orchestrates the animation pipeline:
 * <ol>
 *     <li>Updates the {@link VxSkeleton} based on animation time.</li>
 *     <li>Executes a Compute Pass using {@link VxSkinningShader}, reading from the {@code sourceMesh} (Arena).</li>
 *     <li>Captures the transformed vertices into the internal {@code resultVbo}.</li>
 *     <li>Submits a proxy mesh pointing to the {@code resultVbo} to the render queue.</li>
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
     * A dynamic buffer that stores the per-frame transformed geometry.
     * This is the <b>Output</b> of the skinning shader.
     */
    private final VxVertexBuffer resultVbo;

    /**
     * The VAO configured to render the data in {@code resultVbo}.
     */
    private final int resultVaoId;

    /**
     * The Transform Feedback Object (TFO) used to capture shader output.
     */
    private final int tfoId;

    /**
     * A lightweight proxy object submitted to the render queue.
     * It redirects draw calls to the {@code resultVaoId} instead of the arena's VAO.
     */
    private final VxSkinnedResultMesh renderProxy;

    /**
     * Flattened array holding 4x4 matrices for all bones (Max 100 bones * 16 floats).
     */
    private final float[] boneMatrices = new float[100 * 16];

    /**
     * Constructs a new Skinned Model.
     *
     * @param skeleton   The skeleton hierarchy containing bones and nodes.
     * @param sourceMesh The source mesh handle located in the Skinned Arena Buffer.
     * @param animations The map of available animation clips.
     */
    public VxSkinnedModel(VxSkeleton skeleton, VxArenaMesh sourceMesh, Map<String, VxAnimation> animations) {
        super(skeleton.getRootNode(), sourceMesh, animations);
        this.skeleton = skeleton;
        this.sourceMesh = sourceMesh;

        // 1. Allocate Result Buffer (Dynamic)
        // Size calculation: Vertices * Result Stride (48 bytes).
        int vertexCount = (int) (sourceMesh.getSizeBytes() / VxSkinnedVertexLayout.STRIDE);
        this.resultVbo = new VxVertexBuffer(vertexCount * VxSkinnedResultVertexLayout.STRIDE, true);

        // 2. Setup Result VAO (Output Layout)
        // This maps the output buffer data (Pos, Normal, UV, Tangent) for the main render pass.
        this.resultVaoId = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(resultVaoId);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, resultVbo.getVboId());

        // Now configure the attribute pointers for resultVaoId
        VxSkinnedResultVertexLayout.getInstance().setupAttributes();

        // Unbind to seal state
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        // 3. Setup Transform Feedback (TFO)
        // Links the shader output slots to our Result VBO.
        this.tfoId = GL40.glGenTransformFeedbacks();
        GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, tfoId);
        GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, resultVbo.getVboId());
        GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, 0);

        // 4. Create Render Proxy
        // We reuse the draw commands from the source mesh (materials, counts),
        // but render using our Result VAO ID via the standalone proxy class.
        this.renderProxy = new VxSkinnedResultMesh(this.resultVaoId, sourceMesh.getDrawCommands());
    }

    @Override
    public VxSkinnedModel createInstance() {
        // 1. Copy Skeleton (Nodes + Bones)
        VxSkeleton newSkeleton = this.skeleton.deepCopy();

        // 2. Return new model with shared source geometry but unique output buffers
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
     * Reads from Source Arena -> Writes to Result VBO.
     */
    private void performSkinningPass() {
        VxSkinningShader shader = VxSkinningShader.getInstance();
        shader.bind();
        shader.loadJointTransforms(boneMatrices);

        // Disable rasterization because we are only processing vertices, not drawing pixels.
        GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);

        // Bind Source Data (Bind Pose from Arena)
        sourceMesh.setupVaoState();

        // Bind TFO to capture output
        GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, tfoId);
        GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, resultVbo.getVboId());

        GL40.glBeginTransformFeedback(GL11.GL_POINTS);

        // Calculate the absolute start vertex in the Arena Buffer
        int startVertex = sourceMesh.getFinalVertexOffset(new VxDrawCommand(null, 0, 0));
        int vertexCount = (int) (sourceMesh.getSizeBytes() / VxSkinnedVertexLayout.STRIDE);

        // Draw points to trigger the vertex shader for every vertex
        GL11.glDrawArrays(GL11.GL_POINTS, startVertex, vertexCount);

        GL40.glEndTransformFeedback();
        GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, 0);

        // Re-enable rasterization for the actual rendering pass
        GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
        shader.unbind();
    }

    /**
     * Renders the model.
     * <p>
     * Instead of queuing the source mesh (which holds bind pose), we queue the
     * {@code renderProxy} which points to the transformed data in the Result VBO.
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
        // Free the source memory in the arena
        sourceMesh.delete();

        // Delete dedicated resources
        resultVbo.delete();
        GL30.glDeleteVertexArrays(resultVaoId);
        GL40.glDeleteTransformFeedbacks(tfoId);
        // The renderProxy doesn't own resources, so we don't call delete on it.
    }

    public VxSkeleton getSkeleton() {
        return skeleton;
    }
}