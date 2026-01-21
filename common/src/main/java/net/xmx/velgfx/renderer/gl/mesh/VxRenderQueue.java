/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.mesh;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.xmx.velgfx.renderer.VelGFX;
import net.xmx.velgfx.renderer.gl.VxDrawCommand;
import net.xmx.velgfx.renderer.gl.VxGlState;
import net.xmx.velgfx.renderer.gl.VxIndexBuffer;
import net.xmx.velgfx.renderer.gl.material.VxMaterial;
import net.xmx.velgfx.renderer.gl.state.VxBlendMode;
import net.xmx.velgfx.renderer.gl.state.VxRenderType;
import net.xmx.velgfx.renderer.util.VxShaderDetector;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A central rendering queue that batches mesh draw calls to optimize performance.
 * <p>
 * This class implements a "Structure of Arrays" (SoA) architecture. Instead of holding a list of
 * wrapper objects (where each object contains a mesh, a matrix, and light data), we store these
 * properties in separate parallel arrays. This layout improves CPU cache locality because the
 * renderer often needs to iterate over just matrices or just meshes sequentially.
 * <p>
 * The queue automatically categorizes objects into three buckets:
 * 1. Opaque: Solid objects, drawn first to populate the depth buffer.
 * 2. Cutout: Objects with holes (alpha test), drawn after opaque.
 * 3. Translucent: Semi-transparent objects, drawn last and sorted by distance.
 *
 * @author xI-Mx-Ix
 */
public class VxRenderQueue {

    /**
     * The singleton instance of the render queue.
     */
    private static VxRenderQueue instance;

    /**
     * The initial size of the internal arrays.
     * Set to 1024 to prevent frequent resizing operations during the first few seconds of gameplay.
     */
    private static final int INITIAL_CAPACITY = 1024;

    /**
     * The number of items currently active in the queue for this frame.
     */
    private int totalCount = 0;

    /**
     * The current maximum size of the allocated arrays.
     */
    private int capacity = INITIAL_CAPACITY;

    // -- Data Storage (Structure of Arrays) --

    /**
     * Stores the mesh reference for each queued item.
     */
    private IVxRenderableMesh[] meshes;

    /**
     * Stores the Model Matrix (Position/Rotation/Scale) for each item.
     */
    private Matrix4f[] modelMatrices;

    /**
     * Stores the Normal Matrix (for correct lighting direction) for each item.
     */
    private Matrix3f[] normalMatrices;

    /**
     * Stores the packed light coordinates (SkyLight and BlockLight combined) for each item.
     */
    private int[] packedLights;

    // -- Bucket Indices --

    /**
     * Holds indices pointing to the data arrays for solid objects.
     */
    private final ArrayList<Integer> opaqueIndices = new ArrayList<>(INITIAL_CAPACITY);

    /**
     * Holds indices for objects that require alpha testing (discarding pixels).
     */
    private final ArrayList<Integer> cutoutIndices = new ArrayList<>(INITIAL_CAPACITY / 4);

    /**
     * Holds indices for semi-transparent objects that require blending.
     */
    private final ArrayList<Integer> translucentIndices = new ArrayList<>(INITIAL_CAPACITY / 4);

    // -- Reusable Helpers --

    /**
     * A pre-allocated buffer for uploading 3x3 matrices to OpenGL to avoid per-frame allocation.
     */
    private static final FloatBuffer MATRIX_BUFFER_9 = BufferUtils.createFloatBuffer(9);

    /**
     * The attribute location for the secondary UV channel (lightmap) in Minecraft shaders.
     */
    private static final int AT_UV2 = 4;

    /**
     * Standard vanilla light direction 0, used to correct lighting on custom models.
     */
    private static final Vector3f VANILLA_LIGHT0 = new Vector3f(0.2f, 1.0f, -0.7f).normalize();

