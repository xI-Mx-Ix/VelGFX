/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.layout;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Represents the vertex layout for the output of the hardware skinning pass (Transform Feedback).
 * <p>
 * This layout corresponds to the data written by the {@link net.xmx.velgfx.renderer.gl.shader.VxSkinningShader}
 * into the dynamic Result VBO. It does not contain bone weights or indices, as the geometry
 * has already been transformed into World/Camera space.
 * <p>
 * <b>Memory Layout (Interleaved):</b>
 * <ul>
 *   <li>00-12: Position (vec3 float)</li>
 *   <li>12-24: Normal (vec3 float)</li>
 *   <li>24-32: UV0 (vec2 float)</li>
 *   <li>32-48: Tangent (vec4 float)</li>
 * </ul>
 * Total Stride: 48 bytes.
 *
 * @author xI-Mx-Ix
 */
public class VxSkinnedResultVertexLayout implements IVxVertexLayout {

    public static final int STRIDE = 48;

    private static final VxSkinnedResultVertexLayout INSTANCE = new VxSkinnedResultVertexLayout();

    private VxSkinnedResultVertexLayout() {}

    public static VxSkinnedResultVertexLayout getInstance() {
        return INSTANCE;
    }

    @Override
    public int getStride() {
        return STRIDE;
    }

    @Override
    public void setupAttributes() {
        // 1. Position (vec3) -> Offset 0
        GL30.glEnableVertexAttribArray(0);
        GL30.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, STRIDE, 0);

        // 2. Normal (vec3) -> Offset 12
        GL30.glEnableVertexAttribArray(5); // Location 5 is standard for Normals in this engine
        GL30.glVertexAttribPointer(5, 3, GL11.GL_FLOAT, false, STRIDE, 12);

        // 3. UV0 (vec2) -> Offset 24
        GL30.glEnableVertexAttribArray(2);
        GL30.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, STRIDE, 24);
        
        // MidTexCoord (Parallax) usually copies UV0
        GL30.glEnableVertexAttribArray(7);
        GL30.glVertexAttribPointer(7, 2, GL11.GL_FLOAT, false, STRIDE, 24);

        // 4. Tangent (vec4) -> Offset 32
        GL30.glEnableVertexAttribArray(8);
        GL30.glVertexAttribPointer(8, 4, GL11.GL_FLOAT, false, STRIDE, 32);
    }
}