/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.loader;

import net.xmx.velgfx.renderer.gl.VxDrawCommand;
import net.xmx.velgfx.renderer.gl.VxVertexBuffer;
import net.xmx.velgfx.renderer.gl.layout.VxSkinnedVertexLayout;
import net.xmx.velgfx.renderer.gl.layout.VxStaticVertexLayout;
import net.xmx.velgfx.renderer.gl.material.VxMaterial;
import net.xmx.velgfx.renderer.gl.mesh.arena.VxArenaManager;
import net.xmx.velgfx.renderer.gl.mesh.impl.VxArenaMesh;
import net.xmx.velgfx.renderer.gl.mesh.impl.VxDedicatedMesh;
import net.xmx.velgfx.renderer.gl.mesh.impl.VxSkinnedMesh;
import net.xmx.velgfx.renderer.gl.shader.VxSkinningShader;
import net.xmx.velgfx.renderer.model.VxSkinnedModel;
import net.xmx.velgfx.renderer.model.VxStaticModel;
import net.xmx.velgfx.renderer.model.animation.VxAnimation;
import net.xmx.velgfx.renderer.model.skeleton.VxBone;
import net.xmx.velgfx.renderer.model.skeleton.VxNode;
import net.xmx.velgfx.renderer.model.skeleton.VxSkeleton;
import net.xmx.velgfx.resources.VxResourceLocation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.assimp.*;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.*;

/**
 * A production-grade loader for 3D assets using the Assimp library.
 * <p>
 * This class orchestrates the ingestion of external model files (OBJ, FBX, GLTF, etc.)
 * and converts them into the engine's internal memory formats. It handles the complexity
 * of Assimp's data structures, including coordinate system conversion, material extraction,
 * and the separation of static geometry from skeletal animation data.
 * <p>
 * <b>Architecture:</b>
 * The loader operates in two distinct modes:
 * <ol>
 *     <li><b>Static Mode:</b> optimized for Rigid Body animations (44-byte vertex stride).</li>
 *     <li><b>Skinned Mode:</b> optimized for Vertex Skinning (80-byte vertex stride).</li>
 * </ol>
 *
 * @author xI-Mx-Ix
 */
public class VxAssimpLoader {

    /**
     * Assimp post-processing flags to normalize the input data.
     * <ul>
     *     <li>{@code aiProcess_Triangulate}: Ensures all faces are triangles.</li>
     *     <li>{@code aiProcess_GenSmoothNormals}: Generates normals if missing.</li>
     *     <li>{@code aiProcess_FlipUVs}: Converts UV origin from Top-Left to Bottom-Left (OpenGL standard).</li>
     *     <li>{@code aiProcess_CalcTangentSpace}: Generates Tangents for Normal Mapping.</li>
     *     <li>{@code aiProcess_LimitBoneWeights}: Limits weights to 4 per vertex (GPU requirement).</li>
     *     <li>{@code aiProcess_JoinIdenticalVertices}: Optimizes the mesh by removing duplicate vertices.</li>
     *     <li>{@code aiProcess_GlobalScale}: Applies global scaling units defined in the file.</li>
     * </ul>
     */
    private static final int POST_PROCESS_FLAGS = Assimp.aiProcess_Triangulate |
            Assimp.aiProcess_GenSmoothNormals |
            Assimp.aiProcess_FlipUVs |
            Assimp.aiProcess_CalcTangentSpace |
            Assimp.aiProcess_LimitBoneWeights |
            Assimp.aiProcess_JoinIdenticalVertices |
            Assimp.aiProcess_GlobalScale;

    /**
     * Private constructor to prevent instantiation.
     */
    private VxAssimpLoader() {}