    /**
     * Standard vanilla light direction 1.
     */
    private static final Vector3f VANILLA_LIGHT1 = new Vector3f(-0.2f, 1.0f, 0.7f).normalize();

    // -- Math Scratch Objects --
    // These objects are used for temporary calculations to avoid creating new object instances (garbage) every frame.

    /**
     * Scratch matrix for calculating normal transformations.
     */
    private static final Matrix3f AUX_NORMAL_MAT = new Matrix3f();

    /**
     * Scratch vector for transforming Light 0 direction.
     */
    private static final Vector3f AUX_LIGHT0 = new Vector3f();

    /**
     * Scratch vector for transforming Light 1 direction.
     */
    private static final Vector3f AUX_LIGHT1 = new Vector3f();

    /**
     * Scratch matrix for combined Model-View transformation.
     */
    private static final Matrix4f AUX_MODEL_VIEW = new Matrix4f();

    /**
     * Scratch matrix for View-Normal transformation.
     */
    private static final Matrix3f AUX_NORMAL_VIEW = new Matrix3f();

    /**
     * Scratch matrix to hold the rotation component of the View Matrix.
     */
    private static final Matrix3f VIEW_ROTATION = new Matrix3f();

    // -- Caching --

    /**
     * Tracks the last active shader program ID to detect when the shader changes (e.g. enabling a shader pack).
     * This allows us to re-query uniform locations only when necessary.
     */
    private int cachedProgramId = -1;

    /**
     * The texture unit index offset for the Normal Map.
     * <p>
     * This value is determined dynamically by querying the 'normals' uniform from the active shader.
     * It represents the texture slot (e.g., 1 for GL_TEXTURE1) where the shader expects the normal map.
     * Initialized to -1.
     */
    private int texUnitNormal = -1;

    /**
     * The texture unit index offset for the Specular Map (Metallic/Roughness).
     * <p>
     * This value is determined dynamically by querying the 'specular' uniform from the active shader.
     * It represents the texture slot (e.g., 3 for GL_TEXTURE3) where the shader expects PBR data.
     * Initialized to -1.
     */
    private int texUnitSpecular = -1;

    /**
     * Cached uniform location for the Normal Matrix (mat3).
     * Used to transform normals from Model Space to View Space correctly.
     */
    private int locNormalMat = -1;

    /**
     * Private constructor to enforce the Singleton pattern.
     * Initializes the arrays and pre-allocates the matrix objects to avoid null checks later.
     */
    private VxRenderQueue() {
        this.meshes = new IVxRenderableMesh[INITIAL_CAPACITY];
        this.modelMatrices = new Matrix4f[INITIAL_CAPACITY];
        this.normalMatrices = new Matrix3f[INITIAL_CAPACITY];
        this.packedLights = new int[INITIAL_CAPACITY];

        // We pre-fill the arrays with empty matrix objects.
        // During the render loop, we will use .set() to update them instead of "new Matrix4f()".
        for (int i = 0; i < INITIAL_CAPACITY; i++) {
            this.modelMatrices[i] = new Matrix4f();
            this.normalMatrices[i] = new Matrix3f();
        }
    }

    /**
     * Retrieves the global instance of the render queue.
     *
     * @return The VxRenderQueue singleton.
     */
    public static synchronized VxRenderQueue getInstance() {
        if (instance == null) {
            instance = new VxRenderQueue();
        }
        return instance;
    }

    /**
     * Resets the queue state. This must be called exactly once at the beginning of a frame.
     * It clears the bucket lists and resets the item counter, effectively emptying the queue
     * without deleting the underlying allocated arrays.
     */
    public void reset() {
        this.totalCount = 0;
        this.opaqueIndices.clear();
        this.cutoutIndices.clear();
        this.translucentIndices.clear();
        // Also reset the blend mode cache to ensure a clean state for the new frame.
        VxBlendMode.resetState();
    }

