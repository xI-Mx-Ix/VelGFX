/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.loader.gltf;

import de.javagl.jgltf.model.NodeModel;
import net.xmx.velgfx.renderer.model.skeleton.VxSkeleton;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper class to flatten the glTF recursive Node hierarchy into the linear, topological
 * SoA structure required by {@link VxSkeleton}.
 *
 * @author xI-Mx-Ix
 */
public class VxGltfStructure {

    /**
     * Flattens the scene hierarchy and builds the master skeleton.
     * <p>
     * This method traverses the node tree, extracts topological order and bind poses,
     * applies any provided inverse bind matrices, and returns a fully initialized
     * {@link VxSkeleton} instance containing all static data.
     *
     * @param rootNode The root node of the glTF scene (or sub-scene).
     * @param boneDefs Extracted inverse bind matrices (optional, for skinned models).
     * @return The master skeleton instance.
     */
    public static VxSkeleton buildSkeleton(NodeModel rootNode, List<BoneDefinition> boneDefs) {
        List<NodeModel> flatList = new ArrayList<>();
        Map<NodeModel, Integer> nodeToIndex = new HashMap<>();

        // Flatten via Pre-Order Traversal.
        // IMPORTANT: Pre-order traversal guarantees that a parent node is always visited
        // added to the list BEFORE its children. This fulfills the Topological Sort requirement.
        flattenRecursive(rootNode, flatList);

        int count = flatList.size();

        // Map nodes to their new linear indices
        for (int i = 0; i < count; i++) {
            nodeToIndex.put(flatList.get(i), i);
        }

        // Allocate definition arrays
        int[] parentIndices = new int[count];
        String[] names = new String[count];
        float[] ibm = new float[count * 16];
        float[] refPos = new float[count * 3];
        float[] refRot = new float[count * 4];
        float[] refScale = new float[count * 3];

        // Fill IBM with identity initially (for non-skinning nodes)
        Matrix4f identity = new Matrix4f();
        for (int i = 0; i < count; i++) {
            identity.get(ibm, i * 16);
        }

        // Populate Arrays
        for (int i = 0; i < count; i++) {
            NodeModel node = flatList.get(i);

            // 1. Topology
            NodeModel parent = node.getParent();
            parentIndices[i] = (parent != null && nodeToIndex.containsKey(parent))
                    ? nodeToIndex.get(parent)
                    : -1;

            // 2. Names
            names[i] = node.getName() != null ? node.getName() : "Node_" + i;

            // 3. Bind Pose (Local TRS)
            float[] t = node.getTranslation();
            float[] r = node.getRotation();
            float[] s = node.getScale();
            float[] m = node.getMatrix();

            Vector3f pos = new Vector3f();
            Quaternionf rot = new Quaternionf();
            Vector3f scale = new Vector3f(1, 1, 1);

            if (m != null) {
                // Decompose matrix
                Matrix4f tempMat = new Matrix4f().set(m);
                tempMat.getTranslation(pos);
                tempMat.getUnnormalizedRotation(rot);
                tempMat.getScale(scale);
            } else {
                if (t != null) pos.set(t[0], t[1], t[2]);
                if (r != null) rot.set(r[0], r[1], r[2], r[3]);
                if (s != null) scale.set(s[0], s[1], s[2]);
            }

            // Write to flattened arrays
            refPos[i * 3] = pos.x;
            refPos[i * 3 + 1] = pos.y;
            refPos[i * 3 + 2] = pos.z;

            refRot[i * 4] = rot.x;
            refRot[i * 4 + 1] = rot.y;
            refRot[i * 4 + 2] = rot.z;
            refRot[i * 4 + 3] = rot.w;

            refScale[i * 3] = scale.x;
            refScale[i * 3 + 1] = scale.y;
            refScale[i * 3 + 2] = scale.z;
        }

        // 4. Populate Inverse Bind Matrices (if provided)
        if (boneDefs != null) {
            for (BoneDefinition def : boneDefs) {
                // Find matching node index by name
                for (int i = 0; i < count; i++) {
                    if (names[i].equals(def.name)) {
                        def.offsetMatrix.get(ibm, i * 16);
                        break;
                    }
                }
            }
        }

        // Return the skeleton directly via the Master Constructor
        return new VxSkeleton(count, parentIndices, names, ibm, refPos, refRot, refScale);
    }

    /**
     * Recursively traverses the node tree pre-order.
     */
    private static void flattenRecursive(NodeModel node, List<NodeModel> list) {
        list.add(node);
        for (NodeModel child : node.getChildren()) {
            flattenRecursive(child, list);
        }
    }

    /**
     * Temporary structure used during skin loading.
     */
    public record BoneDefinition(int id, String name, Matrix4f offsetMatrix) {
    }
}