    /**
     * Loads a file as a static model with rigid body animation support.
     * <p>
     * The geometry is allocated into the {@link VxStaticVertexLayout} arena.
     *
     * @param location The resource location of the model file.
     * @return The constructed static model.
     */
    public static VxStaticModel loadStatic(VxResourceLocation location) {
        // Load the raw file bytes into a native Direct ByteBuffer
        ByteBuffer fileData = loadResourceToBuffer(location);

        // Extract extension (e.g., "obj") to hint Assimp about the format
        String extensionHint = getExtension(location.getPath());

        AIScene scene = importSceneFromMemory(fileData, extensionHint);

        try {
            // Parse materials relative to the model location
            List<VxMaterial> materials = parseMaterials(scene, location);

            // Container for geometry results
            List<VxDrawCommand> allCommands = new ArrayList<>();

            // Process geometry directly into a ByteBuffer compatible with VxStaticVertexLayout
            ByteBuffer geometryBuffer = processStaticGeometry(scene, materials, allCommands);

            // Allocate the geometry in the centralized Static Arena
            // This returns a mesh handle that points to the data in the shared buffer
            VxArenaMesh arenaMesh = VxArenaManager.getInstance()
                    .getArena(VxStaticVertexLayout.getInstance())
                    .allocate(geometryBuffer, allCommands, null);

            // Hierarchy and Command Mapping for Rigid Body Animation
            VxNode rootNode = processNodeHierarchy(scene.mRootNode(), null);
            Map<String, List<VxDrawCommand>> nodeCommands = mapNodesToCommands(scene.mRootNode(), allCommands);

            Map<String, VxAnimation> animations = parseAnimations(scene);

            return new VxStaticModel(rootNode, arenaMesh, animations, nodeCommands);
        } finally {
            Assimp.aiReleaseImport(scene);
            // Free the native memory buffer containing the raw file
            MemoryUtil.memFree(fileData);
        }
    }

    /**
     * Imports a file as a {@link VxSkinnedModel} suitable for vertex skinning.
     * <p>
     * The geometry is allocated into the {@link VxSkinnedVertexLayout} arena (Source Data).
     *
     * @param location The resource location of the model file.
     * @param shader   The skinning shader program that will render this mesh.
     * @return A constructed skinned model.
     */
    public static VxSkinnedModel loadSkinned(VxResourceLocation location, VxSkinningShader shader) {
        ByteBuffer fileData = loadResourceToBuffer(location);
        String extensionHint = getExtension(location.getPath());

        AIScene scene = importSceneFromMemory(fileData, extensionHint);

        try {
            List<VxMaterial> materials = parseMaterials(scene, location);

            List<VxDrawCommand> allCommands = new ArrayList<>();
            List<BoneDefinition> boneDefinitions = new ArrayList<>();

            // Process geometry into a buffer compatible with VxSkinnedVertexLayout (includes Weights/Indices)
            ByteBuffer geometryBuffer = processSkinnedGeometry(scene, materials, allCommands, boneDefinitions);

            // Build Scene Hierarchy
            VxNode rootNode = processNodeHierarchy(scene.mRootNode(), null);

            // Construct Final Skeleton
            List<VxBone> finalBones = buildSkeletonBones(boneDefinitions, rootNode);
            Matrix4f globalInverse = toJomlMatrix(scene.mRootNode().mTransformation()).invert();
            VxSkeleton skeleton = new VxSkeleton(rootNode, finalBones, globalInverse);

            Map<String, VxAnimation> animations = parseAnimations(scene);

            // Allocate the Source Data (Bind Pose) in the Skinned Arena
            VxArenaMesh sourceMesh = VxArenaManager.getInstance()
                    .getArena(VxSkinnedVertexLayout.getInstance())
                    .allocate(geometryBuffer, allCommands, null);

            // Create the Skinned Mesh which manages the Transform Feedback pipeline
            VxSkinnedMesh mesh = new VxSkinnedMesh(allCommands, sourceMesh, skeleton, shader);

            return new VxSkinnedModel(skeleton, mesh, animations);
        } finally {
            Assimp.aiReleaseImport(scene);
            MemoryUtil.memFree(fileData);
        }
    }

    /**
     * Reads a resource from the classpath into a Direct ByteBuffer allocated via LWJGL MemoryUtil.
     * <p>
     * <b>Note:</b> The returned buffer is off-heap native memory. The caller is responsible
     * for freeing it via {@link MemoryUtil#memFree(java.nio.Buffer)}.
     *
     * @param location The resource location.
     * @return A Direct ByteBuffer containing the file bytes.
     */
    private static ByteBuffer loadResourceToBuffer(VxResourceLocation location) {
        String path = location.getPath();
        String classpathPath = path.startsWith("/") ? path : "/" + path;

        try (InputStream stream = VxAssimpLoader.class.getResourceAsStream(classpathPath)) {
            if (stream == null) {
                throw new IllegalArgumentException("Model resource not found: " + location);
            }
            byte[] bytes = stream.readAllBytes();

            // Allocate native memory matching the file size
            ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
            buffer.put(bytes);
            buffer.flip(); // Prepare for reading
            return buffer;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read model data: " + location, e);
        }
    }