    /**
     * Adds a new mesh to the render queue for the current frame.
     * The method snapshots the current transformation from the PoseStack and categorizes
     * the mesh based on its material type (Opaque, Cutout, or Translucent).
     *
     * @param mesh        The mesh object to be rendered. If null or deleted, it is ignored.
     * @param poseStack   The matrix stack containing the current Model and Normal transformations.
     * @param packedLight The lightmap coordinates (combined sky and block light) for this object.
     */
    public void add(IVxRenderableMesh mesh, PoseStack poseStack, int packedLight) {
        if (mesh != null && !mesh.isDeleted()) {
            // Make sure we have enough room in the arrays
            ensureCapacity(totalCount + 1);

            // 1. Store the mesh reference
            this.meshes[totalCount] = mesh;

            // 2. Copy the matrices. We use .set() to copy values into the pre-allocated objects.
            // This avoids creating a new object on the heap for every single rendered item.
            this.modelMatrices[totalCount].set(poseStack.last().pose());
            this.normalMatrices[totalCount].set(poseStack.last().normal());

            // 3. Store the lighting data
            this.packedLights[totalCount] = packedLight;

            // 4. Sort into the correct bucket
            // We look at the first draw command's material to decide the bucket.
            List<VxDrawCommand> cmds = mesh.getDrawCommands();
            if (!cmds.isEmpty()) {
                VxRenderType type = cmds.get(0).material.renderType;
                switch (type) {
                    case OPAQUE -> this.opaqueIndices.add(totalCount);
                    case CUTOUT -> this.cutoutIndices.add(totalCount);
                    case TRANSLUCENT -> this.translucentIndices.add(totalCount);
                }
            } else {
                // Fallback for empty meshes (though they won't render anything anyway)
                this.opaqueIndices.add(totalCount);
            }

            totalCount++;
        }
    }

    /**
     * internal method to resize the storage arrays when the capacity is exceeded.
     * It increases the size by roughly 50% to balance memory usage and resize frequency.
     *
     * @param minCapacity The absolute minimum capacity required to fit the new item.
     */
    private void ensureCapacity(int minCapacity) {
        if (minCapacity > capacity) {
            int newCapacity = Math.max(capacity * 3 / 2, minCapacity);

            // Resize the simple arrays
            this.meshes = Arrays.copyOf(this.meshes, newCapacity);
            this.packedLights = Arrays.copyOf(this.packedLights, newCapacity);

            Matrix4f[] newModelMats = new Matrix4f[newCapacity];
            Matrix3f[] newNormalMats = new Matrix3f[newCapacity];

            // Copy the existing matrix references to the new arrays
            System.arraycopy(this.modelMatrices, 0, newModelMats, 0, capacity);
            System.arraycopy(this.normalMatrices, 0, newNormalMats, 0, capacity);

            // Important: We must allocate new matrix objects for the *newly added* slots.
            // The old slots keep their existing objects to preserve references.
            for (int i = capacity; i < newCapacity; i++) {
                newModelMats[i] = new Matrix4f();
                newNormalMats[i] = new Matrix3f();
            }

            this.modelMatrices = newModelMats;
            this.normalMatrices = newNormalMats;
            this.capacity = newCapacity;
        }
    }

    /**
     * Renders all opaque and cutout (alpha-tested) meshes.
     * This method is typically called early in the rendering pipeline, before transparency.
     *
     * @param viewMatrix       The camera's view matrix (World Space -> Camera Space).
     * @param projectionMatrix The camera's projection matrix (Camera Space -> Clip Space).
     */
    public void flushOpaque(Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        if (opaqueIndices.isEmpty() && cutoutIndices.isEmpty()) return;
        // The last parameter is null because sorting is not required for opaque objects
        flushInternal(viewMatrix, projectionMatrix, false, null);
    }

