/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.layout;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Represents the vertex layout for source meshes that support hardware skinning.
 * <p>
 * This layout is used as the <b>input</b> to the skinning compute shader (Transform Feedback).
 * It contains high-precision float data for normals and tangents, as well as bone
 * weights and indices required for the animation calculation.
 * <p>
 * <b>Memory Layout:</b>
 * <ul>
 *   <li>00-12: Position (vec3 float)</li>
 *   <li>12-20: UV0 (vec2 float)</li>
 *   <li>20-32: Normal (vec3 float)</li>
 *   <li>32-48: Tangent (vec4 float)</li>
 *   <li>48-64: Bone Weights (vec4 float)</li>
 *   <li>64-80: Bone Indices (vec4 float)</li>
 * </ul>
 * Total Stride: 80 bytes.
 *
 * @author xI-Mx-Ix
 */
public class VxSkinnedVertexLayout implements IVxVertexLayout {

    public static final int STRIDE = 80;

    private static final VxSkinnedVertexLayout INSTANCE = new VxSkinnedVertexLayout();

    private VxSkinnedVertexLayout() {}

    public static VxSkinnedVertexLayout getInstance() {
        return INSTANCE;
    }

    @Override
    public int getStride() {
        return STRIDE;
    }

    @Override
    public void setupAttributes() {
        // 0: Position (vec3)
        GL30.glEnableVertexAttribArray(0);
        GL30.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, STRIDE, 0);

        // 1: UV0 (vec2)
        GL30.glEnableVertexAttribArray(1);
        GL30.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, STRIDE, 12);

        // 2: Normal (vec3)
        GL30.glEnableVertexAttribArray(2);
        GL30.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, STRIDE, 20);

        // 3: Tangent (vec4)
        GL30.glEnableVertexAttribArray(3);
        GL30.glVertexAttribPointer(3, 4, GL11.GL_FLOAT, false, STRIDE, 32);

        // 4: Bone Weights (vec4)
        GL30.glEnableVertexAttribArray(4);
        GL30.glVertexAttribPointer(4, 4, GL11.GL_FLOAT, false, STRIDE, 48);

        // 5: Bone Indices (vec4)
        // Note: Assimp often provides indices as floats or ints. We use floats here for shader simplicity.
        GL30.glEnableVertexAttribArray(5);
        GL30.glVertexAttribPointer(5, 4, GL11.GL_FLOAT, false, STRIDE, 64);
    }
}