    /**
     * Imports the scene from the memory buffer.
     *
     * @param buffer        The direct byte buffer containing file data.
     * @param extensionHint The file extension (e.g., "obj") to help Assimp identify the format.
     * @return The imported Assimp scene.
     */
    private static AIScene importSceneFromMemory(ByteBuffer buffer, String extensionHint) {
        AIScene scene = Assimp.aiImportFileFromMemory(buffer, POST_PROCESS_FLAGS, extensionHint);
        if (scene == null) {
            throw new RuntimeException("Assimp failed to load model from memory: " + Assimp.aiGetErrorString());
        }
        return scene;
    }

    /**
     * Helper to extract file extension from a path string.
     */
    private static String getExtension(String path) {
        int i = path.lastIndexOf('.');
        return (i > 0) ? path.substring(i + 1) : "";
    }

    /**
     * Extracts material properties and resolves texture paths relative to the model file.
     *
     * @param scene         The Assimp scene.
     * @param modelLocation The location of the loaded model file.
     * @return A list of materials.
     */
    private static List<VxMaterial> parseMaterials(AIScene scene, VxResourceLocation modelLocation) {
        int numMaterials = scene.mNumMaterials();
        List<VxMaterial> result = new ArrayList<>(numMaterials);

        // Determine the base directory of the model to resolve relative texture paths
        String modelDir = modelLocation.getDirectory();

        for (int i = 0; i < numMaterials; i++) {
            AIMaterial aiMat = AIMaterial.create(scene.mMaterials().get(i));
            VxMaterial mat = new VxMaterial("mat_" + i);

            // Attempt to resolve the Diffuse/Albedo texture
            AIString path = AIString.calloc();
            int returnCode = Assimp.aiGetMaterialTexture(aiMat, Assimp.aiTextureType_DIFFUSE, 0, path, (IntBuffer) null, null, null, null, null, null);

            if (returnCode == Assimp.aiReturn_SUCCESS) {
                String rawPath = path.dataString();

                // Resolve path: e.g. "textures/skin.png" relative to "assets/mod/models/"
                // becomes "assets/mod/models/textures/skin.png"
                mat.albedoMap = new VxResourceLocation(modelDir, rawPath);
            }
            path.free();
            result.add(mat);
        }
        return result;
    }

    // --- STATIC GEOMETRY PROCESSING ---

    /**
     * Flattens the scene meshes into a single vertex buffer using the {@link VxStaticVertexLayout}.
     */
    private static ByteBuffer processStaticGeometry(AIScene scene, List<VxMaterial> materials, List<VxDrawCommand> outCommands) {
        int numMeshes = scene.mNumMeshes();

        // Pre-calculate total buffer size to allocate direct memory once
        int totalVertices = 0;
        for (int i = 0; i < numMeshes; i++) {
            totalVertices += AIMesh.create(scene.mMeshes().get(i)).mNumVertices();
        }

        ByteBuffer buffer = ByteBuffer.allocateDirect(totalVertices * VxStaticVertexLayout.STRIDE).order(ByteOrder.nativeOrder());
        int vertexOffset = 0;

        for (int i = 0; i < numMeshes; i++) {
            AIMesh aiMesh = AIMesh.create(scene.mMeshes().get(i));
            VxMaterial mat = materials.get(aiMesh.mMaterialIndex());
            int count = aiMesh.mNumVertices();

            writeStaticMeshToBuffer(buffer, aiMesh);

            outCommands.add(new VxDrawCommand(mat, vertexOffset, count));
            vertexOffset += count;
        }

        buffer.flip();
        return buffer;
    }