    /**
     * Renders all translucent (semi-transparent) meshes.
     * This method performs CPU-side sorting of the objects (Back-to-Front) based on the
     * camera position to ensure proper alpha blending.
     *
     * @param viewMatrix       The camera's view matrix.
     * @param projectionMatrix The camera's projection matrix.
     * @param cameraPosition   The absolute world position of the camera, used for distance sorting.
     */
    public void flushTranslucent(Matrix4f viewMatrix, Matrix4f projectionMatrix, Vector3f cameraPosition) {
        if (translucentIndices.isEmpty()) return;

        // Sort the translucent indices: Farthest objects must be drawn first.
        translucentIndices.sort((idx1, idx2) -> {
            Matrix4f m1 = modelMatrices[idx1];
            Matrix4f m2 = modelMatrices[idx2];

            // Extract translation (position) from the model matrices (m30, m31, m32)
            float distSq1 = cameraPosition.distanceSquared(m1.m30(), m1.m31(), m1.m32());
            float distSq2 = cameraPosition.distanceSquared(m2.m30(), m2.m31(), m2.m32());

            // Descending sort order
            return Float.compare(distSq2, distSq1);
        });

        flushInternal(viewMatrix, projectionMatrix, true, null);
    }

    /**
     * The core dispatcher that sets up the environment and calls the correct rendering routine.
     * It handles saving/restoring the OpenGL state to avoid conflicts with vanilla Minecraft.
     *
     * @param viewMatrix        The view matrix.
     * @param projectionMatrix  The projection matrix.
     * @param renderTranslucent True if we are currently rendering the transparent pass.
     * @param cameraPos         Camera position (currently unused in this internal method, but kept for signature consistency).
     */
    private void flushInternal(Matrix4f viewMatrix, Matrix4f projectionMatrix, boolean renderTranslucent, Vector3f cameraPos) {
        RenderSystem.assertOnRenderThread();

        // Save the current GL state (VAO bindings, buffer bindings) so we can restore it later.
        VxGlState.saveCurrentState();

        try {
            // Extract the rotation component of the view matrix.
            // We need this to rotate the normal vectors from World Space into View Space.
            viewMatrix.get3x3(VIEW_ROTATION);

            // Check if a Shaderpack (Iris/Optifine) is active and branch accordingly.
            if (VxShaderDetector.isShaderpackActive()) {
                renderBatchShaderpack(viewMatrix, projectionMatrix, renderTranslucent);
            } else {
                renderBatchVanilla(viewMatrix, projectionMatrix, renderTranslucent);
            }
        } finally {
            // Always restore the previous state, even if rendering crashes.
            VxGlState.restorePreviousState();
        }
    }

    /**
     * Handles rendering for the standard Minecraft pipeline (Vanilla).
     * It manages shader switching between "Solid", "Cutout", and "Translucent".
     *
     * @param viewMatrix        The view matrix.
     * @param projectionMatrix  The projection matrix.
     * @param renderTranslucent Whether this pass is for translucent objects.
     */
    private void renderBatchVanilla(Matrix4f viewMatrix, Matrix4f projectionMatrix, boolean renderTranslucent) {
        List<Integer> bucket = null;
        ShaderInstance shader;

        if (renderTranslucent) {
            // Setup for Translucent pass
            bucket = translucentIndices;

            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            // Disable writing to depth buffer for transparent objects to prevent self-occlusion artifacts
            RenderSystem.depthMask(false);

            shader = setupCommonRenderState(projectionMatrix, GameRenderer.getRendertypeEntityTranslucentShader());
        } else {
            // Setup for Opaque pass
            RenderSystem.disableBlend();
            // Enable writing to depth buffer
            RenderSystem.depthMask(true);

            shader = setupCommonRenderState(projectionMatrix, GameRenderer.getRendertypeEntitySolidShader());
        }

        if (shader == null) return;

        // Bind the lightmap texture (Texture 2 in Minecraft)
        Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();

        // Assign all standard samplers
        for (int i = 0; i < 12; ++i) {
            shader.setSampler("Sampler" + i, RenderSystem.getShaderTexture(i));
        }

        // Set the default overlay color (white/neutral)
        GL30.glVertexAttrib4f(1, 1.0f, 1.0f, 1.0f, 1.0f);

        if (!renderTranslucent) {
            // 1. Render solids
            renderBucketVanilla(opaqueIndices, shader, viewMatrix, VxRenderType.OPAQUE);

            // 2. Render cutouts
            if (!cutoutIndices.isEmpty()) {
                // We must switch to the cutout shader because it has the "discard" instruction for alpha testing
                ShaderInstance cutoutShader = GameRenderer.getRendertypeEntityCutoutShader();
                if (cutoutShader != null) {
                    RenderSystem.setShader(() -> cutoutShader);
                    setupShaderUniforms(cutoutShader, projectionMatrix);
                    renderBucketVanilla(cutoutIndices, cutoutShader, viewMatrix, VxRenderType.CUTOUT);
                }
            }
        } else {
            // 3. Render translucent
            renderBucketVanilla(bucket, shader, viewMatrix, VxRenderType.TRANSLUCENT);
        }

        // Restore default GL states
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        shader.clear();
    }

