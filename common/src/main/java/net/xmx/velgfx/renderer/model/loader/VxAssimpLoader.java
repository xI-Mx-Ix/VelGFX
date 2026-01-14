/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.loader;

import net.xmx.velgfx.renderer.VelGFX;
import net.xmx.velgfx.renderer.gl.VxDrawCommand;
import net.xmx.velgfx.renderer.gl.layout.VxSkinnedVertexLayout;
import net.xmx.velgfx.renderer.gl.layout.VxStaticVertexLayout;
import net.xmx.velgfx.renderer.gl.material.VxMaterial;
import net.xmx.velgfx.renderer.gl.mesh.arena.VxArenaManager;
import net.xmx.velgfx.renderer.gl.mesh.arena.VxArenaMesh;
import net.xmx.velgfx.renderer.model.VxSkinnedModel;
import net.xmx.velgfx.renderer.model.VxStaticModel;
import net.xmx.velgfx.renderer.model.animation.VxAnimation;
import net.xmx.velgfx.renderer.model.skeleton.VxBone;
import net.xmx.velgfx.renderer.model.skeleton.VxNode;
import net.xmx.velgfx.renderer.model.skeleton.VxSkeleton;
import net.xmx.velgfx.resources.VxResourceLocation;
import org.joml.Matrix4f;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.Assimp;
import org.lwjgl.assimp.AIFileIO;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * A comprehensive loader for 3D assets using the Assimp library.
 * <p>
 * This class acts as a facade/coordinator. It contains no low-level parsing logic.
 * Instead, it delegates specific tasks (I/O, geometry processing, skeleton building)
 * to specialized helper classes.
 * <p>
 * It supports both static rigid bodies and fully skinned meshes with skeletal animation,
 * ensuring proper calculation of Tangent Space for Normal Mapping.
 *
 * @author xI-Mx-Ix
 */
public class VxAssimpLoader {

    /**
     * Post-processing flags used during import to normalize geometry and prepare it for rendering.
     * <p>
     * The configuration includes:
     * <ul>
     *     <li>{@link Assimp#aiProcess_Triangulate}: Ensures all faces are triangles (no quads or polygons).</li>
     *     <li>{@link Assimp#aiProcess_GenSmoothNormals}: Generates smooth vertex normals if the file lacks them.</li>
     *     <li>{@link Assimp#aiProcess_CalcTangentSpace}: Calculates tangents and bitangents required for Normal Mapping.</li>
     *     <li>{@link Assimp#aiProcess_LimitBoneWeights}: Limits the number of bone weights per vertex to 4, matching standard GPU vertex attributes.</li>
     *     <li>{@link Assimp#aiProcess_JoinIdenticalVertices}: Optimizes the mesh by identifying and merging duplicate vertices.</li>
     *     <li>{@link Assimp#aiProcess_GlobalScale}: Applies global scaling units defined in the metadata (e.g., converting centimeters to meters).</li>
     *     <li>{@link Assimp#aiProcess_FlipUVs}: Flips the Y-axis of texture coordinates for OpenGL compatibility.</li>
     * </ul>
     */
    private static final int POST_PROCESS_FLAGS = Assimp.aiProcess_Triangulate |
            Assimp.aiProcess_GenSmoothNormals |
            Assimp.aiProcess_CalcTangentSpace |
            Assimp.aiProcess_LimitBoneWeights |
            Assimp.aiProcess_JoinIdenticalVertices |
            Assimp.aiProcess_GlobalScale |
            Assimp.aiProcess_FlipUVs;

    /**
     * Private constructor to prevent instantiation.
     */
    private VxAssimpLoader() {}

