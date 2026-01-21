/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.shader.impl;

import net.xmx.velgfx.renderer.gl.mesh.arena.skinning.VxMorphTextureAtlas;
import net.xmx.velgfx.renderer.gl.shader.VxShaderProgram;
import net.xmx.velgfx.resources.VxResourceLocation;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * A specialized shader program for skeletal animation (skinning) and morph target blending.
 * <p>
 * This shader performs vertex deformation on the GPU and outputs the transformed
 * vertices via Transform Feedback to be rendered by the main pass.
 * <p>
 * Updates:
 * <ul>
 *     <li>Added uniforms for Morph Target TBO (Texture Buffer Object).</li>
 *     <li>Added logic to upload active morph indices and weights.</li>
 * </ul>
 *
 * @author xI-Mx-Ix
 */
public class VxSkinningShader extends VxShaderProgram {

    private static final int MAX_BONES = 100;
    private static final int MAX_ACTIVE_MORPHS = 8;

    /**
     * Dedicated Texture Unit for the Morph Target TBO.
     * <p>
     * Using Unit 14 avoids conflicts with Minecraft's standard texture units.
     * This prevents rendering artifacts on subsequent entities.
     */
    private static final int MORPH_TBO_UNIT = 14;

    private static final VxResourceLocation VERTEX_SOURCE =
            new VxResourceLocation("velgfx", "shaders/skinning.vsh");
    private static final VxResourceLocation FRAGMENT_SOURCE =
            new VxResourceLocation("velgfx", "shaders/skinning.fsh");

    /**
     * Private constructor to enforce Singleton pattern.
     */
    public VxSkinningShader() {
        super(VERTEX_SOURCE, FRAGMENT_SOURCE);
    }

    /**
     * Binds vertex attributes to specific indices.
     * <p>
     * <b>Note:</b> These indices must strictly match the layout defined in
     * {@link net.xmx.velgfx.renderer.gl.layout.VxSkinnedVertexLayout}.
     * Updated to match GLSL shader input:
     * 0: in_Pos, 1: in_UV, 2: in_Normal, 3: in_Tangent, 4: in_Weights, 5: in_Joints
     */
    @Override
    protected void bindAttributes() {
        super.bindAttribute(0, "in_Pos");
        super.bindAttribute(1, "in_UV");
        super.bindAttribute(2, "in_Normal");
        super.bindAttribute(3, "in_Tangent");
        super.bindAttribute(4, "in_Weights");
        super.bindAttribute(5, "in_Joints");
    }

    /**
     * Configures the Transform Feedback varyings before linking the program.
     * <p>
     * This defines the structure of the data written to the Result VBO.
     * The order here determines the offset calculation in the mesh implementation.
     *
     * @param programId The OpenGL program ID.
     */
    @Override
    protected void preLink(int programId) {
        // Output varyings for Transform Feedback
        CharSequence[] varyings = {
                "out_Pos",
                "out_Normal",
                "out_UV",
                "out_Tangent"
        };
        GL30.glTransformFeedbackVaryings(programId, varyings, GL30.GL_INTERLEAVED_ATTRIBS);
    }

    @Override
    protected void registerUniforms() {
        // Bone Matrices
        for (int i = 0; i < MAX_BONES; i++) {
            super.createUniform("u_BoneMatrices[" + i + "]");
        }

        // Morph Targets
        super.createUniform("u_MorphDeltas"); // SamplerBuffer
        super.createUniform("u_ActiveMorphCount");
        super.createUniform("u_MeshBaseVertex");

        for (int i = 0; i < MAX_ACTIVE_MORPHS; i++) {
            super.createUniform("u_ActiveMorphIndices[" + i + "]");
            super.createUniform("u_ActiveMorphWeights[" + i + "]");
        }
    }

    /**
     * Uploads the joint (bone) transformation matrices to the GPU.
     *
     * @param jointMatrices The flat array of bone matrices.
     */
    public void loadJointTransforms(float[] jointMatrices) {
        int location = super.getUniformLocation("u_BoneMatrices[0]");
        if (location != -1) {
            GL20.glUniformMatrix4fv(location, false, jointMatrices);
        }
    }

    /**
     * Configures the shader for the current mesh's morph state.
     *
     * @param indices        Array of size 8 containing TBO offsets for active targets.
     * @param weights        Array of size 8 containing weights (0-1).
     * @param count          Number of active targets.
     * @param meshBaseVertex The absolute start index of the mesh in the Arena VBO.
     */
    public void loadMorphState(int[] indices, float[] weights, int count, int meshBaseVertex) {
        // Bind the TBO
        VxMorphTextureAtlas.getInstance().bind(MORPH_TBO_UNIT);
        super.setUniform("u_MorphDeltas", MORPH_TBO_UNIT);

        super.setUniform("u_ActiveMorphCount", count);
        super.setUniform("u_MeshBaseVertex", meshBaseVertex);

        // Upload Arrays (Bulk upload)
        if (indices != null && weights != null) {
            int locIndices = super.getUniformLocation("u_ActiveMorphIndices[0]");
            int locWeights = super.getUniformLocation("u_ActiveMorphWeights[0]");

            if (locIndices != -1) GL20.glUniform1iv(locIndices, indices);
            if (locWeights != -1) GL20.glUniform1fv(locWeights, weights);
        }
    }
}