    /**
     * The inner loop that processes a list of indices for Vanilla rendering.
     * This method handles the complex math required to make custom meshes work with vanilla lighting.
     *
     * @param bucket      The list of object indices to render.
     * @param shader      The active shader instance.
     * @param viewMatrix  The camera view matrix.
     * @param currentType The render type we are currently processing (used for filtering).
     */
    private void renderBucketVanilla(List<Integer> bucket, ShaderInstance shader, Matrix4f viewMatrix, VxRenderType currentType) {
        boolean isCullingEnabled = true;

        for (Integer i : bucket) {
            IVxRenderableMesh mesh = this.meshes[i];
            Matrix4f modelMat = this.modelMatrices[i];
            Matrix3f normalMat = this.normalMatrices[i];
            int packedLight = this.packedLights[i];

            // -- Math Calculation --

            // 1. Calculate ModelView Matrix: Transforms Local -> View Space
            AUX_MODEL_VIEW.set(viewMatrix).mul(modelMat);

            // 2. Calculate Normal Matrix: Transforms Local Normals -> View Space Normals
            AUX_NORMAL_VIEW.set(VIEW_ROTATION).mul(normalMat);

            // 3. Lighting Correction:
            // The vanilla shader expects light directions to be pre-transformed by the inverse
            // of the normal matrix. We compute this by transposing the normal-view matrix
            // and then transforming the world-space light directions through it.
            AUX_NORMAL_MAT.set(AUX_NORMAL_VIEW).transpose();
            AUX_LIGHT0.set(VANILLA_LIGHT0).mul(AUX_NORMAL_MAT);
            AUX_LIGHT1.set(VANILLA_LIGHT1).mul(AUX_NORMAL_MAT);

            // Update uniforms
            if (shader.LIGHT0_DIRECTION != null) shader.LIGHT0_DIRECTION.set(AUX_LIGHT0);
            if (shader.LIGHT1_DIRECTION != null) shader.LIGHT1_DIRECTION.set(AUX_LIGHT1);
            if (shader.MODEL_VIEW_MATRIX != null) shader.MODEL_VIEW_MATRIX.set(AUX_MODEL_VIEW);

            // Send the 3x3 Normal Matrix
            int normalMatrixLocation = Uniform.glGetUniformLocation(shader.getId(), "NormalMat");
            if (normalMatrixLocation != -1) {
                MATRIX_BUFFER_9.clear();
                AUX_NORMAL_VIEW.get(MATRIX_BUFFER_9);
                RenderSystem.glUniformMatrix3(normalMatrixLocation, false, MATRIX_BUFFER_9);
            }

            // Prepare the mesh VAO
            mesh.setupVaoState();

            // Pass the lightmap coordinates. In vanilla, this is often passed as a vertex attribute (UV2).
            GL30.glDisableVertexAttribArray(AT_UV2);
            GL30.glVertexAttribI2i(AT_UV2, packedLight & 0xFFFF, packedLight >> 16);

            // Process draw commands
            for (VxDrawCommand rawCommand : mesh.getDrawCommands()) {
                VxDrawCommand command = mesh.resolveCommand(rawCommand);
                VxMaterial mat = command.material;

                // Skip parts of the mesh that don't match the current render pass
                if (mat.renderType != currentType) continue;

                // Handle Face Culling (Double-sided vs Single-sided)
                boolean shouldCull = !mat.doubleSided;
                if (shouldCull != isCullingEnabled) {
                    if (shouldCull) RenderSystem.enableCull();
                    else RenderSystem.disableCull();
                    isCullingEnabled = shouldCull;
                }

                // Apply material blend mode
                mat.blendMode.apply();

                // Prepare sampler uniform
                shader.setSampler("Sampler0", mat.albedoMapGlId);

                // Apply color tint
                if (shader.COLOR_MODULATOR != null) {
                    shader.COLOR_MODULATOR.set(mat.baseColorFactor);
                }

                // Apply shader state
                shader.apply();

                if (mat.albedoMapGlId != -1) {
                    GL13.glActiveTexture(GL13.GL_TEXTURE0);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, mat.albedoMapGlId);
                }

                // Draw Elements
                GL32.glDrawElementsBaseVertex(
                        GL30.GL_TRIANGLES,
                        command.indexCount,
                        VxIndexBuffer.GL_INDEX_TYPE,
                        command.indexOffsetBytes,
                        command.baseVertex
                );
            }

            // Cleanup attribute
            GL30.glEnableVertexAttribArray(AT_UV2);
        }

