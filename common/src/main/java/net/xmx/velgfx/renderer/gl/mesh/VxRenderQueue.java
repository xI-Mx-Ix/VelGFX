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
import net.xmx.velgfx.renderer.gl.state.VxBlendMode;
import net.xmx.velgfx.renderer.gl.material.VxMaterial;
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
 * A high-performance render queue that batches render calls to minimize state changes
 * and object allocations.
 * <p>
 * This class implements a <b>Structure of Arrays (SoA)</b> architecture. Instead of storing
 * a list of objects (where each object contains its matrix, light, mesh, etc.), we store
 * parallel arrays for each property. This improves CPU cache locality during iteration.
 * <p>
 * <b>Rendering Pipeline:</b>
 * The queue classifies meshes into three buckets:
 * <ol>
 *     <li><b>Opaque:</b> Solid geometry. Rendered first, front-to-back (implicitly). Writes to depth buffer.</li>
 *     <li><b>Cutout:</b> Alpha-tested geometry (e.g., grates). Rendered after Opaque. Uses discard in shader.</li>
 *     <li><b>Translucent:</b> Semi-transparent geometry (e.g., glass). Rendered last. <b>Sorted Back-to-Front</b> relative to the camera. Read-only depth buffer.</li>
 * </ol>
 * <p>
 * This class is a Singleton to ensure global access from any mesh entity and centralized batch management.
 *
 * @author xI-Mx-Ix
 */
public class VxRenderQueue {

    /**
     * The singleton instance of the queue.
     */
    private static VxRenderQueue instance;

    /**
     * Initial capacity for the internal SoA arrays.
     * Starts at 1024 objects to minimize early resizing.
     */
    private static final int INITIAL_CAPACITY = 1024;

    /**
     * The total number of items currently stored in the queue for this frame.
     * This acts as the pointer for the next available slot in the arrays.
     */
    private int totalCount = 0;

    /**
     * The current maximum capacity of the allocated arrays.
     * Used to detect when resizing is required.
     */
    private int capacity = INITIAL_CAPACITY;

    // --- Structure of Arrays (SoA) Storage ---

    /**
     * Array storing the mesh reference for each queued item.
     * Index {@code i} corresponds to {@code modelMatrices[i]}, etc.
     */
    private IVxRenderableMesh[] meshes;

    /**
     * Array storing the Model Matrix (Position, Rotation, Scale) for each item.
     * Transforms Local Space -> World Space.
     */
    private Matrix4f[] modelMatrices;

    /**
     * Array storing the Normal Matrix for each item.
     * Used to transform normal vectors correctly, handling non-uniform scaling.
     */
    private Matrix3f[] normalMatrices;

    /**
     * Array storing the packed light data (Sky Light / Block Light) for each item.
     * Encoded as two 16-bit integers packed into one int.
     */
    private int[] packedLights;

    // --- Render Buckets (Indices) ---

    /**
     * List of indices in the SoA arrays pointing to OPAQUE objects.
     */
    private final ArrayList<Integer> opaqueIndices = new ArrayList<>(INITIAL_CAPACITY);

    /**
     * List of indices in the SoA arrays pointing to CUTOUT objects.
     */
    private final ArrayList<Integer> cutoutIndices = new ArrayList<>(INITIAL_CAPACITY / 4);

    /**
     * List of indices in the SoA arrays pointing to TRANSLUCENT objects.
     * This list is sorted every frame before rendering.
     */
    private final ArrayList<Integer> translucentIndices = new ArrayList<>(INITIAL_CAPACITY / 4);

    // --- Reusable Buffers and Math Objects (Zero Allocation) ---

    /**
     * A direct FloatBuffer used to upload 3x3 matrices (Normal Matrix) to OpenGL uniforms.
     * Allocated once to avoid garbage collection pressure.
     */
    private static final FloatBuffer MATRIX_BUFFER_9 = BufferUtils.createFloatBuffer(9);

    /**
     * The attribute location index for the lightmap coordinates (UV2) in standard shaders.
     */
    private static final int AT_UV2 = 4;

