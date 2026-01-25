/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.loader.gltf;

import de.javagl.jgltf.model.*;
import de.javagl.jgltf.model.io.GltfModelReader;
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
import net.xmx.velgfx.renderer.model.morph.VxMorphController;
import net.xmx.velgfx.renderer.model.morph.VxMorphTarget;
import net.xmx.velgfx.renderer.model.skeleton.VxSkeleton;
import net.xmx.velgfx.resources.VxResourceLocation;
import org.joml.Matrix4f;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A comprehensive loader for glTF 2.0 and GLB assets using the SoA architecture.
 * <p>
 * This class coordinates the parsing of the file, the flattening of the hierarchy,
 * and the construction of the efficient runtime models.
 *
 * @author xI-Mx-Ix
 */
public class VxGltfLoader {

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private VxGltfLoader() {
    }

    /**
     * Loads a file as a static model with rigid body support.
     *
     * @param location The resource location.
     * @return The static model.
     */
    public static VxStaticModel loadStatic(VxResourceLocation location) {
        GltfModel gltfModel = readModelFromClasspath(location);
        List<VxMaterial> materials = VxGltfMaterial.parseMaterials(gltfModel);
        List<VxDrawCommand> allCommands = new ArrayList<>();

        // Process geometry
        VxGltfGeometry.GeometryResult geometry = VxGltfGeometry.processStaticGeometry(gltfModel, materials, allCommands);

        // Upload to arena
        VxArenaMesh arenaMesh = VxArenaManager.getInstance()
                .getArena(VxStaticVertexLayout.getInstance())
                .allocate(geometry.vertices, geometry.indices, allCommands, null);

        // Build Skeleton Definition from Scene Hierarchy
        SceneModel scene = gltfModel.getSceneModels().get(0);
        NodeModel root = scene.getNodeModels().get(0);

        // Use buildSkeleton which returns the master skeleton directly
        VxSkeleton skeleton = VxGltfStructure.buildSkeleton(root, null);

        // Map node commands based on bone indices
        Map<Integer, List<VxDrawCommand>> boneCommands = mapCommands(gltfModel, scene, allCommands, skeleton);

        // Pass skeleton to animation parser instead of definition
        Map<String, VxAnimation> animations = VxGltfAnimation.parseAnimations(gltfModel, skeleton);

        return new VxStaticModel(skeleton, arenaMesh, animations, boneCommands);
    }

    /**
     * Loads a file as a skinned model supporting skeletal animation.
     * <p>
     * This method orchestrates the following:
     * <ol>
     *     <li>Extracts Inverse Bind Matrices from the skin definition.</li>
     *     <li>Builds the {@link VxSkeleton} hierarchy first.</li>
     *     <li>Creates a "Bake Map" that links local glTF skin joints to global skeleton indices.</li>
     *     <li>Processes the geometry, baking the remapped indices directly into the vertex buffer.</li>
     *     <li>Loads animations and morph targets.</li>
     * </ol>
     *
     * @param location The resource location of the glTF/GLB file.
     * @return The loaded skinned model.
     */
    public static VxSkinnedModel loadSkinned(VxResourceLocation location) {
        GltfModel gltfModel = readModelFromClasspath(location);
        List<VxMaterial> materials = VxGltfMaterial.parseMaterials(gltfModel);
        List<VxDrawCommand> allCommands = new ArrayList<>();
        List<VxGltfStructure.BoneDefinition> boneDefs = new ArrayList<>();

        // 1. Extract Skin Definitions (Inverse Bind Matrices) manually.
        // We do this before creating the skeleton to ensure the IBMs are populated.
        if (!gltfModel.getSkinModels().isEmpty()) {
            SkinModel skin = gltfModel.getSkinModels().get(0);
            for (int i = 0; i < skin.getJoints().size(); i++) {
                NodeModel node = skin.getJoints().get(i);
                Matrix4f ibm = new Matrix4f();

                AccessorModel accessor = skin.getInverseBindMatrices();
                if (accessor != null) {
                    float[] m = VxGltfAccessorUtil.readAccessorAsFloats(accessor);
                    float[] matData = new float[16];
                    System.arraycopy(m, i * 16, matData, 0, 16);
                    ibm.set(matData);
                }
                boneDefs.add(new VxGltfStructure.BoneDefinition(i, node.getName(), ibm));
            }
        }

        SceneModel scene = gltfModel.getSceneModels().get(0);
        NodeModel root = scene.getNodeModels().get(0);

        // 2. Build the Master Skeleton.
        VxSkeleton skeleton = VxGltfStructure.buildSkeleton(root, boneDefs);

        // 3. Create the Bake Map.
        // Maps: [Skin Joint List Index] -> [Global Skeleton Array Index].
        // This map is passed to the geometry processor to rewrite vertex attributes.
        int[] jointBakeMap = null;
        if (!gltfModel.getSkinModels().isEmpty()) {
            SkinModel skin = gltfModel.getSkinModels().get(0);
            List<NodeModel> joints = skin.getJoints();
            jointBakeMap = new int[joints.size()];

            for (int i = 0; i < joints.size(); i++) {
                String nodeName = joints.get(i).getName();
                int skeletonIndex = skeleton.indexOf(nodeName);

                if (skeletonIndex == -1) {
                    VelGFX.LOGGER.warn("Skin joint '{}' defined in glTF but not found in Skeleton hierarchy.", nodeName);
                    skeletonIndex = 0; // Fallback to root to prevent crash
                }
                jointBakeMap[i] = skeletonIndex;
            }
        }

        // 4. Process Geometry with Vertex Baking.
        // The jointBakeMap allows the vertices to point directly to the correct matrix slot.
        VxGltfGeometry.GeometryResult geometry = VxGltfGeometry.processSkinnedGeometry(
                gltfModel, materials, allCommands, jointBakeMap
        );

        // 5. Load remaining components.
        List<VxMorphTarget> morphTargets = VxGltfMorphLoader.extractMorphTargets(gltfModel);
        VxMorphController morphController = !morphTargets.isEmpty() ? new VxMorphController(morphTargets) : null;

        Map<String, VxAnimation> animations = VxGltfAnimation.parseAnimations(gltfModel, skeleton);

        // 6. Upload baked geometry to the GPU Arena.
        VxArenaMesh sourceMesh = VxArenaManager.getInstance()
                .getArena(VxSkinnedVertexLayout.getInstance())
                .allocate(geometry.vertices, geometry.indices, allCommands, null);

        return new VxSkinnedModel(skeleton, sourceMesh, animations, morphController);
    }

