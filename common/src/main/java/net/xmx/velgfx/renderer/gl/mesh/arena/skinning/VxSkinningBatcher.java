/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.mesh.arena.skinning;

import com.mojang.blaze3d.systems.RenderSystem;
import net.xmx.velgfx.VelGFX;
import net.xmx.velgfx.renderer.gl.VxGlState;
import net.xmx.velgfx.renderer.gl.layout.VxSkinnedVertexLayout;
import net.xmx.velgfx.renderer.gl.mesh.arena.VxArenaManager;
import net.xmx.velgfx.renderer.gl.shader.impl.VxSkinningShader;
import net.xmx.velgfx.renderer.model.VxSkinnedModel;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.List;

/**
 * Batches hardware skinning operations (Transform Feedback) to minimize OpenGL state changes.
 * <p>
 * Instead of switching shaders, VAOs, and GL states for every single model update, this batcher 
 * collects all skinning requests for the current frame and executes them in a single optimized pass 
 * before the main rendering phase begins.
 *
 * @author xI-Mx-Ix
 */
public class VxSkinningBatcher {

    private static VxSkinningBatcher instance;
    private final List<VxSkinnedModel> queue = new ArrayList<>();

    private VxSkinningBatcher() {}

    /**
     * Retrieves the global instance of the skinning batcher.
     *
     * @return The singleton instance.
     */
    public static synchronized VxSkinningBatcher getInstance() {
        if (instance == null) {
            instance = new VxSkinningBatcher();
        }
        return instance;
    }

    /**
     * Queues a model for skinning computation this frame.
     * <p>
     * The actual computation will occur when {@link #flush()} is called.
     *
     * @param model The model to process.
     */
    public void queue(VxSkinnedModel model) {
        queue.add(model);
    }

    /**
     * Executes all pending skinning operations in the queue.
     * <p>
     * This method saves the current OpenGL state, sets up the global skinning environment 
     * (Shader, Input VAO, Rasterizer State), iterates through the queued models to perform 
     * Transform Feedback, and finally restores the original OpenGL state.
     */
    public void flush() {
        if (queue.isEmpty()) return;

        RenderSystem.assertOnRenderThread();

        // 1. Save critical OpenGL state (Program, VAO, Texture Unit, etc.)
        VxGlState.saveCurrentState();

        try {
            VxSkinningShader shader = VelGFX.getShaderManager().getSkinningShader();
            VxSkinningArena resultArena = VxSkinningArena.getInstance();

            // 2. Setup Global Skinning State
            shader.bind();
            
            // Disable rasterization because we are only processing vertices via Transform Feedback, 
            // not drawing pixels to the screen.
            GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);

            // Bind the shared SOURCE VAO (Input Data).
            // All skinned models reside in the same SkinnedVertexLayout Arena.
            // Binding it once here avoids redundant binds per model.
            VxArenaManager.getInstance().getArena(VxSkinnedVertexLayout.getInstance()).bindVao();

            // 3. Process All Models
            for (VxSkinnedModel model : queue) {
                // Dispatch the specific compute draw call for this model
                model.dispatchCompute(shader);
            }

            // 4. Cleanup Global State
            resultArena.unbindFeedback();
            GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);

        } finally {
            queue.clear();
            // 5. Restore OpenGL state completely to prevent conflicts with Vanilla/Iris
            VxGlState.restorePreviousState();
        }
    }

    /**
     * Clears the queue without processing. Should be called at the start of a frame 
     * to ensure no stale references persist.
     */
    public void reset() {
        queue.clear();
    }
}