    /**
     * Writes a single mesh's vertices to the buffer in the Static format (Position, Color, UVs, Normals).
     */
    private static void writeStaticMeshToBuffer(ByteBuffer buffer, AIMesh mesh) {
        int count = mesh.mNumVertices();
        AIVector3D.Buffer verts = mesh.mVertices();
        AIVector3D.Buffer norms = mesh.mNormals();
        AIVector3D.Buffer uvs = mesh.mTextureCoords(0);
        AIVector3D.Buffer tans = mesh.mTangents();

        for (int i = 0; i < count; i++) {
            AIVector3D v = verts.get(i);
            AIVector3D n = (norms != null) ? norms.get(i) : null;
            AIVector3D uv = (uvs != null) ? uvs.get(i) : null;
            AIVector3D t = (tans != null) ? tans.get(i) : null;

            // Layout must match VxStaticVertexLayout exactly:
            // 1. Position (3 floats)
            buffer.putFloat(v.x()).putFloat(v.y()).putFloat(v.z());

            // 2. Color (4 bytes) - Defaulting to White (0xFFFFFFFF)
            buffer.putInt(0xFFFFFFFF);

            // 3. UV0 (2 floats)
            if (uv != null) buffer.putFloat(uv.x()).putFloat(uv.y());
            else buffer.putFloat(0f).putFloat(0f);

            // 4. UV2 Lightmap (2 shorts) - Defaulting to 0
            buffer.putShort((short) 0).putShort((short) 0);

            // 5. Normal (3 bytes, normalized)
            if (n != null) {
                buffer.put((byte) (n.x() * 127)).put((byte) (n.y() * 127)).put((byte) (n.z() * 127));
            } else {
                buffer.put((byte) 0).put((byte) 127).put((byte) 0);
            }

            // 6. Tangent (4 bytes, normalized)
            if (t != null) {
                buffer.put((byte) (t.x() * 127)).put((byte) (t.y() * 127)).put((byte) (t.z() * 127)).put((byte) 127);
            } else {
                buffer.put((byte) 127).put((byte) 0).put((byte) 0).put((byte) 127);
            }

            // 7. Padding (1 byte)
            buffer.put((byte) 0);

            // 8. MidTexCoord (2 floats) - Used for Parallax, copy UV0
            if (uv != null) buffer.putFloat(uv.x()).putFloat(uv.y());
            else buffer.putFloat(0f).putFloat(0f);
        }
    }

    // --- SKINNED GEOMETRY PROCESSING ---

    /**
     * Flattens geometry into the {@link VxSkinnedVertexLayout} and extracts bone definitions.
     * <p>
     * This method populates the {@code boneDefinitions} list as it encounters new bones in the mesh data.
     */
    private static ByteBuffer processSkinnedGeometry(AIScene scene, List<VxMaterial> materials, List<VxDrawCommand> outCommands, List<BoneDefinition> boneDefinitions) {
        int numMeshes = scene.mNumMeshes();
        int totalVertices = 0;
        for (int i = 0; i < numMeshes; i++) {
            totalVertices += AIMesh.create(scene.mMeshes().get(i)).mNumVertices();
        }

        ByteBuffer buffer = ByteBuffer.allocateDirect(totalVertices * VxSkinnedVertexLayout.STRIDE).order(ByteOrder.nativeOrder());
        int vertexOffset = 0;

        // Map to ensure we don't duplicate bone definitions across multiple meshes
        Map<String, Integer> globalBoneIndexMap = new HashMap<>();

        for (int i = 0; i < numMeshes; i++) {
            AIMesh aiMesh = AIMesh.create(scene.mMeshes().get(i));
            VxMaterial mat = materials.get(aiMesh.mMaterialIndex());
            int count = aiMesh.mNumVertices();

            writeSkinnedMeshToBuffer(buffer, aiMesh, globalBoneIndexMap, boneDefinitions);

            outCommands.add(new VxDrawCommand(mat, vertexOffset, count));
            vertexOffset += count;
        }

        buffer.flip();
        return buffer;
    }