        // Reset culling state if it was changed
        if (!isCullingEnabled) {
            RenderSystem.enableCull();
        }
    }

    /**
     * Handles rendering when an external shader pack is active.
     * <p>
     * This method inspects the current OpenGL shader program to determine where specific
     * textures should be bound. It looks for specific uniform names ("normals", "specular")
     * and retrieves their integer values to determine the correct Texture Unit offset.
     *
     * @param viewMatrix        The view matrix (World -> Camera).
     * @param projectionMatrix  The projection matrix (Camera -> Clip).
     * @param renderTranslucent Whether this pass is for translucent objects.
     */
    private void renderBatchShaderpack(Matrix4f viewMatrix, Matrix4f projectionMatrix, boolean renderTranslucent) {
        ShaderInstance shader = setupCommonRenderState(projectionMatrix, GameRenderer.getRendertypeEntitySolidShader());
        if (shader == null) return;

        // Reset standard samplers (Unit 0-11)
        for (int i = 0; i < 12; ++i) {
            shader.setSampler("Sampler" + i, RenderSystem.getShaderTexture(i));
        }
        shader.apply();

        // Reset base overlay color
        GL30.glVertexAttrib4f(1, 1.0f, 1.0f, 1.0f, 1.0f);

        // Dynamic Shader Analysis
        // We query the shader program directly to find out which Texture Unit
        // corresponds to the 'normals' and 'specular' uniforms.
        int currentProgramId = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);

        if (currentProgramId != this.cachedProgramId) {
            this.cachedProgramId = currentProgramId;

            // 1. Find Uniform Locations by Name
            int locNormals = GL20.glGetUniformLocation(currentProgramId, "normals");
            int locSpecular = GL20.glGetUniformLocation(currentProgramId, "specular");

            this.texUnitNormal = (locNormals != -1) ? GL20.glGetUniformi(currentProgramId, locNormals) : -1;
            this.texUnitSpecular = (locSpecular != -1) ? GL20.glGetUniformi(currentProgramId, locSpecular) : -1;

            // 3. Find Normal Matrix Uniform
            this.locNormalMat = Uniform.glGetUniformLocation(currentProgramId, "iris_NormalMat");
            if (this.locNormalMat == -1) {
                this.locNormalMat = Uniform.glGetUniformLocation(currentProgramId, "NormalMat");
            }
            if (this.locNormalMat == -1) {
                this.locNormalMat = Uniform.glGetUniformLocation(currentProgramId, "normalMatrix");
            }
        }

        if (renderTranslucent) {
            // Translucent pass: Enable blending and disable depth writing
            RenderSystem.enableBlend();
            RenderSystem.depthMask(false);

            renderBucketShaderpack(translucentIndices, shader, viewMatrix, VxRenderType.TRANSLUCENT);

            // Restore state
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        } else {
            // Opaque / G-Buffer pass
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);

            renderBucketShaderpack(opaqueIndices, shader, viewMatrix, VxRenderType.OPAQUE);
            renderBucketShaderpack(cutoutIndices, shader, viewMatrix, VxRenderType.CUTOUT);
        }

        shader.clear();
    }

    /**
     * The inner loop for external shader rendering.
     * <p>
     * Iterates over the batched meshes and binds textures using the dynamically discovered
     * texture unit offsets. This ensures the shader reads the correct maps regardless of
     * how the shader pack author assigned the slots.
     *
     * @param bucket      The list of object indices to render.
     * @param shader      The active shader instance.
     * @param viewMatrix  The camera view matrix.
     * @param currentType The render type we are currently processing.
     */
    private void renderBucketShaderpack(List<Integer> bucket, ShaderInstance shader, Matrix4f viewMatrix, VxRenderType currentType) {
        boolean isCullingEnabled = true;

        for (Integer i : bucket) {
            IVxRenderableMesh mesh = this.meshes[i];
            Matrix4f modelMat = this.modelMatrices[i];
            Matrix3f normalMat = this.normalMatrices[i];
            int packedLight = this.packedLights[i];

            // 1. Calculate Matrices
            AUX_MODEL_VIEW.set(viewMatrix).mul(modelMat);
            AUX_NORMAL_VIEW.set(VIEW_ROTATION).mul(normalMat);

            // Upload ModelView Matrix
            if (shader.MODEL_VIEW_MATRIX != null) shader.MODEL_VIEW_MATRIX.set(AUX_MODEL_VIEW);

            // Upload Normal Matrix (if location was found)
            if (this.locNormalMat != -1) {
                MATRIX_BUFFER_9.clear();
                AUX_NORMAL_VIEW.get(MATRIX_BUFFER_9);
                // Ensure buffer is flipped for reading to prevent GL_INVALID_OPERATION
                MATRIX_BUFFER_9.flip();
                RenderSystem.glUniformMatrix3(this.locNormalMat, false, MATRIX_BUFFER_9);
            }

            // Prepare the mesh VAO
            mesh.setupVaoState();

            // Pass the lightmap coordinates via attribute
            GL30.glDisableVertexAttribArray(AT_UV2);
            GL30.glVertexAttribI2i(AT_UV2, packedLight & 0xFFFF, packedLight >> 16);

            for (VxDrawCommand rawCommand : mesh.getDrawCommands()) {
                VxDrawCommand command = mesh.resolveCommand(rawCommand);
                VxMaterial mat = command.material;

                // Skip non-matching render types
                if (mat.renderType != currentType) continue;

                // Handle Face Culling
                boolean shouldCull = !mat.doubleSided;
                if (shouldCull != isCullingEnabled) {
                    if (shouldCull) RenderSystem.enableCull();
                    else RenderSystem.disableCull();
                    isCullingEnabled = shouldCull;
                }

                // Since factors are baked into textures, the modulator is set to neutral white
                if (shader.COLOR_MODULATOR != null) shader.COLOR_MODULATOR.set(1.0f, 1.0f, 1.0f, 1.0f);

                mat.blendMode.apply();
                shader.apply();

                // -- Bind Textures using Discovered Offsets --

                // 0. Albedo (Base Color + Baked AO + Baked Emission) -> Always Unit 0
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                if (mat.albedoMapGlId != -1) {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, mat.albedoMapGlId);
                } else {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                }

                // 1. Normal Map -> Targeted Unit
                if (this.texUnitNormal != -1 && mat.normalMapGlId != -1) {
                    GL13.glActiveTexture(GL13.GL_TEXTURE0 + this.texUnitNormal);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, mat.normalMapGlId);
                }

                // 2. Specular Map (Packed Smoothness, Metallic, Emission Strength) -> Targeted Unit
                if (this.texUnitSpecular != -1 && mat.specularMapGlId != -1) {
                    GL13.glActiveTexture(GL13.GL_TEXTURE0 + this.texUnitSpecular);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, mat.specularMapGlId);
                }

                // Restore active texture to 0 for vanilla compatibility
                GL13.glActiveTexture(GL13.GL_TEXTURE0);

                // Draw Elements
                GL32.glDrawElementsBaseVertex(
                        GL30.GL_TRIANGLES,
                        command.indexCount,
                        VxIndexBuffer.GL_INDEX_TYPE,
                        command.indexOffsetBytes,
                        command.baseVertex
                );
            }

            // Cleanup attribute
            GL30.glEnableVertexAttribArray(AT_UV2);
        }

        // Restore culling state
        if (!isCullingEnabled) {
            RenderSystem.enableCull();
        }
    }

    /**
     * Initializes the common OpenGL state and standard uniforms required by all render paths.
     * This ensures depth testing is on and culling is set to default.
     *
     * @param projectionMatrix The projection matrix to upload.
     * @param targetShader     The shader instance to configure.
     * @return The configured shader instance, or null if initialization failed.
     */
    private ShaderInstance setupCommonRenderState(Matrix4f projectionMatrix, ShaderInstance targetShader) {
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.enableCull();

        if (targetShader == null) {
            VelGFX.LOGGER.error("Failed to render mesh batch: RendertypeEntitySolidShader is null.");
            return null;
        }

        RenderSystem.setShader(() -> targetShader);
        setupShaderUniforms(targetShader, projectionMatrix);
        return targetShader;
    }

    /**
     * Helper method to upload all standard Minecraft uniforms (Projection, Fog, GameTime, ScreenSize)
     * to the provided shader instance.
     *
     * @param shader           The shader to update.
     * @param projectionMatrix The current projection matrix.
     */
    private void setupShaderUniforms(ShaderInstance shader, Matrix4f projectionMatrix) {
        if (shader.PROJECTION_MATRIX != null) shader.PROJECTION_MATRIX.set(projectionMatrix);
        if (shader.TEXTURE_MATRIX != null) shader.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
        if (shader.COLOR_MODULATOR != null) shader.COLOR_MODULATOR.set(RenderSystem.getShaderColor());

        // Fog Uniforms
        if (shader.FOG_START != null) shader.FOG_START.set(RenderSystem.getShaderFogStart());
        if (shader.FOG_END != null) shader.FOG_END.set(RenderSystem.getShaderFogEnd());
        if (shader.FOG_COLOR != null) shader.FOG_COLOR.set(RenderSystem.getShaderFogColor());
        if (shader.FOG_SHAPE != null) shader.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());

        // Misc Uniforms
        if (shader.GAME_TIME != null) shader.GAME_TIME.set(RenderSystem.getShaderGameTime());
        if (shader.GLINT_ALPHA != null) shader.GLINT_ALPHA.set(RenderSystem.getShaderGlintAlpha());

        if (shader.SCREEN_SIZE != null) {
            Window window = Minecraft.getInstance().getWindow();
            shader.SCREEN_SIZE.set((float) window.getWidth(), (float) window.getHeight());
        }

        RenderSystem.setupShaderLights(shader);
    }
}