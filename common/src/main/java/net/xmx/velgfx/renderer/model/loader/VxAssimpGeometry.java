/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.loader;

import net.xmx.velgfx.renderer.gl.VxDrawCommand;
import net.xmx.velgfx.renderer.gl.VxIndexBuffer;
import net.xmx.velgfx.renderer.gl.layout.VxSkinnedVertexLayout;
import net.xmx.velgfx.renderer.gl.layout.VxStaticVertexLayout;
import net.xmx.velgfx.renderer.gl.material.VxMaterial;
import org.lwjgl.assimp.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * Handles the extraction of mesh geometry from Assimp data structures.
 * <p>
 * This class processes {@link AIMesh} objects into indexed vertex buffers. It utilizes
 * 32-bit indices to support large meshes and optimizes vertex usage by utilizing the
 * indices provided by Assimp's {@code aiProcess_JoinIdenticalVertices}.
 *
 * @author xI-Mx-Ix
 */
public class VxAssimpGeometry {

    /**
     * Container for processed geometry data ready for GPU upload.
     */
    public static class GeometryResult {
        public final ByteBuffer vertices;
        public final ByteBuffer indices;

        public GeometryResult(ByteBuffer vertices, ByteBuffer indices) {
            this.vertices = vertices;
            this.indices = indices;
        }
    }

    /**
     * Processes the scene meshes into merged, indexed static geometry.
     *
     * @param scene       The imported scene.
     * @param materials   The parsed materials.
     * @param outCommands A list to populate with draw commands.
     * @return The result containing Vertex and Index buffers.
     */
    public static GeometryResult processStaticGeometry(AIScene scene, List<VxMaterial> materials, List<VxDrawCommand> outCommands) {
        int numMeshes = scene.mNumMeshes();

        // 1. Pre-calculate total sizes to allocate buffers exactly once
        int totalVertices = 0;
        int totalIndices = 0;

        for (int i = 0; i < numMeshes; i++) {
            AIMesh mesh = AIMesh.create(scene.mMeshes().get(i));
            totalVertices += mesh.mNumVertices();
            totalIndices += mesh.mNumFaces() * 3; // Assumes Triangulate flag was used
        }

        if (totalVertices == 0) {
            return new GeometryResult(MemoryUtil.memAlloc(0), MemoryUtil.memAlloc(0));
        }

        // 2. Allocate Direct Buffers
        ByteBuffer vBuffer = ByteBuffer.allocateDirect(totalVertices * VxStaticVertexLayout.STRIDE).order(ByteOrder.nativeOrder());
        ByteBuffer iBuffer = ByteBuffer.allocateDirect(totalIndices * VxIndexBuffer.BYTES_PER_INDEX).order(ByteOrder.nativeOrder());

        // 3. Process Meshes
        // We merge multiple Assimp meshes into one buffer.
        // - vertexOffset tracks the current vertex position in the merged VBO.
        // - indexOffsetBytes tracks the current byte position in the merged EBO.
        int vertexOffset = 0;
        long indexOffsetBytes = 0;

        for (int i = 0; i < numMeshes; i++) {
            AIMesh aiMesh = AIMesh.create(scene.mMeshes().get(i));
            VxMaterial mat = materials.get(aiMesh.mMaterialIndex());

            int meshVertexCount = aiMesh.mNumVertices();
            int meshIndexCount = aiMesh.mNumFaces() * 3;

            // Write Vertices
            writeStaticVertices(vBuffer, aiMesh);

            // Write Indices (0-based local to the mesh)
            writeIndicesLocal(iBuffer, aiMesh);

            // Create Draw Command
            // We set baseVertex to 'vertexOffset'. This allows glDrawElementsBaseVertex to
            // automatically shift the 0-based indices to the correct location in the VBO.
            outCommands.add(new VxDrawCommand(mat, meshIndexCount, indexOffsetBytes, vertexOffset));

            vertexOffset += meshVertexCount;
            indexOffsetBytes += (long) meshIndexCount * VxIndexBuffer.BYTES_PER_INDEX;
        }

        vBuffer.flip();
        iBuffer.flip();
        return new GeometryResult(vBuffer, iBuffer);
    }

