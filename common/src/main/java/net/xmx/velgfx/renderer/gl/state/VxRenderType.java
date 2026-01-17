/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.state;

/**
 * Defines the high-level rendering category for a material.
 * This determines the render queue bucket and sorting strategy.
 *
 * @author xI-Mx-Ix
 */
public enum VxRenderType {
    /**
     * Solid geometry. Writes to depth buffer. No blending. Rendered first (Front-to-Back preferred).
     */
    OPAQUE,

    /**
     * Solid geometry with alpha testing (discard). Writes to depth buffer. No blending. Rendered after OPAQUE.
     */
    CUTOUT,

    /**
     * Transparent geometry. Read-only depth buffer (usually). Blending enabled. Rendered last (Back-to-Front).
     */
    TRANSLUCENT
}