    /**
     * Loads a file as a static model suitable for rigid body rendering.
     * <p>
     * This method preserves the node hierarchy, allowing specific parts (like car wheels)
     * to be transformed independently even if they share the same mesh buffer.
     *
     * @param location The resource location of the model file.
     * @return The constructed static model.
     * @throws RuntimeException If Assimp fails to import the file.
     */
    public static VxStaticModel loadStatic(VxResourceLocation location) {
        // 1. Prepare IO system to read from Classpath/JAR
        AIFileIO fileIo = VxAssimpIO.createFileIO(location);

        String rawPath = location.getPath();
        String fileName = rawPath.substring(rawPath.lastIndexOf('/') + 1);

        // 2. Import Scene via Assimp
        AIScene scene = Assimp.aiImportFileEx(fileName, POST_PROCESS_FLAGS, fileIo);

        if (scene == null) {
            VxAssimpIO.freeFileIO(fileIo);
            throw new RuntimeException("Assimp failed to load static model [" + location + "]: " + Assimp.aiGetErrorString());
        }

        try {
            // 3. Parse Materials
            List<VxMaterial> materials = VxAssimpMaterial.parseMaterials(scene, location);

            // 4. Process Geometry (Vertices, Normals, UVs, Indices)
            List<VxDrawCommand> allCommands = new ArrayList<>();
            VxAssimpGeometry.GeometryResult geometry = VxAssimpGeometry.processStaticGeometry(scene, materials, allCommands);

            // Safety Check
            if (geometry.vertices.limit() == 0) {
                VelGFX.LOGGER.warn("Model '{}' loaded with 0 vertices!", location);
            } else {
                VelGFX.LOGGER.info("Successfully loaded geometry: {} vertices.", geometry.vertices.limit() / VxStaticVertexLayout.STRIDE);
            }

            // 5. Upload to GPU Arena
            VxArenaMesh arenaMesh = VxArenaManager.getInstance()
                    .getArena(VxStaticVertexLayout.getInstance())
                    .allocate(geometry.vertices, geometry.indices, allCommands, null);

            // 6. Build Hierarchy and Skeleton
            VxNode rootNode = VxAssimpStructure.processNodeHierarchy(scene.mRootNode(), null);
            Map<String, List<VxDrawCommand>> nodeCommands = VxAssimpStructure.mapNodesToCommands(scene.mRootNode(), allCommands);
            
            // 7. Parse Animations
            Map<String, VxAnimation> animations = VxAssimpAnimation.parseAnimations(scene);

            // Create wrapper skeleton for static model
            VxSkeleton skeleton = new VxSkeleton(rootNode, Collections.emptyList(), new Matrix4f());

            return new VxStaticModel(skeleton, arenaMesh, animations, nodeCommands);

        } finally {
            Assimp.aiReleaseImport(scene);
            VxAssimpIO.freeFileIO(fileIo);
        }
    }

    /**
     * Imports a file as a {@link VxSkinnedModel} suitable for skeletal animation.
     * <p>
     * This method reads bone weights and indices and constructs the full bone list
     * required for vertex skinning shaders.
     *
     * @param location The resource location of the model file.
     * @return A constructed skinned model.
     * @throws RuntimeException If Assimp fails to import the file.
     */
    public static VxSkinnedModel loadSkinned(VxResourceLocation location) {
        AIFileIO fileIo = VxAssimpIO.createFileIO(location);
        String rawPath = location.getPath();
        String fileName = rawPath.substring(rawPath.lastIndexOf('/') + 1);

        AIScene scene = Assimp.aiImportFileEx(fileName, POST_PROCESS_FLAGS, fileIo);

        if (scene == null) {
            VxAssimpIO.freeFileIO(fileIo);
            throw new RuntimeException("Assimp failed to load skinned model [" + location + "]: " + Assimp.aiGetErrorString());
        }

        try {
            // 1. Parse Materials
            List<VxMaterial> materials = VxAssimpMaterial.parseMaterials(scene, location);

            // 2. Process Geometry & Bones
            List<VxDrawCommand> allCommands = new ArrayList<>();
            List<VxAssimpStructure.BoneDefinition> boneDefinitions = new ArrayList<>();

            // Capture the result containing both Vertex and Index buffers
            VxAssimpGeometry.GeometryResult geometry = VxAssimpGeometry.processSkinnedGeometry(scene, materials, allCommands, boneDefinitions);

            // 3. Build Scene Hierarchy
            VxNode rootNode = VxAssimpStructure.processNodeHierarchy(scene.mRootNode(), null);

            // 4. Construct Final Skeleton (Mapping BoneDefs to Nodes)
            List<VxBone> finalBones = VxAssimpStructure.buildSkeletonBones(boneDefinitions, rootNode);
            Matrix4f globalInverse = VxAssimpStructure.toJomlMatrix(scene.mRootNode().mTransformation()).invert();
            VxSkeleton skeleton = new VxSkeleton(rootNode, finalBones, globalInverse);

            // 5. Parse Animations
            Map<String, VxAnimation> animations = VxAssimpAnimation.parseAnimations(scene);

            // 6. Upload to GPU Arena
            // Pass both vertices and indices to the arena
            VxArenaMesh sourceMesh = VxArenaManager.getInstance()
                    .getArena(VxSkinnedVertexLayout.getInstance())
                    .allocate(geometry.vertices, geometry.indices, allCommands, null);

            return new VxSkinnedModel(skeleton, sourceMesh, animations);

        } finally {
            Assimp.aiReleaseImport(scene);
            VxAssimpIO.freeFileIO(fileIo);
        }
    }
}