    /**
     * Standard Minecraft light direction 0 (Top-Left-Front), normalized.
     * Used for vanilla lighting correction on custom models.
     */
    private static final Vector3f VANILLA_LIGHT0 = new Vector3f(0.2f, 1.0f, -0.7f).normalize();

    /**
     * Standard Minecraft light direction 1 (Bottom-Right-Back), normalized.
     * Used for vanilla lighting correction on custom models.
     */
    private static final Vector3f VANILLA_LIGHT1 = new Vector3f(-0.2f, 1.0f, 0.7f).normalize();

    // Scratch objects to perform math operations without `new` allocations.

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

    // --- Caching for Shader Uniforms ---

    /**
     * Stores the ID of the last active shader program to detect when the shader changes.
     */
    private int lastProgramId = -1;

    /**
     * Cached texture unit index for the normal map in the current shader.
     */
    private int cachedNormalUnit = -1;

    /**
     * Cached texture unit index for the specular map in the current shader.
     */
    private int cachedSpecularUnit = -1;

    /**
     * Cached uniform location for the normal matrix in the current shader.
     */
    private int cachedNormalMatLoc = -1;

    /**
     * Private constructor for Singleton pattern.
     * Pre-allocates the SoA arrays and the matrix objects within them to avoid allocation during render.
     */
    private VxRenderQueue() {
        this.meshes = new IVxRenderableMesh[INITIAL_CAPACITY];
        this.modelMatrices = new Matrix4f[INITIAL_CAPACITY];
        this.normalMatrices = new Matrix3f[INITIAL_CAPACITY];
        this.packedLights = new int[INITIAL_CAPACITY];

        // Pre-allocate matrix objects. We will use .set() later to avoid new allocations.
        for (int i = 0; i < INITIAL_CAPACITY; i++) {
            this.modelMatrices[i] = new Matrix4f();
            this.normalMatrices[i] = new Matrix3f();
        }
    }

    /**
     * Gets the singleton instance of the render queue.
     *
     * @return The global VxRenderQueue instance.
     */
    public static synchronized VxRenderQueue getInstance() {
        if (instance == null) {
            instance = new VxRenderQueue();
        }
        return instance;
    }

    /**
     * Resets the queue counter and clears all bucket lists.
     * <p>
     * This method must be called at the very start of the frame (e.g., via LevelRenderer mixin)
     * to prepare for new geometry. It also resets the global BlendMode state cache.
     */
    public void reset() {
        this.totalCount = 0;
        this.opaqueIndices.clear();
        this.cutoutIndices.clear();
        this.translucentIndices.clear();
        VxBlendMode.resetState();
    }

    /**
     * Queues a mesh for rendering in the current frame.
     * <p>
     * This method copies the current state from the {@link PoseStack} into the pre-allocated SoA arrays
     * and assigns the item to the correct render bucket (Opaque, Cutout, or Translucent) based on its material.
     *
     * @param mesh        The mesh to render.
     * @param poseStack   The current transformation stack containing Model Matrix and Normal Matrix.
     * @param packedLight The packed light value for the mesh.
     */
    public void add(IVxRenderableMesh mesh, PoseStack poseStack, int packedLight) {
        // Accept any mesh implementation that is not marked as deleted
        if (mesh != null && !mesh.isDeleted()) {
            ensureCapacity(totalCount + 1);

            // 1. Store Mesh Reference
            this.meshes[totalCount] = mesh;

            // 2. Copy Matrix Data (Zero Allocation using .set())
            this.modelMatrices[totalCount].set(poseStack.last().pose());
            this.normalMatrices[totalCount].set(poseStack.last().normal());

            // 3. Store Light Data
            this.packedLights[totalCount] = packedLight;

            // 4. Classify and Add to specific Bucket
            List<VxDrawCommand> cmds = mesh.getDrawCommands();
            if (!cmds.isEmpty()) {
                VxRenderType type = cmds.get(0).material.renderType;
                switch (type) {
                    case OPAQUE -> this.opaqueIndices.add(totalCount);
                    case CUTOUT -> this.cutoutIndices.add(totalCount);
                    case TRANSLUCENT -> this.translucentIndices.add(totalCount);
                }
            } else {
                // Default to Opaque if no commands are present (failsafe)
                this.opaqueIndices.add(totalCount);
            }

            totalCount++;
        }
    }

