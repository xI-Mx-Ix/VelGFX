/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.loader;

import net.xmx.velgfx.renderer.gl.VxDrawCommand;
import net.xmx.velgfx.renderer.model.skeleton.VxBone;
import net.xmx.velgfx.renderer.model.skeleton.VxNode;
import net.xmx.velgfx.renderer.model.skeleton.VxSkeleton;
import org.joml.Matrix4f;
import org.lwjgl.assimp.AIMatrix4x4;
import org.lwjgl.assimp.AINode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles the structural components of the model, including the Node Hierarchy and Skeleton.
 *
 * @author xI-Mx-Ix
 */
public class VxAssimpStructure {

    /**
     * Record to temporarily store bone info during loading before the final {@link VxSkeleton} is built.
     */
    public record BoneDefinition(int id, String name, Matrix4f offsetMatrix) {}

    /**
     * Recursively processes the Assimp Node graph into a VelGFX {@link VxNode} hierarchy.
     *
     * @param aiNode The current Assimp node.
     * @param parent The parent VxNode (null for root).
     * @return The constructed VxNode tree.
     */
    public static VxNode processNodeHierarchy(AINode aiNode, VxNode parent) {
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
     * Maps specific scene nodes to the DrawCommands (mesh parts) that belong to them.
     * This allows static models to have moving parts (e.g. wheels) by moving the corresponding node.
     */
    public static Map<String, List<VxDrawCommand>> mapNodesToCommands(AINode root, List<VxDrawCommand> allCommands) {
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
     * Constructs the final list of {@link VxBone} objects by linking bone definitions to scene nodes.
     */
    public static List<VxBone> buildSkeletonBones(List<BoneDefinition> definitions, VxNode root) {
        List<VxBone> finalBones = new ArrayList<>(definitions.size());
        for (BoneDefinition def : definitions) {
            VxNode node = root.findByName(def.name);
            if (node == null) node = root; // Fallback to root if node missing
            finalBones.add(new VxBone(def.id, def.name, def.offsetMatrix, node));
        }
        return finalBones;
    }

    /**
     * Converts an Assimp 4x4 Matrix to a JOML Matrix4f.
     */
    public static Matrix4f toJomlMatrix(AIMatrix4x4 m) {
        return new Matrix4f(
                m.a1(), m.b1(), m.c1(), m.d1(),
                m.a2(), m.b2(), m.c2(), m.d2(),
                m.a3(), m.b3(), m.c3(), m.d3(),
                m.a4(), m.b4(), m.c4(), m.d4()
        );
    }
}