    /**
     * Maps global draw commands to specific bones/nodes based on their mesh assignment.
     * <p>
     * This allows the renderer to know which parts of the mesh belong to which rigid body node.
     */
    private static Map<Integer, List<VxDrawCommand>> mapCommands(GltfModel model, SceneModel scene, List<VxDrawCommand> allCommands, VxSkeleton skeleton) {
        Map<MeshModel, List<VxDrawCommand>> meshToCommands = new HashMap<>();
        int globalIdx = 0;

        for (MeshModel mesh : model.getMeshModels()) {
            List<VxDrawCommand> cmds = new ArrayList<>();
            int primCount = mesh.getMeshPrimitiveModels().size();
            for (int i = 0; i < primCount; i++) {
                if (globalIdx < allCommands.size()) cmds.add(allCommands.get(globalIdx++));
            }
            meshToCommands.put(mesh, cmds);
        }

        Map<Integer, List<VxDrawCommand>> result = new HashMap<>();

        // Access skeleton.boneCount directly
        for (int i = 0; i < skeleton.boneCount; i++) {
            String nodeName = skeleton.names[i];

            NodeModel node = findNode(scene.getNodeModels(), nodeName);
            if (node != null) {
                // If the node has meshes, assign their commands to this bone index
                for (MeshModel m : node.getMeshModels()) {
                    List<VxDrawCommand> c = meshToCommands.get(m);
                    if (c != null) {
                        result.computeIfAbsent(i, k -> new ArrayList<>()).addAll(c);
                    }
                }
            }
        }
        return result;
    }

    // Helper to find a node by name in the glTF graph
    private static NodeModel findNode(List<NodeModel> nodes, String name) {
        for (NodeModel n : nodes) {
            if (name.equals(n.getName())) return n;
            NodeModel res = findNode(n.getChildren(), name);
            if (res != null) return res;
        }
        return null;
    }

    private static GltfModel readModelFromClasspath(VxResourceLocation location) {
        String path = location.getPath();
        if (!path.startsWith("/")) path = "/" + path;
        try {
            URL url = VxGltfLoader.class.getResource(path);
            if (url == null) throw new RuntimeException("Model not found: " + path);
            return new GltfModelReader().read(url.toURI());
        } catch (Exception e) {
            VelGFX.LOGGER.error("Failed to read glTF: " + path, e);
            throw new RuntimeException("glTF Import Error", e);
        }
    }
}