    /**
     * Ensures the internal arrays have enough space for new elements.
     * <p>
     * If capacity is exceeded, arrays grow by 50%. New matrix objects are allocated
     * only for the newly created slots to preserve existing object references.
     *
     * @param minCapacity The minimum required capacity.
     */
    private void ensureCapacity(int minCapacity) {
        if (minCapacity > capacity) {
            int newCapacity = Math.max(capacity * 3 / 2, minCapacity);

            // Resize arrays
            this.meshes = Arrays.copyOf(this.meshes, newCapacity);
            this.packedLights = Arrays.copyOf(this.packedLights, newCapacity);

            Matrix4f[] newModelMats = new Matrix4f[newCapacity];
            Matrix3f[] newNormalMats = new Matrix3f[newCapacity];

            // Copy existing matrix references to new arrays
            System.arraycopy(this.modelMatrices, 0, newModelMats, 0, capacity);
            System.arraycopy(this.normalMatrices, 0, newNormalMats, 0, capacity);

            // Allocate NEW matrix objects only for the NEW slots
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
     * Flushes the <b>Opaque</b> and <b>Cutout</b> buckets.
     * <p>
     * This should be called early in the frame (e.g., after entities are rendered).
     * It renders solid geometry first, then alpha-tested geometry.
     *
     * @param viewMatrix       The camera view matrix.
     * @param projectionMatrix The projection matrix.
     */
    public void flushOpaque(Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        if (opaqueIndices.isEmpty() && cutoutIndices.isEmpty()) return;
        flushInternal(viewMatrix, projectionMatrix, false, null);
    }

    /**
     * Flushes the <b>Translucent</b> bucket.
     * <p>
     * This should be called late in the frame (after vanilla translucent rendering).
     * It sorts the transparent objects Back-to-Front based on the provided camera position
     * before rendering to ensure correct blending.
     *
     * @param viewMatrix       The camera view matrix.
     * @param projectionMatrix The projection matrix.
     * @param cameraPosition   The world position of the camera (used for sorting).
     */
    public void flushTranslucent(Matrix4f viewMatrix, Matrix4f projectionMatrix, Vector3f cameraPosition) {
        if (translucentIndices.isEmpty()) return;

        // Perform CPU-side sorting of indices based on distance to camera
        translucentIndices.sort((idx1, idx2) -> {
            // Retrieve Model Matrices
            Matrix4f m1 = modelMatrices[idx1];
            Matrix4f m2 = modelMatrices[idx2];

            // Calculate distance squared from Camera Position to Model Origin (Translation components)
            // m30 = x, m31 = y, m32 = z in column-major 4x4 matrix
            float distSq1 = cameraPosition.distanceSquared(m1.m30(), m1.m31(), m1.m32());
            float distSq2 = cameraPosition.distanceSquared(m2.m30(), m2.m31(), m2.m32());

            // Sort Descending (Back-to-Front): Higher distance comes first
            return Float.compare(distSq2, distSq1);
        });

        flushInternal(viewMatrix, projectionMatrix, true, null);
    }

    /**
     * Internal dispatcher that executes the render logic.
     * <p>
     * It saves the current GL state, calculates view rotation, and branches to either
     * the Vanilla or Shaderpack render path based on environment detection.
     *
     * @param viewMatrix        The view matrix.
     * @param projectionMatrix  The projection matrix.
     * @param renderTranslucent True if rendering the translucent pass, false for opaque/cutout.
     * @param cameraPos         Camera position (unused here, passed for potential future use).
     */
    private void flushInternal(Matrix4f viewMatrix, Matrix4f projectionMatrix, boolean renderTranslucent, Vector3f cameraPos) {
        RenderSystem.assertOnRenderThread();
        // Save the previous GL state (VAO/VBO/EBO bindings) to prevent conflicts with vanilla
        VxGlState.saveCurrentState();

        try {
            // Extract the rotation component from the view matrix.
            // This is required to correctly rotate normal vectors into view space.
            viewMatrix.get3x3(VIEW_ROTATION);

            // Detect rendering pipeline and dispatch to the correct batch method
            if (VxShaderDetector.isShaderpackActive()) {
                renderBatchShaderpack(viewMatrix, projectionMatrix, renderTranslucent);
            } else {
                renderBatchVanilla(viewMatrix, projectionMatrix, renderTranslucent);
            }
        } finally {
            // Restore the GL state
            VxGlState.restorePreviousState();
            // Note: We do NOT clear the arrays here because flushOpaque and flushTranslucent
            // are called at different times in the same frame. Reset happens in reset().
        }
    }

    /**
     * Renders the batch using the standard Minecraft rendering pipeline (no shaderpacks).
     * Handles switching between Solid, Cutout, and Translucent shaders and states.
     *
     * @param viewMatrix        The view matrix.
     * @param projectionMatrix  The projection matrix.
     * @param renderTranslucent Whether to render the translucent bucket or the opaque/cutout buckets.
     */
    private void renderBatchVanilla(Matrix4f viewMatrix, Matrix4f projectionMatrix, boolean renderTranslucent) {
        // Variable to hold the list of indices we want to process in the current sub-pass
        List<Integer> bucket = null;
        ShaderInstance shader;

        if (renderTranslucent) {
            // --- TRANSLUCENT PASS ---
            bucket = translucentIndices;

            // Set GL State for Transparency
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            RenderSystem.depthMask(false); // Read-only depth buffer to prevent self-occlusion artifacts

            // Use Vanilla Translucent Shader
            shader = setupCommonRenderState(projectionMatrix, GameRenderer.getRendertypeEntityTranslucentShader());
        } else {
            // --- OPAQUE / CUTOUT PASS ---
            // Set GL State for Solids
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true); // Write to depth buffer

            // Start with the Solid Shader
            shader = setupCommonRenderState(projectionMatrix, GameRenderer.getRendertypeEntitySolidShader());
        }

        if (shader == null) return;

        // Bind global textures (Lightmap)
        Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();

        // Initialize samplers
        for (int i = 0; i < 12; ++i) {
            shader.setSampler("Sampler" + i, RenderSystem.getShaderTexture(i));
        }

        // Set default Overlay Color (neutral)
        GL30.glVertexAttrib4f(1, 1.0f, 1.0f, 1.0f, 1.0f);

        // --- Render Execution ---
        if (!renderTranslucent) {
            // 1. Render Opaque Bucket
            renderBucketVanilla(opaqueIndices, shader, viewMatrix, VxRenderType.OPAQUE);

            // 2. Render Cutout Bucket
            if (!cutoutIndices.isEmpty()) {
                // Switch Shader to Cutout (supports discard)
                ShaderInstance cutoutShader = GameRenderer.getRendertypeEntityCutoutShader();
                if (cutoutShader != null) {
                    RenderSystem.setShader(() -> cutoutShader);
                    setupShaderUniforms(cutoutShader, projectionMatrix);

                    // Render the Cutout list
                    renderBucketVanilla(cutoutIndices, cutoutShader, viewMatrix, VxRenderType.CUTOUT);
                }
            }
        } else {
            // 3. Render Translucent Bucket
            renderBucketVanilla(bucket, shader, viewMatrix, VxRenderType.TRANSLUCENT);
        }

        // Restore global state defaults
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        shader.clear();
    }

    /**
     * Inner loop for vanilla rendering. Iterates over a specific bucket of indices.
     *
     * @param bucket      The list of SoA indices to render.
     * @param shader      The active shader instance.
     * @param viewMatrix  The camera view matrix.
     * @param currentType The expected render type (used to filter sub-commands).
     */
    private void renderBucketVanilla(List<Integer> bucket, ShaderInstance shader, Matrix4f viewMatrix, VxRenderType currentType) {
        boolean isCullingEnabled = true;

        for (Integer i : bucket) {
            IVxRenderableMesh mesh = this.meshes[i];
            Matrix4f modelMat = this.modelMatrices[i];
            Matrix3f normalMat = this.normalMatrices[i];
            int packedLight = this.packedLights[i];

            // --- Math Calculations ---

            // 1. Compute Model-View Matrix (View * Model)
            AUX_MODEL_VIEW.set(viewMatrix).mul(modelMat);

            // 2. Compute Normal Matrix (ViewRotation * NormalMat)
            AUX_NORMAL_VIEW.set(VIEW_ROTATION).mul(normalMat);

            // 3. Lighting Correction
            // Transform global light vectors into model space using the inverse normal matrix.
            // This is required because vanilla shaders expect lights in a specific space relative to the object.
            AUX_NORMAL_MAT.set(AUX_NORMAL_VIEW).transpose();
            AUX_LIGHT0.set(VANILLA_LIGHT0).mul(AUX_NORMAL_MAT);
            AUX_LIGHT1.set(VANILLA_LIGHT1).mul(AUX_NORMAL_MAT);

            // Upload calculated uniforms
            if (shader.LIGHT0_DIRECTION != null) shader.LIGHT0_DIRECTION.set(AUX_LIGHT0);
            if (shader.LIGHT1_DIRECTION != null) shader.LIGHT1_DIRECTION.set(AUX_LIGHT1);
            if (shader.MODEL_VIEW_MATRIX != null) shader.MODEL_VIEW_MATRIX.set(AUX_MODEL_VIEW);

            // Upload 3x3 Normal Matrix
            int normalMatrixLocation = Uniform.glGetUniformLocation(shader.getId(), "NormalMat");
            if (normalMatrixLocation != -1) {
                MATRIX_BUFFER_9.clear();
                AUX_NORMAL_VIEW.get(MATRIX_BUFFER_9);
                RenderSystem.glUniformMatrix3(normalMatrixLocation, false, MATRIX_BUFFER_9);
            }

            // Bind VAO
            mesh.setupVaoState();

            // Upload packed light coords via Vertex Attribute
            GL30.glDisableVertexAttribArray(AT_UV2);
            GL30.glVertexAttribI2i(AT_UV2, packedLight & 0xFFFF, packedLight >> 16);

            // Execute Draw Commands
            for (VxDrawCommand rawCommand : mesh.getDrawCommands()) {
                VxDrawCommand command = mesh.resolveCommand(rawCommand);
                VxMaterial mat = command.material;

                // Ensure we only render parts of the mesh that match the current pass.
                if (mat.renderType != currentType) continue;

                // Culling State
                boolean shouldCull = !mat.doubleSided;
                if (shouldCull != isCullingEnabled) {
                    if (shouldCull) RenderSystem.enableCull();
                    else RenderSystem.disableCull();
                    isCullingEnabled = shouldCull;
                }

                // Apply custom blend mode defined in material
                mat.blendMode.apply();

                // Bind Texture
                RenderSystem.setShaderTexture(0, mat.albedoMapGlId);
                shader.setSampler("Sampler0", mat.albedoMapGlId);

                // Apply Color Tint
                if (shader.COLOR_MODULATOR != null) {
                    shader.COLOR_MODULATOR.set(mat.baseColorFactor);
                }

                shader.apply();

                // Draw
                GL32.glDrawElementsBaseVertex(
                        GL30.GL_TRIANGLES,
                        command.indexCount,
                        VxIndexBuffer.GL_INDEX_TYPE,
                        command.indexOffsetBytes,
                        command.baseVertex
                );
            }

            // Cleanup Attribute state
            GL30.glEnableVertexAttribArray(AT_UV2);
        }

        // Restore global culling state
        if (!isCullingEnabled) {
            RenderSystem.enableCull();
        }
    }

    /**
     * Renders the batch using a pipeline compatible with Iris shaderpacks.
     * <p>
     * Dynamically queries the active shader program to find texture units for Normals and Specular maps.
     * Handles the G-Buffer writing (Opaque) vs Composite (Translucent) separation.
     *
     * @param viewMatrix        The view matrix.
     * @param projectionMatrix  The projection matrix.
     * @param renderTranslucent Whether to render the translucent pass.
     */
    private void renderBatchShaderpack(Matrix4f viewMatrix, Matrix4f projectionMatrix, boolean renderTranslucent) {
        // Always start with the base Solid Shader. Iris wraps this.
        ShaderInstance shader = setupCommonRenderState(projectionMatrix, GameRenderer.getRendertypeEntitySolidShader());
        if (shader == null) return;

        // Reset samplers
        for (int i = 0; i < 12; ++i) {
            shader.setSampler("Sampler" + i, RenderSystem.getShaderTexture(i));
        }
        shader.apply();
        GL30.glVertexAttrib4f(1, 1.0f, 1.0f, 1.0f, 1.0f);

        // --- Dynamic PBR Unit Resolution ---
        // Query the active shader program (which is replaced by Iris) to find where PBR maps go.
        int currentProgramId = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);

        if (currentProgramId != this.lastProgramId) {
            this.lastProgramId = currentProgramId;

            // Find locations for "normals" and "specular" samplers
            int normalUniformLoc = GL20.glGetUniformLocation(currentProgramId, "normals");
            int specularUniformLoc = GL20.glGetUniformLocation(currentProgramId, "specular");

            this.cachedNormalUnit = (normalUniformLoc != -1) ? GL20.glGetUniformi(currentProgramId, normalUniformLoc) : -1;
            this.cachedSpecularUnit = (specularUniformLoc != -1) ? GL20.glGetUniformi(currentProgramId, specularUniformLoc) : -1;

            // Resolve Normal Matrix Uniform Name (varies between packs)
            this.cachedNormalMatLoc = Uniform.glGetUniformLocation(currentProgramId, "NormalMat");
            if (this.cachedNormalMatLoc == -1) {
                this.cachedNormalMatLoc = Uniform.glGetUniformLocation(currentProgramId, "normalMatrix");
            }
        }

        if (renderTranslucent) {
            // --- TRANSLUCENT PASS (Shaderpack) ---
            RenderSystem.enableBlend();
            RenderSystem.depthMask(false); // Usually read-only for transparents

            renderBucketShaderpack(translucentIndices, shader, viewMatrix, VxRenderType.TRANSLUCENT);

            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        } else {
            // --- OPAQUE / G-BUFFER PASS ---
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);

            // Render Opaque
            renderBucketShaderpack(opaqueIndices, shader, viewMatrix, VxRenderType.OPAQUE);

            // Render Cutout (Shaderpacks usually handle alpha discard internally via texture alpha)
            renderBucketShaderpack(cutoutIndices, shader, viewMatrix, VxRenderType.CUTOUT);
        }

