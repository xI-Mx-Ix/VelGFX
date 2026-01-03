/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.mesh.impl;

import net.xmx.velgfx.renderer.gl.VxDrawCommand;
import net.xmx.velgfx.renderer.gl.VxVertexBuffer;
import net.xmx.velgfx.renderer.gl.layout.VxSkinnedVertexLayout;
import net.xmx.velgfx.renderer.gl.mesh.VxAbstractRenderableMesh;
import net.xmx.velgfx.renderer.gl.mesh.arena.VxArenaBuffer;
import net.xmx.velgfx.renderer.gl.shader.VxSkinningShader;
import net.xmx.velgfx.renderer.model.animation.VxAnimator;
import net.xmx.velgfx.renderer.model.skeleton.VxSkeleton;
import org.lwjgl.opengl.*;

import java.util.List;
import java.util.Map;

/**
 * A specialized mesh implementation that supports hardware skinning via Transform Feedback.
 * <p>
 * This class manages the pipeline for animating skeletal meshes:
 * <ol>
 *     <li><b>Source Data (Bind Pose):</b> Stored in a global {@link VxArenaBuffer} allocated via {@link VxSkinnedVertexLayout}.</li>
 *     <li><b>Skinning Pass:</b> A GPU compute step using {@link VxSkinningShader} to transform vertices based on the skeleton.</li>
 *     <li><b>Result VBO:</b> A dynamic buffer that captures the transformed geometry (Position, Normal, UV, Tangent) via Transform Feedback.</li>
 * </ol>
 * The main rendering queue draws from the <b>Result VBO</b>.
 *
 * @author xI-Mx-Ix
 */
public class VxSkinnedMesh extends VxAbstractRenderableMesh {

    /**
     * The stride of the transformed vertices in the Result VBO.
     * Structure: Position(vec3) + Normal(vec3) + UV(vec2) + Tangent(vec4)
     * Size: 12 + 12 + 8 + 16 = 48 bytes.
     */
    private static final int RESULT_STRIDE = 48;

    /**
     * Handle to the static bind-pose data residing in the Arena Buffer.
     */
    private final VxArenaMesh sourceMesh;

    /**
     * Dedicated VBO to store the transformed vertices for this specific mesh instance.
     */
    private final VxVertexBuffer resultVbo;

    /**
     * Dedicated VAO for rendering the result buffer.
     */
    private final int resultVaoId;

    // OpenGL Transform Feedback Object
    private final int tfoId;

    // Animation Components
    private final VxSkeleton skeleton;
    private final VxAnimator animator;
    private final VxSkinningShader skinningShader;

    // Buffer for bone matrices (100 bones * 16 floats)
    private final float[] boneMatrices = new float[100 * 16];

