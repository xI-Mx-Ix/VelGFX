/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.state;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import java.util.Objects;

/**
 * Encapsulates the OpenGL blending state required for a specific render pass.
 * <p>
 * This class manages the source and destination blend factors, the blend equation,
 * and the write-masking behavior (opaque vs transparent). It ensures that redundant
 * GL state changes are minimized by caching the last applied mode locally.
 *
 * @author xI-Mx-Ix
 */
public class VxBlendMode {

    private static VxBlendMode lastApplied = null;

    /**
     * Standard Opaque mode: No blending, overwrites color buffer.
     */
    public static final VxBlendMode OPAQUE = new VxBlendMode(false, true, 1, 0, 1, 0, GL14.GL_FUNC_ADD);

    /**
     * Standard Alpha Blending: SrcAlpha / OneMinusSrcAlpha.
     */
    public static final VxBlendMode ALPHA = new VxBlendMode(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL14.GL_FUNC_ADD);

    private final boolean separateBlend;
    private final boolean opaque;
    private final int srcColorFactor;
    private final int dstColorFactor;
    private final int srcAlphaFactor;
    private final int dstAlphaFactor;
    private final int blendFunc;

    /**
     * Internal constructor for fully defined blend modes.
     */
    private VxBlendMode(boolean separateBlend, boolean opaque, int srcColorFactor, int dstColorFactor, int srcAlphaFactor, int dstAlphaFactor, int blendFunc) {
        this.separateBlend = separateBlend;
        this.opaque = opaque;
        this.srcColorFactor = srcColorFactor;
        this.dstColorFactor = dstColorFactor;
        this.srcAlphaFactor = srcAlphaFactor;
        this.dstAlphaFactor = dstAlphaFactor;
        this.blendFunc = blendFunc;
    }

    /**
     * Creates a standard blend mode with identical color and alpha factors.
     *
     * @param srcFactor The source factor.
     * @param dstFactor The destination factor.
     * @param blendFunc The blend equation.
     */
    public VxBlendMode(int srcFactor, int dstFactor, int blendFunc) {
        this(false, false, srcFactor, dstFactor, srcFactor, dstFactor, blendFunc);
    }

    /**
     * Applies this blend mode to the current RenderSystem state.
     * <p>
     * Checks against the last applied mode to avoid unnecessary JNI calls.
     */
    public void apply() {
        if (!this.equals(lastApplied)) {
            // Check if we strictly need to toggle the GL capability
            if (lastApplied == null || this.opaque != lastApplied.isOpaque()) {
                lastApplied = this;
                if (this.opaque) {
                    RenderSystem.disableBlend();
                    return;
                }
                RenderSystem.enableBlend();
            } else {
                lastApplied = this;
            }

            RenderSystem.blendEquation(this.blendFunc);
            if (this.separateBlend) {
                RenderSystem.blendFuncSeparate(this.srcColorFactor, this.dstColorFactor, this.srcAlphaFactor, this.dstAlphaFactor);
            } else {
                RenderSystem.blendFunc(this.srcColorFactor, this.dstColorFactor);
            }
        }
    }

    /**
     * Resets the cached state. Should be called at the start of a frame or after external renderers have run.
     */
    public static void resetState() {
        lastApplied = null;
    }

    public boolean isOpaque() {
        return opaque;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VxBlendMode that = (VxBlendMode) o;
        return separateBlend == that.separateBlend &&
                opaque == that.opaque &&
                srcColorFactor == that.srcColorFactor &&
                dstColorFactor == that.dstColorFactor &&
                srcAlphaFactor == that.srcAlphaFactor &&
                dstAlphaFactor == that.dstAlphaFactor &&
                blendFunc == that.blendFunc;
    }

    @Override
    public int hashCode() {
        return Objects.hash(separateBlend, opaque, srcColorFactor, dstColorFactor, srcAlphaFactor, dstAlphaFactor, blendFunc);
    }
}