    private static void writeSkinnedMeshToBuffer(ByteBuffer buffer, AIMesh mesh, Map<String, Integer> globalBoneMap, List<BoneDefinition> boneDefs) {
        int count = mesh.mNumVertices();

        // Arrays to hold weight data for this mesh
        // OpenGL 4.x supports max 4 weights per vertex.
        float[] weights = new float[count * 4];
        float[] indices = new float[count * 4];

        // Initialize indices to 0 to avoid garbage data
        Arrays.fill(indices, 0f);

        // 1. Process Bones and populate weight arrays
        for (int i = 0; i < mesh.mNumBones(); i++) {
            AIBone aiBone = AIBone.create(mesh.mBones().get(i));
            String name = aiBone.mName().dataString();

            // Register bone if seen for the first time
            int boneId = globalBoneMap.computeIfAbsent(name, k -> {
                int newId = boneDefs.size();
                // Store the definition (data only), not the VxBone object yet
                boneDefs.add(new BoneDefinition(newId, name, toJomlMatrix(aiBone.mOffsetMatrix())));
                return newId;
            });

            // Map weights to vertices
            for (int wIdx = 0; wIdx < aiBone.mNumWeights(); wIdx++) {
                AIVertexWeight w = aiBone.mWeights().get(wIdx);
                int vertexId = w.mVertexId();
                float weightVal = w.mWeight();

                // Find first empty slot (0..3) for this vertex
                for (int slot = 0; slot < 4; slot++) {
                    if (weights[vertexId * 4 + slot] == 0f) {
                        weights[vertexId * 4 + slot] = weightVal;
                        indices[vertexId * 4 + slot] = (float) boneId;
                        break;
                    }
                }
            }
        }

        // 2. Write Interleaved Vertex Data
        AIVector3D.Buffer verts = mesh.mVertices();
        AIVector3D.Buffer uvs = mesh.mTextureCoords(0);
        AIVector3D.Buffer norms = mesh.mNormals();
        AIVector3D.Buffer tans = mesh.mTangents();

        for (int i = 0; i < count; i++) {
            AIVector3D v = verts.get(i);
            AIVector3D uv = (uvs != null) ? uvs.get(i) : null;
            AIVector3D n = (norms != null) ? norms.get(i) : null;
            AIVector3D t = (tans != null) ? tans.get(i) : null;

            // Position (vec3)
            buffer.putFloat(v.x()).putFloat(v.y()).putFloat(v.z());

            // UV0 (vec2)
            if (uv != null) buffer.putFloat(uv.x()).putFloat(uv.y());
            else buffer.putFloat(0f).putFloat(0f);

            // Normal (vec3)
            if (n != null) buffer.putFloat(n.x()).putFloat(n.y()).putFloat(n.z());
            else buffer.putFloat(0f).putFloat(1f).putFloat(0f);

            // Tangent (vec4)
            if (t != null) buffer.putFloat(t.x()).putFloat(t.y()).putFloat(t.z()).putFloat(1f);
            else buffer.putFloat(1f).putFloat(0f).putFloat(0f).putFloat(1f);

            // Bone Weights (vec4)
            buffer.putFloat(weights[i * 4]).putFloat(weights[i * 4 + 1])
                    .putFloat(weights[i * 4 + 2]).putFloat(weights[i * 4 + 3]);

            // Bone Indices (vec4)
            buffer.putFloat(indices[i * 4]).putFloat(indices[i * 4 + 1])
                    .putFloat(indices[i * 4 + 2]).putFloat(indices[i * 4 + 3]);
        }
    }

    // --- HIERARCHY & ANIMATION PARSING ---

    /**
     * Recursively parses the Assimp Node graph into a generic {@link VxNode} tree.
     */
    private static VxNode processNodeHierarchy(AINode aiNode, VxNode parent) {
        VxNode node = new VxNode(
                aiNode.mName().dataString(),
                parent,
                toJomlMatrix(aiNode.mTransformation())
        );

        for (int i = 0; i < aiNode.mNumChildren(); i++) {
            node.addChild(processNodeHierarchy(AINode.create(aiNode.mChildren().get(i)), node));
        }
        return node;
    }

    /**
     * Creates the mapping between Node Names and Draw Commands.
     * Essential for Rigid Body animations where specific parts (Nodes) need to be rendered.
     */
    private static Map<String, List<VxDrawCommand>> mapNodesToCommands(AINode root, List<VxDrawCommand> allCommands) {
        Map<String, List<VxDrawCommand>> map = new HashMap<>();
        collectNodeCommands(root, allCommands, map);
        return map;
    }

    private static void collectNodeCommands(AINode node, List<VxDrawCommand> allCommands, Map<String, List<VxDrawCommand>> map) {
        int numMeshes = node.mNumMeshes();
        if (numMeshes > 0) {
            List<VxDrawCommand> nodeCmds = new ArrayList<>(numMeshes);
            for (int i = 0; i < numMeshes; i++) {
                int meshIndex = node.mMeshes().get(i);
                // Validate index to prevent out-of-bounds access
                if (meshIndex >= 0 && meshIndex < allCommands.size()) {
                    nodeCmds.add(allCommands.get(meshIndex));
                }
            }
            map.put(node.mName().dataString(), nodeCmds);
        }

        for (int i = 0; i < node.mNumChildren(); i++) {
            collectNodeCommands(AINode.create(node.mChildren().get(i)), allCommands, map);
        }
    }

