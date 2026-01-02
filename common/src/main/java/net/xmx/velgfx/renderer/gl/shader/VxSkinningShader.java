/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.shader;

import net.xmx.velgfx.resources.VxResourceLocation;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * A specialized shader program for skeletal animation (skinning).
 * <p>
 * This shader performs vertex skinning on the GPU and outputs the transformed
 * vertices via Transform Feedback to be rendered by the main pass.
 *
 * @author xI-Mx-Ix
 */
public class VxSkinningShader extends VxShaderProgram {

    private static final int MAX_BONES = 100;

    private static final VxResourceLocation VERTEX_SOURCE =
            new VxResourceLocation("velgfx", "shaders/skinning.vsh");
    private static final VxResourceLocation FRAGMENT_SOURCE =
            new VxResourceLocation("velgfx", "shaders/skinning.fsh");

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
     * The order here determines the offset calculation in {@code VxSkinnedMesh.setupResultAttributes}.
     *
     * @param programId The OpenGL program ID.
     */
    @Override
    protected void preLink(int programId) {
        CharSequence[] varyings = {
                "out_Pos",      // vec3
                "out_Normal",   // vec3
                "out_UV",       // vec2
                "out_Tangent"   // vec4
        };

        // GL_INTERLEAVED_ATTRIBS writes all variables into a single buffer, packed continuously.
        GL30.glTransformFeedbackVaryings(programId, varyings, GL30.GL_INTERLEAVED_ATTRIBS);
    }

    @Override
    protected void registerUniforms() {
        // Register array uniforms for bone matrices
        // Assumes uniform name "u_BoneMatrices" as per latest shader logic
        for (int i = 0; i < MAX_BONES; i++) {
            super.createUniform("u_BoneMatrices[" + i + "]");
        }
    }

    /**
     * Uploads the joint (bone) transformation matrices to the GPU.
     *
     * @param jointMatrices The flat array of bone matrices (16 floats per bone).
     */
    public void loadJointTransforms(float[] jointMatrices) {
        // Upload the entire array to the uniform array location
        int location = super.getUniformLocation("u_BoneMatrices[0]");
        if (location != -1) {
            GL20.glUniformMatrix4fv(location, false, jointMatrices);
        }
    }
}