/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.morph;

/**
 * Defines the static metadata for a single Morph Target associated with a specific mesh.
 * <p>
 * This record maps a logical name (e.g., "Smile") to its physical location within the
 * global {@link net.xmx.velgfx.renderer.gl.mesh.arena.skinning.VxMorphTextureAtlas}.
 *
 * @param name            The name of the morph target.
 * @param index           The logical index in the glTF file (used for animation channels).
 * @param tboOffsetTexels The absolute start offset in the GPU Texture Buffer (in Texels).
 * @author xI-Mx-Ix
 */
public record VxMorphTarget(
        String name,
        int index,
        int tboOffsetTexels
) {}