    /**
     * Constructs the final {@link VxBone} list by linking definitions to the scene graph.
     */
    private static List<VxBone> buildSkeletonBones(List<BoneDefinition> definitions, VxNode root) {
        List<VxBone> finalBones = new ArrayList<>(definitions.size());

        for (BoneDefinition def : definitions) {
            // Locate the node in the hierarchy that matches the bone name
            VxNode node = root.findByName(def.name);

            if (node == null) {
                // If the bone node is missing in the hierarchy (should not happen in valid models),
                // we bind it to the root to prevent crashes, though animation won't work for this bone.
                node = root;
                // Log warning in production code here
            }

            finalBones.add(new VxBone(def.id, def.name, def.offsetMatrix, node));
        }
        return finalBones;
    }

    private static Map<String, VxAnimation> parseAnimations(AIScene scene) {
        if (scene.mNumAnimations() == 0) return Collections.emptyMap();

        Map<String, VxAnimation> animations = new HashMap<>();
        for (int i = 0; i < scene.mNumAnimations(); i++) {
            AIAnimation aiAnim = AIAnimation.create(scene.mAnimations().get(i));
            VxAnimation anim = processSingleAnimation(aiAnim);
            animations.put(anim.getName(), anim);
        }
        return animations;
    }

    private static VxAnimation processSingleAnimation(AIAnimation aiAnim) {
        String name = aiAnim.mName().dataString();
        double duration = aiAnim.mDuration();
        double tps = aiAnim.mTicksPerSecond() != 0 ? aiAnim.mTicksPerSecond() : 25.0;

        Map<String, VxAnimation.NodeChannel> channels = new HashMap<>();
        for (int c = 0; c < aiAnim.mNumChannels(); c++) {
            AINodeAnim ch = AINodeAnim.create(aiAnim.mChannels().get(c));

            // Extract Position Keys
            List<VxAnimation.Key<Vector3f>> pos = new ArrayList<>(ch.mNumPositionKeys());
            for (int k = 0; k < ch.mNumPositionKeys(); k++) {
                AIVectorKey key = ch.mPositionKeys().get(k);
                pos.add(new VxAnimation.Key<>(key.mTime(), new Vector3f(key.mValue().x(), key.mValue().y(), key.mValue().z())));
            }

            // Extract Rotation Keys
            List<VxAnimation.Key<Quaternionf>> rot = new ArrayList<>(ch.mNumRotationKeys());
            for (int k = 0; k < ch.mNumRotationKeys(); k++) {
                AIQuatKey key = ch.mRotationKeys().get(k);
                // Assimp Quaternions are (w, x, y, z), JOML is (x, y, z, w)
                rot.add(new VxAnimation.Key<>(key.mTime(), new Quaternionf(key.mValue().x(), key.mValue().y(), key.mValue().z(), key.mValue().w())));
            }

            // Extract Scaling Keys
            List<VxAnimation.Key<Vector3f>> scl = new ArrayList<>(ch.mNumScalingKeys());
            for (int k = 0; k < ch.mNumScalingKeys(); k++) {
                AIVectorKey key = ch.mScalingKeys().get(k);
                scl.add(new VxAnimation.Key<>(key.mTime(), new Vector3f(key.mValue().x(), key.mValue().y(), key.mValue().z())));
            }

            channels.put(ch.mNodeName().dataString(), new VxAnimation.NodeChannel(pos, rot, scl));
        }

        return new VxAnimation(name, duration, tps, channels);
    }

    // --- UTILITIES ---

    private static Matrix4f toJomlMatrix(AIMatrix4x4 m) {
        return new Matrix4f(
                m.a1(), m.b1(), m.c1(), m.d1(),
                m.a2(), m.b2(), m.c2(), m.d2(),
                m.a3(), m.b3(), m.c3(), m.d3(),
                m.a4(), m.b4(), m.c4(), m.d4()
        );
    }

    /**
     * Intermediate Data Transfer Object (DTO) for holding bone information
     * extracted from mesh geometry before the Node hierarchy is available.
     */
    private record BoneDefinition(int id, String name, Matrix4f offsetMatrix) {}
}