        shader.clear();
    }

    /**
     * Inner loop for shaderpack rendering.
     *
     * @param bucket      The list of indices.
     * @param shader      The shader instance.
     * @param viewMatrix  The view matrix.
     * @param currentType The target render type.
     */
    private void renderBucketShaderpack(List<Integer> bucket, ShaderInstance shader, Matrix4f viewMatrix, VxRenderType currentType) {
        boolean isCullingEnabled = true;

        for (Integer i : bucket) {
            IVxRenderableMesh mesh = this.meshes[i];
            Matrix4f modelMat = this.modelMatrices[i];
            Matrix3f normalMat = this.normalMatrices[i];
            int packedLight = this.packedLights[i];

            // Matrix Calculations
            AUX_MODEL_VIEW.set(viewMatrix).mul(modelMat);
            AUX_NORMAL_VIEW.set(VIEW_ROTATION).mul(normalMat);

            // Upload ModelView
            if (shader.MODEL_VIEW_MATRIX != null) shader.MODEL_VIEW_MATRIX.set(AUX_MODEL_VIEW);

            // Upload Normal Matrix (using cached location)
            if (this.cachedNormalMatLoc != -1) {
                MATRIX_BUFFER_9.clear();
                AUX_NORMAL_VIEW.get(MATRIX_BUFFER_9);
                RenderSystem.glUniformMatrix3(this.cachedNormalMatLoc, false, MATRIX_BUFFER_9);
            }

            // Bind Mesh State
            mesh.setupVaoState();

            // Lightmap
            GL30.glDisableVertexAttribArray(AT_UV2);
            GL30.glVertexAttribI2i(AT_UV2, packedLight & 0xFFFF, packedLight >> 16);

            for (VxDrawCommand rawCommand : mesh.getDrawCommands()) {
                VxDrawCommand command = mesh.resolveCommand(rawCommand);
                VxMaterial mat = command.material;

                if (mat.renderType != currentType) continue;

                // Culling
                boolean shouldCull = !mat.doubleSided;
                if (shouldCull != isCullingEnabled) {
                    if (shouldCull) RenderSystem.enableCull();
                    else RenderSystem.disableCull();
                    isCullingEnabled = shouldCull;
                }

                // Color Tint
                if (shader.COLOR_MODULATOR != null) shader.COLOR_MODULATOR.set(mat.baseColorFactor);

                // Apply Blend Mode (though Shaderpacks often override this)
                mat.blendMode.apply();

                shader.apply();

                // --- Bind Albedo (Unit 0) ---
                RenderSystem.activeTexture(GL13.GL_TEXTURE0);
                RenderSystem.bindTexture(mat.albedoMapGlId);

                // --- Bind Normal Map (Dynamic Unit) ---
                if (this.cachedNormalUnit != -1 && mat.normalMapGlId != -1) {
                    RenderSystem.activeTexture(GL13.GL_TEXTURE0 + this.cachedNormalUnit);
                    RenderSystem.bindTexture(mat.normalMapGlId);
                }

                // --- Bind Specular/LabPBR Map (Dynamic Unit) ---
                if (this.cachedSpecularUnit != -1 && mat.specularMapGlId != -1) {
                    RenderSystem.activeTexture(GL13.GL_TEXTURE0 + this.cachedSpecularUnit);
                    RenderSystem.bindTexture(mat.specularMapGlId);
                }

                // Reset Active Texture
                RenderSystem.activeTexture(GL13.GL_TEXTURE0);

                // Draw
                GL32.glDrawElementsBaseVertex(
                        GL30.GL_TRIANGLES,
                        command.indexCount,
                        VxIndexBuffer.GL_INDEX_TYPE,
                        command.indexOffsetBytes,
                        command.baseVertex
                );
            }

            GL30.glEnableVertexAttribArray(AT_UV2);
        }

        if (!isCullingEnabled) {
            RenderSystem.enableCull();
        }
    }

    /**
     * Sets up the common render state and shader uniforms used by both rendering paths.
     * This is called once per batch flush.
     *
     * @param projectionMatrix The projection matrix to upload to the shader.
     * @param targetShader     The specific shader instance we want to configure.
     * @return The configured {@link ShaderInstance} to be used for rendering, or null if setup fails.
     */
    private ShaderInstance setupCommonRenderState(Matrix4f projectionMatrix, ShaderInstance targetShader) {
        // --- Common OpenGL State ---
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
     * Uploads standard global uniforms (Projection, Fog, GameTime) to the shader.
     *
     * @param shader           The shader instance.
     * @param projectionMatrix The projection matrix.
     */
    private void setupShaderUniforms(ShaderInstance shader, Matrix4f projectionMatrix) {
        if (shader.PROJECTION_MATRIX != null) shader.PROJECTION_MATRIX.set(projectionMatrix);
        if (shader.TEXTURE_MATRIX != null) shader.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
        if (shader.COLOR_MODULATOR != null) shader.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
        if (shader.FOG_START != null) shader.FOG_START.set(RenderSystem.getShaderFogStart());
        if (shader.FOG_END != null) shader.FOG_END.set(RenderSystem.getShaderFogEnd());
        if (shader.FOG_COLOR != null) shader.FOG_COLOR.set(RenderSystem.getShaderFogColor());
        if (shader.FOG_SHAPE != null) shader.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
        if (shader.GAME_TIME != null) shader.GAME_TIME.set(RenderSystem.getShaderGameTime());
        if (shader.GLINT_ALPHA != null) shader.GLINT_ALPHA.set(RenderSystem.getShaderGlintAlpha());
        if (shader.SCREEN_SIZE != null) {
            Window window = Minecraft.getInstance().getWindow();
            shader.SCREEN_SIZE.set((float) window.getWidth(), (float) window.getHeight());
        }

        RenderSystem.setupShaderLights(shader);
    }
}