    /**
     * Constructs a new skinned mesh.
     *
     * @param allDrawCommands The draw commands associated with this mesh.
     * @param sourceMesh      The handle to the static mesh data in the Skinned Arena.
     * @param skeleton        The skeletal hierarchy for animation.
     * @param shader          The skinning shader used for the compute pass.
     */
    public VxSkinnedMesh(List<VxDrawCommand> allDrawCommands,
                         VxArenaMesh sourceMesh,
                         VxSkeleton skeleton,
                         VxSkinningShader shader) {
        super(allDrawCommands, Map.of());
        this.sourceMesh = sourceMesh;
        this.skeleton = skeleton;
        // The animator works on the node graph, which is the root of the skeleton.
        this.animator = new VxAnimator(skeleton.getRootNode());
        this.skinningShader = shader;

        // 1. Setup Result VBO (Output storage)
        // Calculate the number of vertices based on the source capacity.
        int vertexCount = (int) (sourceMesh.getSizeBytes() / VxSkinnedVertexLayout.STRIDE);
        // Create the result buffer. Since this is written to by the GPU every frame, it is dynamic.
        this.resultVbo = new VxVertexBuffer(vertexCount * RESULT_STRIDE, true);

        // 2. Setup Result VAO (Render Layout)
        // This maps the transformed data in the resultVbo to the attributes expected by the main render pipeline.
        this.resultVaoId = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(resultVaoId);
        resultVbo.bind();
        setupResultAttributes();
        GL30.glBindVertexArray(0);

        // 3. Setup Transform Feedback Object (TFO)
        // Links the shader output to the result VBO.
        this.tfoId = GL40.glGenTransformFeedbacks();
        GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, tfoId);
        GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, resultVbo.getVboId());
        GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, 0);

        initializeTextures();
    }

    /**
     * Updates the animation state and executes the skinning compute pass on the GPU.
     *
     * @param dt Delta time in seconds.
     */
    @Override
    public void update(float dt) {
        // 1. Update Skeleton Hierarchy (CPU)
        animator.update(dt);
        skeleton.updateBoneMatrices(boneMatrices);

        // 2. Perform Vertex Skinning (GPU)
        performSkinningPass();
    }

    /**
     * Executes the Transform Feedback pass.
     * This reads from the Source VBO (via Arena), applies bone matrices, and writes to the Result VBO.
     */
    private void performSkinningPass() {
        skinningShader.bind();
        skinningShader.loadJointTransforms(boneMatrices);

        // Disable rasterization because we are only processing vertices, not drawing pixels.
        GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);

        // Bind the Source VAO from the Arena
        sourceMesh.setupVaoState();

        GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, tfoId);

        GL40.glBeginTransformFeedback(GL11.GL_POINTS);

        // Process only the vertices belonging to this mesh within the Arena
        int startVertex = sourceMesh.getFinalVertexOffset(new VxDrawCommand(null, 0, 0));
        int vertexCount = (int) (sourceMesh.getSizeBytes() / VxSkinnedVertexLayout.STRIDE);

        GL11.glDrawArrays(GL11.GL_POINTS, startVertex, vertexCount);

        GL40.glEndTransformFeedback();

        GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, 0);

        // Re-enable rasterization for the actual rendering pass
        GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
        skinningShader.unbind();
    }

    /**
     * Configures the VAO attributes for the Result VBO.
     * <p>
     * The Result VBO contains interleaved data written by the skinning shader:
     * [Position (12B)] [Normal (12B)] [UV (8B)] [Tangent (16B)]
     */
    private void setupResultAttributes() {
        // Offsets must match the order of 'varyings' in VxSkinningShader.preLink
        // Order: out_Pos, out_Normal, out_UV, out_Tangent

        // 0: Position (vec3)
        GL30.glEnableVertexAttribArray(0);
        GL30.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, RESULT_STRIDE, 0);

        // 1: Color (Not present in result, disable and set default white)
        GL30.glDisableVertexAttribArray(1);
        GL20.glVertexAttrib4f(1, 1.0f, 1.0f, 1.0f, 1.0f);

        // 2: UV0 (vec2) -> Offset 24 (Pos 12 + Normal 12)
        GL30.glEnableVertexAttribArray(2);
        GL30.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, RESULT_STRIDE, 24);

        // 4: Lightmap UV
        GL30.glDisableVertexAttribArray(4);

        // 5: Normal (vec3) -> Offset 12 (after Pos)
        GL30.glEnableVertexAttribArray(5);
        GL30.glVertexAttribPointer(5, 3, GL11.GL_FLOAT, false, RESULT_STRIDE, 12);

        // 7: MidTexCoord (Used for Parallax, reuse UV0)
        GL30.glEnableVertexAttribArray(7);
        GL30.glVertexAttribPointer(7, 2, GL11.GL_FLOAT, false, RESULT_STRIDE, 24);

        // 8: Tangent (vec4) -> Offset 32 (Pos 12 + Normal 12 + UV 8)
        GL30.glEnableVertexAttribArray(8);
        GL30.glVertexAttribPointer(8, 4, GL11.GL_FLOAT, false, RESULT_STRIDE, 32);
    }

    @Override
    public void setupVaoState() {
        // Bind the Result VAO for the main rendering pipeline
        GL30.glBindVertexArray(resultVaoId);
    }

    @Override
    public int getFinalVertexOffset(VxDrawCommand command) {
        // Dedicated Result VBO means local offset (0-based from start of command)
        return command.vertexOffset;
    }

    @Override
    public void delete() {
        if (!isDeleted) {
            sourceMesh.delete(); // Free from Arena
            resultVbo.delete();
            GL30.glDeleteVertexArrays(resultVaoId);
            GL40.glDeleteTransformFeedbacks(tfoId);
            isDeleted = true;
        }
    }

    /**
     * Gets the animator instance to control animations.
     *
     * @return The animator.
     */
    public VxAnimator getAnimator() {
        return animator;
    }
}