/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.loader;

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
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.assimp.*;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.*;

/**
 * A loader for 3D assets using the Assimp library.
 * <p>
 * This class orchestrates the ingestion of external model files (OBJ, FBX, GLTF, etc.)
 * and converts them into the engine's internal memory formats. It handles the complexity
 * of Assimp's data structures, including coordinate system conversion, material extraction,
 * and the separation of static geometry from skeletal animation data.
 * <p>
 * <b>Key Feature: Custom IO System</b><br>
 * Unlike standard Assimp implementations, this loader uses a custom {@link AIFileIO} implementation.
 * This allows Assimp to natively "see" into the Java Classpath (or JAR file). Consequently,
 * multi-file formats like OBJ (which references MTL) or GLTF (which references BIN/Textures)
 * load correctly without manual parsing logic.
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
     *     <li>{@code aiProcess_Triangulate}: Ensures all faces are triangles (no quads/polygons).</li>
     *     <li>{@code aiProcess_GenSmoothNormals}: Generates smooth normals if the file lacks them.</li>
     *     <li>{@code aiProcess_CalcTangentSpace}: Generates Tangents/Bitangents for Normal Mapping.</li>
     *     <li>{@code aiProcess_LimitBoneWeights}: Limits weights to 4 per vertex (Standard GPU requirement).</li>
     *     <li>{@code aiProcess_JoinIdenticalVertices}: Optimizes the mesh by removing duplicate vertices.</li>
     *     <li>{@code aiProcess_GlobalScale}: Applies global scaling units defined in the file (e.g. cm to m).</li>
     * </ul>
     */
    private static final int POST_PROCESS_FLAGS = Assimp.aiProcess_Triangulate |
            Assimp.aiProcess_GenSmoothNormals |
            Assimp.aiProcess_CalcTangentSpace |
            Assimp.aiProcess_LimitBoneWeights |
            Assimp.aiProcess_JoinIdenticalVertices |
            Assimp.aiProcess_GlobalScale;

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private VxAssimpLoader() {}

    /**
     * Loads a file as a static model with rigid body animation support.
     * <p>
     * This method utilizes a custom {@link AIFileIO} to ensure that dependent files
     * (like .mtl material definitions) are correctly resolved from the classpath.
     * The geometry is allocated into the {@link VxStaticVertexLayout} arena.
     *
     * @param location The resource location of the model file.
     * @return The constructed static model.
     * @throws RuntimeException If the model fails to load.
     */
    public static VxStaticModel loadStatic(VxResourceLocation location) {
        // Create the custom IO system to bridge Assimp C++ callbacks with Java InputStream
        AIFileIO fileIo = createFileIO(location);

        // Manually extract the filename from the path.
        String rawPath = location.getPath();
        String fileName = rawPath.substring(rawPath.lastIndexOf('/') + 1);

        // Load the scene using ImportFileEx, which supports the custom IO system
        AIScene scene = Assimp.aiImportFileEx(fileName, POST_PROCESS_FLAGS, fileIo);

        if (scene == null) {
            throw new RuntimeException("Assimp failed to load static model [" + location + "]: " + Assimp.aiGetErrorString());
        }

        try {
            // Parse materials. Assimp has already loaded MTL data thanks to the IO system.
            List<VxMaterial> materials = parseMaterials(scene, location);

            // Container for geometry results and draw commands
            List<VxDrawCommand> allCommands = new ArrayList<>();

            // Process geometry directly into a ByteBuffer compatible with VxStaticVertexLayout
            ByteBuffer geometryBuffer = processStaticGeometry(scene, materials, allCommands);

            // Allocate the geometry in the centralized Static Arena.
            VxArenaMesh arenaMesh = VxArenaManager.getInstance()
                    .getArena(VxStaticVertexLayout.getInstance())
                    .allocate(geometryBuffer, allCommands, null);

            // Process Hierarchy and Command Mapping for Rigid Body Animation
            VxNode rootNode = processNodeHierarchy(scene.mRootNode(), null);
            Map<String, List<VxDrawCommand>> nodeCommands = mapNodesToCommands(scene.mRootNode(), allCommands);

            // Parse animations
            Map<String, VxAnimation> animations = parseAnimations(scene);

            // Create a skeleton wrapper for the static hierarchy.
            VxSkeleton skeleton = new VxSkeleton(rootNode, Collections.emptyList(), new Matrix4f());

            return new VxStaticModel(skeleton, arenaMesh, animations, nodeCommands);

        } finally {
            Assimp.aiReleaseImport(scene);
            freeFileIO(fileIo);
        }
    }

    /**
     * Imports a file as a {@link VxSkinnedModel} suitable for vertex skinning.
     * <p>
     * This method parses bone weights, indices, and the skeleton hierarchy.
     * The geometry is allocated into the {@link VxSkinnedVertexLayout} arena (Source Data).
     *
     * @param location The resource location of the model file.
     * @return A constructed skinned model.
     * @throws RuntimeException If the model fails to load.
     */
    public static VxSkinnedModel loadSkinned(VxResourceLocation location) {
        AIFileIO fileIo = createFileIO(location);

        // Manually extract the filename from the path
        String rawPath = location.getPath();
        String fileName = rawPath.substring(rawPath.lastIndexOf('/') + 1);

        AIScene scene = Assimp.aiImportFileEx(fileName, POST_PROCESS_FLAGS, fileIo);

        if (scene == null) {
            throw new RuntimeException("Assimp failed to load skinned model [" + location + "]: " + Assimp.aiGetErrorString());
        }

        try {
            List<VxMaterial> materials = parseMaterials(scene, location);

            List<VxDrawCommand> allCommands = new ArrayList<>();
            List<BoneDefinition> boneDefinitions = new ArrayList<>();

            // Process geometry into a buffer compatible with VxSkinnedVertexLayout
            ByteBuffer geometryBuffer = processSkinnedGeometry(scene, materials, allCommands, boneDefinitions);

            // Build Scene Hierarchy (Nodes)
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

            return new VxSkinnedModel(skeleton, sourceMesh, animations);

        } finally {
            Assimp.aiReleaseImport(scene);
            freeFileIO(fileIo);
        }
    }

    // ==============================================================================================
    //                                  CUSTOM FILE IO SYSTEM
    // ==============================================================================================

    /**
     * Creates an {@link AIFileIO} structure that redirects Assimp's file requests
     * to the Java Classpath relative to the given model location.
     * <p>
     * This allows Assimp to automatically find sibling files like .mtl, .bin, or textures
     * even when packaged inside a JAR file.
     *
     * @param baseLocation The location of the main model file, used as a reference for relative paths.
     * @return An allocated AIFileIO structure with active callbacks.
     */
    private static AIFileIO createFileIO(VxResourceLocation baseLocation) {
        AIFileIO fileIo = AIFileIO.calloc();

        // --- OPEN CALLBACK ---
        // Called when Assimp wants to open a file (e.g., "ball.mtl" or "../textures/skin.png")
        fileIo.OpenProc((pFileIO, pFileName, openMode) -> {
            String requestedFileName = MemoryUtil.memUTF8(pFileName);

            // 1. Resolve the absolute path in the classpath using our new helper.
            // This fixes the "invisible model" issue by removing "." and ".." from the path.
            String baseDir = baseLocation.getDirectory();
            String fullPath = resolveClasspathPath(baseDir, requestedFileName);

            ByteBuffer data;
            try {
                // 2. Load the actual bytes from classpath
                data = loadResourceToBuffer(fullPath);
            } catch (Exception e) {
                // Return NULL (0) to tell Assimp the file was not found.
                // Assimp will continue loading the geometry but materials might be missing.
                return 0;
            }

            // Create a custom AIFile struct to represent this open file
            AIFile aiFile = AIFile.calloc();

            // --- READ CALLBACK ---
            aiFile.ReadProc((pFile, pBuffer, size, count) -> {
                long bytesToRead = size * count;
                long bytesRemaining = data.remaining();
                long actualBytesToRead = Math.min(bytesRemaining, bytesToRead);

                if (actualBytesToRead > 0) {
                    MemoryUtil.memCopy(MemoryUtil.memAddress(data) + data.position(), pBuffer, actualBytesToRead);
                    data.position(data.position() + (int) actualBytesToRead);
                }

                return (size > 0) ? (actualBytesToRead / size) : 0;
            });

            // --- SEEK CALLBACK ---
            aiFile.SeekProc((pFile, offset, origin) -> {
                int newPosition = 0;
                switch (origin) {
                    case Assimp.aiOrigin_SET:
                        newPosition = (int) offset;
                        break;
                    case Assimp.aiOrigin_CUR:
                        newPosition = data.position() + (int) offset;
                        break;
                    case Assimp.aiOrigin_END:
                        newPosition = data.limit() + (int) offset;
                        break;
                }
                // Clamp to valid range
                newPosition = Math.max(0, Math.min(newPosition, data.limit()));
                data.position(newPosition);
                return 0; // Success
            });

            // --- TELL/SIZE/FLUSH CALLBACKS ---
            aiFile.TellProc((pFile) -> data.position());
            aiFile.FileSizeProc((pFile) -> data.limit());
            aiFile.FlushProc((pFile) -> {});

            // Store the data buffer address in UserData so we can free it later
            aiFile.UserData(MemoryUtil.memAddress(data));

            return aiFile.address();
        });

        // --- CLOSE CALLBACK ---
        fileIo.CloseProc((pFileIO, pFile) -> {
            AIFile aiFile = AIFile.create(pFile);

            // Retrieve the ByteBuffer address we stored and free it
            long bufferAddr = aiFile.UserData();
            if (bufferAddr != 0) {
                MemoryUtil.memFree(MemoryUtil.memByteBuffer(bufferAddr, 0));
            }

            aiFile.free();
        });

        return fileIo;
    }

    /**
     * Frees the callbacks and memory associated with the custom AIFileIO.
     *
     * @param fileIo The file IO structure to free.
     */
    private static void freeFileIO(AIFileIO fileIo) {
        // Free the closure objects (Callbacks)
        fileIo.OpenProc().free();
        fileIo.CloseProc().free();
        // Free the struct
        fileIo.free();
    }

    /**
     * Reads a resource from the classpath into a Direct ByteBuffer allocated via LWJGL MemoryUtil.
     * <p>
     * <b>Note:</b> The returned buffer is off-heap native memory. The caller is responsible
     * for freeing it via {@link MemoryUtil#memFree(java.nio.Buffer)}.
     *
     * @param path The classpath path (absolute).
     * @return A Direct ByteBuffer containing the file bytes.
     * @throws IOException If the resource cannot be found or read.
     */
    private static ByteBuffer loadResourceToBuffer(String path) throws IOException {
        // Ensure path starts with / for classpath lookup if it doesn't already
        String classpathPath = path.startsWith("/") ? path : "/" + path;

        try (InputStream stream = VxAssimpLoader.class.getResourceAsStream(classpathPath)) {
            if (stream == null) {
                throw new IOException("Resource not found in classpath: " + path);
            }
            byte[] bytes = stream.readAllBytes();

            // Allocate native memory matching the file size
            ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
            buffer.put(bytes);
            buffer.flip(); // Prepare for reading
            return buffer;
        }
    }

    // ==============================================================================================
    //                                  MATERIAL & SCENE PARSING
    // ==============================================================================================

    /**
     * Extracts material properties and resolves texture paths.
     * Because we use the custom FileIO, Assimp automatically parses linked .mtl files.
     *
     * @param scene         The Assimp scene.
     * @param modelLocation The location of the loaded model file (used for relative paths).
     * @return A list of materials.
     */
    private static List<VxMaterial> parseMaterials(AIScene scene, VxResourceLocation modelLocation) {
        int numMaterials = scene.mNumMaterials();
        List<VxMaterial> result = new ArrayList<>(numMaterials);
        String modelDir = modelLocation.getDirectory();

        for (int i = 0; i < numMaterials; i++) {
            AIMaterial aiMat = AIMaterial.create(scene.mMaterials().get(i));

            // Get material name or generate a fallback
            AIString aiName = AIString.calloc();
            Assimp.aiGetMaterialString(aiMat, Assimp.AI_MATKEY_NAME, 0, 0, aiName);
            String matName = aiName.dataString();
            if (matName.isEmpty()) {
                matName = "mat_" + i;
            }
            aiName.free();

            VxMaterial mat = new VxMaterial(matName);

            // 1. Read Diffuse Color (Kd)
            // Assimp fills this from the MTL file correctly now
            AIColor4D color = AIColor4D.calloc();
            int resultColor = Assimp.aiGetMaterialColor(aiMat, Assimp.AI_MATKEY_COLOR_DIFFUSE, Assimp.aiTextureType_NONE, 0, color);

            if (resultColor == Assimp.aiReturn_SUCCESS) {
                mat.baseColorFactor[0] = color.r();
                mat.baseColorFactor[1] = color.g();
                mat.baseColorFactor[2] = color.b();
                mat.baseColorFactor[3] = color.a();
            }
            color.free();

            // 2. Read Diffuse Texture
            // The path stored here is what was in the MTL (e.g., "texture.png")
            AIString path = AIString.calloc();
            int resultTex = Assimp.aiGetMaterialTexture(aiMat, Assimp.aiTextureType_DIFFUSE, 0, path, (IntBuffer) null, null, null, null, null, null);

            if (resultTex == Assimp.aiReturn_SUCCESS) {
                String rawPath = path.dataString();
                if (!rawPath.isEmpty()) {
                    // Resolve path relative to the model location
                    mat.albedoMap = new VxResourceLocation(modelDir, rawPath);
                }
            }
            path.free();

            result.add(mat);
        }
        return result;
    }

    // ==============================================================================================
    //                                  STATIC GEOMETRY PROCESSING
    // ==============================================================================================

    /**
     * Flattens the scene meshes into a single vertex buffer using the {@link VxStaticVertexLayout}.
     * <p>
     * This method performs mesh "unrolling" (de-indexing). It iterates over the faces (triangles)
     * defined by Assimp and duplicates vertices as needed to create a flat array compatible
     * with {@code glDrawArrays}.
     */
    private static ByteBuffer processStaticGeometry(AIScene scene, List<VxMaterial> materials, List<VxDrawCommand> outCommands) {
        int numMeshes = scene.mNumMeshes();

        // Calculate total vertices based on Faces * 3 (since we are triangulating)
        int totalVertices = 0;
        for (int i = 0; i < numMeshes; i++) {
            AIMesh mesh = AIMesh.create(scene.mMeshes().get(i));
            totalVertices += mesh.mNumFaces() * 3;
        }

        // Allocate the buffer (Off-heap direct buffer)
        ByteBuffer buffer = ByteBuffer.allocateDirect(totalVertices * VxStaticVertexLayout.STRIDE).order(ByteOrder.nativeOrder());
        int vertexOffset = 0;

        for (int i = 0; i < numMeshes; i++) {
            AIMesh aiMesh = AIMesh.create(scene.mMeshes().get(i));
            VxMaterial mat = materials.get(aiMesh.mMaterialIndex());

            // The number of vertices to draw is the number of faces * 3
            int count = aiMesh.mNumFaces() * 3;

            writeStaticMeshToBuffer(buffer, aiMesh);

            outCommands.add(new VxDrawCommand(mat, vertexOffset, count));
            vertexOffset += count;
        }

        buffer.flip();
        return buffer;
    }

    /**
     * Writes a single mesh's vertices to the buffer by iterating over faces.
     */
    private static void writeStaticMeshToBuffer(ByteBuffer buffer, AIMesh mesh) {
        AIVector3D.Buffer verts = mesh.mVertices();
        AIVector3D.Buffer norms = mesh.mNormals();
        AIVector3D.Buffer uvs = mesh.mTextureCoords(0);
        AIVector3D.Buffer tans = mesh.mTangents();
        AIFace.Buffer faces = mesh.mFaces();

        int numFaces = mesh.mNumFaces();

        // Iterate over every face (triangle) defined in the file
        for (int i = 0; i < numFaces; i++) {
            AIFace face = faces.get(i);

            // Assuming aiProcess_Triangulate was used, numIndices is always 3
            for (int j = 0; j < face.mNumIndices(); j++) {
                int vertexIndex = face.mIndices().get(j);

                AIVector3D v = verts.get(vertexIndex);
                AIVector3D n = (norms != null) ? norms.get(vertexIndex) : null;
                AIVector3D uv = (uvs != null) ? uvs.get(vertexIndex) : null;
                AIVector3D t = (tans != null) ? tans.get(vertexIndex) : null;

                // 1. Position (3 floats)
                buffer.putFloat(v.x()).putFloat(v.y()).putFloat(v.z());

                // 2. Color (4 bytes) - Default to White
                buffer.putInt(0xFFFFFFFF);

                // 3. UV0 (2 floats)
                if (uv != null) buffer.putFloat(uv.x()).putFloat(uv.y());
                else buffer.putFloat(0f).putFloat(0f);

                // 4. UV2 Lightmap (2 shorts) - Placeholder
                buffer.putShort((short) 0).putShort((short) 0);

                // 5. Normal (3 bytes, normalized to 0-255 range for GL_BYTE)
                if (n != null) {
                    buffer.put((byte) (n.x() * 127)).put((byte) (n.y() * 127)).put((byte) (n.z() * 127));
                } else {
                    buffer.put((byte) 0).put((byte) 127).put((byte) 0); // Default Up
                }

                // 6. Tangent (4 bytes, normalized)
                if (t != null) {
                    buffer.put((byte) (t.x() * 127)).put((byte) (t.y() * 127)).put((byte) (t.z() * 127)).put((byte) 127);
                } else {
                    buffer.put((byte) 127).put((byte) 0).put((byte) 0).put((byte) 127);
                }

                // 7. Padding (1 byte) to align structure
                buffer.put((byte) 0);

                // 8. MidTexCoord (2 floats) - often used for wind/foliage logic
                if (uv != null) buffer.putFloat(uv.x()).putFloat(uv.y());
                else buffer.putFloat(0f).putFloat(0f);
            }
        }
    }

    // ==============================================================================================
    //                                  SKINNED GEOMETRY PROCESSING
    // ==============================================================================================

    /**
     * Flattens geometry into the {@link VxSkinnedVertexLayout} by de-indexing the faces.
     * Also extracts bone definitions and maps weights.
     */
    private static ByteBuffer processSkinnedGeometry(AIScene scene, List<VxMaterial> materials, List<VxDrawCommand> outCommands, List<BoneDefinition> boneDefinitions) {
        int numMeshes = scene.mNumMeshes();
        int totalVertices = 0;

        // Calculate size based on Faces * 3
        for (int i = 0; i < numMeshes; i++) {
            totalVertices += AIMesh.create(scene.mMeshes().get(i)).mNumFaces() * 3;
        }

        ByteBuffer buffer = ByteBuffer.allocateDirect(totalVertices * VxSkinnedVertexLayout.STRIDE).order(ByteOrder.nativeOrder());
        int vertexOffset = 0;

        // Map global bone names to IDs across all meshes
        Map<String, Integer> globalBoneIndexMap = new HashMap<>();

        for (int i = 0; i < numMeshes; i++) {
            AIMesh aiMesh = AIMesh.create(scene.mMeshes().get(i));
            VxMaterial mat = materials.get(aiMesh.mMaterialIndex());

            int count = aiMesh.mNumFaces() * 3;

            writeSkinnedMeshToBuffer(buffer, aiMesh, globalBoneIndexMap, boneDefinitions);

            outCommands.add(new VxDrawCommand(mat, vertexOffset, count));
            vertexOffset += count;
        }

        buffer.flip();
        return buffer;
    }

    private static void writeSkinnedMeshToBuffer(ByteBuffer buffer, AIMesh mesh, Map<String, Integer> globalBoneMap, List<BoneDefinition> boneDefs) {
        int originalVertexCount = mesh.mNumVertices();

        // 1. Pre-process Bone Weights for the ORIGINAL vertices.
        // We cannot write these directly to the buffer yet because we need to unroll the faces later.
        float[] tempWeights = new float[originalVertexCount * 4];
        float[] tempIndices = new float[originalVertexCount * 4];
        Arrays.fill(tempIndices, 0f);

        for (int i = 0; i < mesh.mNumBones(); i++) {
            AIBone aiBone = AIBone.create(mesh.mBones().get(i));
            String name = aiBone.mName().dataString();

            // Register bone if new
            int boneId = globalBoneMap.computeIfAbsent(name, k -> {
                int newId = boneDefs.size();
                boneDefs.add(new BoneDefinition(newId, name, toJomlMatrix(aiBone.mOffsetMatrix())));
                return newId;
            });

            // Map weights to vertices
            for (int wIdx = 0; wIdx < aiBone.mNumWeights(); wIdx++) {
                AIVertexWeight w = aiBone.mWeights().get(wIdx);
                int vertexId = w.mVertexId();
                float weightVal = w.mWeight();

                // Find first empty slot (max 4 weights per vertex)
                for (int slot = 0; slot < 4; slot++) {
                    if (tempWeights[vertexId * 4 + slot] == 0f) {
                        tempWeights[vertexId * 4 + slot] = weightVal;
                        tempIndices[vertexId * 4 + slot] = (float) boneId;
                        break;
                    }
                }
            }
        }

        // 2. Write Unrolled Vertex Data by iterating Faces
        AIVector3D.Buffer verts = mesh.mVertices();
        AIVector3D.Buffer uvs = mesh.mTextureCoords(0);
        AIVector3D.Buffer norms = mesh.mNormals();
        AIVector3D.Buffer tans = mesh.mTangents();
        AIFace.Buffer faces = mesh.mFaces();
        int numFaces = mesh.mNumFaces();

        for (int i = 0; i < numFaces; i++) {
            AIFace face = faces.get(i);

            for (int j = 0; j < face.mNumIndices(); j++) {
                int originalIndex = face.mIndices().get(j);

                AIVector3D v = verts.get(originalIndex);
                AIVector3D uv = (uvs != null) ? uvs.get(originalIndex) : null;
                AIVector3D n = (norms != null) ? norms.get(originalIndex) : null;
                AIVector3D t = (tans != null) ? tans.get(originalIndex) : null;

                // Position
                buffer.putFloat(v.x()).putFloat(v.y()).putFloat(v.z());

                // UV0
                if (uv != null) buffer.putFloat(uv.x()).putFloat(uv.y());
                else buffer.putFloat(0f).putFloat(0f);

                // Normal
                if (n != null) buffer.putFloat(n.x()).putFloat(n.y()).putFloat(n.z());
                else buffer.putFloat(0f).putFloat(1f).putFloat(0f);

                // Tangent
                if (t != null) buffer.putFloat(t.x()).putFloat(t.y()).putFloat(t.z()).putFloat(1f);
                else buffer.putFloat(1f).putFloat(0f).putFloat(0f).putFloat(1f);

                // Bone Weights & Indices (Look up using the original index)
                int baseIdx = originalIndex * 4;
                buffer.putFloat(tempWeights[baseIdx]).putFloat(tempWeights[baseIdx + 1])
                        .putFloat(tempWeights[baseIdx + 2]).putFloat(tempWeights[baseIdx + 3]);

                buffer.putFloat(tempIndices[baseIdx]).putFloat(tempIndices[baseIdx + 1])
                        .putFloat(tempIndices[baseIdx + 2]).putFloat(tempIndices[baseIdx + 3]);
            }
        }
    }

    // ==============================================================================================
    //                                  HIERARCHY & ANIMATION PARSING
    // ==============================================================================================

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
     * Essential for Rigid Body animations where specific parts (Nodes) need to be rendered
     * separately with their own transforms.
     */
    private static Map<String, List<VxDrawCommand>> mapNodesToCommands(AINode root, List<VxDrawCommand> allCommands) {
        Map<String, List<VxDrawCommand>> map = new HashMap<>();
        collectNodeCommands(root, allCommands, map);
        return map;
    }

    /**
     * Recursively walks the tree to find which meshes belong to which node.
     */
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
                // If the bone node is missing in the hierarchy, fallback to root.
                node = root;
            }

            finalBones.add(new VxBone(def.id, def.name, def.offsetMatrix, node));
        }
        return finalBones;
    }

    /**
     * Parses all animations present in the scene.
     */
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

    /**
     * Converts a single Assimp animation to the engine's format.
     */
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
                // Assimp Quaternions are (w, x, y, z), JOML is (x, y, z, w). Reordering is necessary.
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

    // ==============================================================================================
    //                                  UTILITIES
    // ==============================================================================================

    /**
     * Helper method to convert Assimp 4x4 matrices (Row-Majorish) to JOML Matrix4f (Column-Major).
     */
    private static Matrix4f toJomlMatrix(AIMatrix4x4 m) {
        return new Matrix4f(
                m.a1(), m.b1(), m.c1(), m.d1(),
                m.a2(), m.b2(), m.c2(), m.d2(),
                m.a3(), m.b3(), m.c3(), m.d3(),
                m.a4(), m.b4(), m.c4(), m.d4()
        );
    }

    /**
     * Resolves a target path relative to a base directory, logically handling "." and ".." segments.
     * <p>
     * This is critical because {@link ClassLoader#getResourceAsStream(String)} returns null
     * if the path contains relative segments (non-canonical paths), causing Assimp to silently
     * fail loading .mtl files or textures.
     *
     * @param baseDir The directory of the parent file (must end with '/').
     * @param target  The relative or absolute path to the target file requested by Assimp.
     * @return A clean, absolute classpath string without relative segments.
     */
    private static String resolveClasspathPath(String baseDir, String target) {
        // Standardize separators to forward slashes
        String normalizedTarget = target.replace('\\', '/');

        // If the target is already absolute (starts with root), use it directly.
        // This happens if the OBJ file specifies an absolute path like "/textures/skin.png".
        if (normalizedTarget.startsWith("/")) {
            return normalizedTarget;
        }

        // Combine base directory and target
        String combined = baseDir + normalizedTarget;
        String[] parts = combined.split("/");

        // Use a Deque to resolve ".." and "."
        Deque<String> stack = new ArrayDeque<>();

        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) {
                // Skip empty parts (double slashes) or current directory markers
                continue;
            } else if (part.equals("..")) {
                // Go up one directory if possible
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
            } else {
                stack.addLast(part);
            }
        }

        // Reassemble the path
        return String.join("/", stack);
    }

    /**
     * Intermediate Data Transfer Object (DTO) for holding bone information
     * extracted from mesh geometry before the Node hierarchy is available.
     */
    private record BoneDefinition(int id, String name, Matrix4f offsetMatrix) {}
}