    /**
     * Processes the scene meshes into merged, indexed skinned geometry.
     *
     * @param scene           The imported scene.
     * @param materials       The parsed materials.
     * @param outCommands     A list to populate with draw commands.
     * @param boneDefinitions A list to populate with bone definitions.
     * @return The result containing Vertex and Index buffers.
     */
    public static GeometryResult processSkinnedGeometry(AIScene scene, List<VxMaterial> materials, List<VxDrawCommand> outCommands, List<VxAssimpStructure.BoneDefinition> boneDefinitions) {
        int numMeshes = scene.mNumMeshes();
        int totalVertices = 0;
        int totalIndices = 0;

        for (int i = 0; i < numMeshes; i++) {
            AIMesh mesh = AIMesh.create(scene.mMeshes().get(i));
            totalVertices += mesh.mNumVertices();
            totalIndices += mesh.mNumFaces() * 3;
        }

        ByteBuffer vBuffer = ByteBuffer.allocateDirect(totalVertices * VxSkinnedVertexLayout.STRIDE).order(ByteOrder.nativeOrder());
        ByteBuffer iBuffer = ByteBuffer.allocateDirect(totalIndices * VxIndexBuffer.BYTES_PER_INDEX).order(ByteOrder.nativeOrder());

        int vertexOffset = 0;
        long indexOffsetBytes = 0;
        Map<String, Integer> globalBoneIndexMap = new HashMap<>();

        for (int i = 0; i < numMeshes; i++) {
            AIMesh aiMesh = AIMesh.create(scene.mMeshes().get(i));
            VxMaterial mat = materials.get(aiMesh.mMaterialIndex());
            int meshVertexCount = aiMesh.mNumVertices();
            int meshIndexCount = aiMesh.mNumFaces() * 3;

            writeSkinnedVertices(vBuffer, aiMesh, globalBoneIndexMap, boneDefinitions);
            writeIndicesLocal(iBuffer, aiMesh);

            outCommands.add(new VxDrawCommand(mat, meshIndexCount, indexOffsetBytes, vertexOffset));

            vertexOffset += meshVertexCount;
            indexOffsetBytes += (long) meshIndexCount * VxIndexBuffer.BYTES_PER_INDEX;
        }

        vBuffer.flip();
        iBuffer.flip();
        return new GeometryResult(vBuffer, iBuffer);
    }

    // --- Private Writers ---

    /**
     * Writes 32-bit indices for the mesh faces.
     * Indices are written relative to the start of the mesh (0-based).
     */
    private static void writeIndicesLocal(ByteBuffer buffer, AIMesh mesh) {
        AIFace.Buffer faces = mesh.mFaces();
        for (int i = 0; i < mesh.mNumFaces(); i++) {
            AIFace face = faces.get(i);
            // Assumes triangulation
            buffer.putInt(face.mIndices().get(0));
            buffer.putInt(face.mIndices().get(1));
            buffer.putInt(face.mIndices().get(2));
        }
    }

    private static void writeStaticVertices(ByteBuffer buffer, AIMesh mesh) {
        AIVector3D.Buffer verts = mesh.mVertices();
        AIVector3D.Buffer norms = mesh.mNormals();
        AIVector3D.Buffer uvs = mesh.mTextureCoords(0);
        AIVector3D.Buffer tans = mesh.mTangents();

        for (int i = 0; i < mesh.mNumVertices(); i++) {
            AIVector3D v = verts.get(i);
            AIVector3D n = (norms != null) ? norms.get(i) : null;
            AIVector3D uv = (uvs != null) ? uvs.get(i) : null;
            AIVector3D t = (tans != null) ? tans.get(i) : null;

            // Pos
            buffer.putFloat(v.x()).putFloat(v.y()).putFloat(v.z());
            // Color (Default White)
            buffer.putInt(0xFFFFFFFF);
            // UV0
            if (uv != null) buffer.putFloat(uv.x()).putFloat(uv.y());
            else buffer.putFloat(0f).putFloat(0f);
            // UV2 (Lightmap)
            buffer.putShort((short)0).putShort((short)0);
            // Normal (byte packed)
            if (n != null) buffer.put((byte)(n.x()*127)).put((byte)(n.y()*127)).put((byte)(n.z()*127));
            else buffer.put((byte)0).put((byte)127).put((byte)0);
            // Tangent (byte packed)
            if (t != null) buffer.put((byte)(t.x()*127)).put((byte)(t.y()*127)).put((byte)(t.z()*127)).put((byte)127);
            else buffer.put((byte)127).put((byte)0).put((byte)0).put((byte)127);
            // Padding
            buffer.put((byte)0);
            // MidTex
            if (uv != null) buffer.putFloat(uv.x()).putFloat(uv.y());
            else buffer.putFloat(0f).putFloat(0f);
        }
    }

    private static void writeSkinnedVertices(ByteBuffer buffer, AIMesh mesh, Map<String, Integer> globalBoneMap, List<VxAssimpStructure.BoneDefinition> boneDefs) {
        // First: Parse weights and organize them per-vertex
        int vertexCount = mesh.mNumVertices();
        float[] tempWeights = new float[vertexCount * 4];
        float[] tempIndices = new float[vertexCount * 4];

        for (int i = 0; i < mesh.mNumBones(); i++) {
            AIBone aiBone = AIBone.create(mesh.mBones().get(i));
            String name = aiBone.mName().dataString();
            int boneId = globalBoneMap.computeIfAbsent(name, k -> {
                int newId = boneDefs.size();
                boneDefs.add(new VxAssimpStructure.BoneDefinition(newId, name, VxAssimpStructure.toJomlMatrix(aiBone.mOffsetMatrix())));
                return newId;
            });
            for (int wIdx = 0; wIdx < aiBone.mNumWeights(); wIdx++) {
                AIVertexWeight w = aiBone.mWeights().get(wIdx);
                int vId = w.mVertexId();
                float val = w.mWeight();
                // Find free slot
                for (int slot = 0; slot < 4; slot++) {
                    if (tempWeights[vId * 4 + slot] == 0f) {
                        tempWeights[vId * 4 + slot] = val;
                        tempIndices[vId * 4 + slot] = (float) boneId;
                        break;
                    }
                }
            }
        }

        AIVector3D.Buffer verts = mesh.mVertices();
        AIVector3D.Buffer uvs = mesh.mTextureCoords(0);
        AIVector3D.Buffer norms = mesh.mNormals();
        AIVector3D.Buffer tans = mesh.mTangents();

        for (int i = 0; i < vertexCount; i++) {
            AIVector3D v = verts.get(i);
            AIVector3D uv = (uvs != null) ? uvs.get(i) : null;
            AIVector3D n = (norms != null) ? norms.get(i) : null;
            AIVector3D t = (tans != null) ? tans.get(i) : null;

            buffer.putFloat(v.x()).putFloat(v.y()).putFloat(v.z()); // Pos
            if (uv != null) buffer.putFloat(uv.x()).putFloat(uv.y()); // UV
            else buffer.putFloat(0f).putFloat(0f);
            if (n != null) buffer.putFloat(n.x()).putFloat(n.y()).putFloat(n.z()); // Norm
            else buffer.putFloat(0f).putFloat(1f).putFloat(0f);
            if (t != null) buffer.putFloat(t.x()).putFloat(t.y()).putFloat(t.z()).putFloat(1f); // Tan
            else buffer.putFloat(1f).putFloat(0f).putFloat(0f).putFloat(1f);

            int base = i * 4;
            buffer.putFloat(tempWeights[base]).putFloat(tempWeights[base+1]).putFloat(tempWeights[base+2]).putFloat(tempWeights[base+3]);
            buffer.putFloat(tempIndices[base]).putFloat(tempIndices[base+1]).putFloat(tempIndices[base+2]).putFloat(tempIndices[base+3]);
        }
    }
}