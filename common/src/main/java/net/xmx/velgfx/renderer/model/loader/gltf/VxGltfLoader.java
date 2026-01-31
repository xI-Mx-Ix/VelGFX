/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.loader.gltf;

import de.javagl.jgltf.model.*;
import de.javagl.jgltf.model.io.GltfModelReader;
import net.xmx.velgfx.VelGFX;
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

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A comprehensive loader for glTF 2.0 and GLB assets.
 * <p>
 * This class serves as the entry point for the glTF loading pipeline. It orchestrates the
 * reading of the file stream and delegates the processing of materials, geometry,
 * scene structure, morph targets, and animations to specialized helper classes.
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
     * Loads a file as a static model suitable for rigid body rendering.
     * <p>
     * A static model parses the node hierarchy but does not include bone weights or
     * vertex skinning data. It builds a command map to allow sub-nodes (like wheels on a car)
     * to be animated via Rigid Body transformations.
     *
     * @param location The resource location of the model file.
     * @return The constructed static model.
     * @throws RuntimeException If the model cannot be loaded or parsed.
     */
    public static VxStaticModel loadStatic(VxResourceLocation location) {
        GltfModel gltfModel = readModelFromClasspath(location);

        // 1. Extract Materials
        // Parses PBR properties (Metallic/Roughness) and uploads textures.
        List<VxMaterial> materials = VxGltfMaterial.parseMaterials(gltfModel);

        // 2. Process Geometry (Vertices, Indices, Normals, Tangents)
        // Flattens all meshes into a single buffer and generates missing attributes.
        List<VxDrawCommand> allCommands = new ArrayList<>();
        VxGltfGeometry.GeometryResult geometry = VxGltfGeometry.processStaticGeometry(gltfModel, materials, allCommands);

        // 3. Upload Geometry to GPU Arena
        // Static models use the Static Vertex Layout (44 bytes, packed data) to save memory.
        VxArenaMesh arenaMesh = VxArenaManager.getInstance()
                .getArena(VxStaticVertexLayout.getInstance())
                .allocate(geometry.vertices, geometry.indices, allCommands, null);

        // 4. Build Node Hierarchy and Skeleton
        SceneModel scene = gltfModel.getSceneModels().get(0);
        List<NodeModel> rootNodes = scene.getNodeModels();

        // Flatten the hierarchy first to create a deterministic list of nodes.
        List<NodeModel> flatNodeList = VxGltfStructure.flatten(rootNodes);

        // Build the skeleton using the flat list.
        // This ensures the skeleton indices correspond 1:1 with the flat list indices.
        VxSkeleton skeleton = VxGltfStructure.buildSkeleton(flatNodeList, null);

        // 5. Extract Animations and Mappings
        // Map Nodes to specific Draw Commands using the flat list to ensure correct assignment
        // even if nodes are unnamed in the source file.
        Map<Integer, List<VxDrawCommand>> boneCommands = mapCommands(gltfModel, flatNodeList, allCommands, skeleton);
        Map<String, VxAnimation> animations = VxGltfAnimation.parseAnimations(gltfModel, skeleton);

        return new VxStaticModel(skeleton, arenaMesh, animations, boneCommands);
    }

    /**
     * Imports a file as a {@link VxSkinnedModel} suitable for skeletal and morph animation.
     * <p>
     * A skinned model includes:
     * <ul>
     *     <li>Vertex Attributes: Weights and Joints for Skinning.</li>
     *     <li>Skeleton: Inverse Bind Matrices.</li>
     *     <li>Morph Targets: Extracted deltas loaded into the Texture Buffer Atlas.</li>
     * </ul>
     *
     * @param location The resource location of the model file.
     * @return A constructed skinned model.
     * @throws RuntimeException If the model cannot be loaded or parsed.
     */
    public static VxSkinnedModel loadSkinned(VxResourceLocation location) {
        GltfModel gltfModel = readModelFromClasspath(location);

        // 1. Extract Materials
        List<VxMaterial> materials = VxGltfMaterial.parseMaterials(gltfModel);

        // 2. Extract Skin Definitions (Inverse Bind Matrices) manually.
        List<VxDrawCommand> allCommands = new ArrayList<>();
        List<VxGltfStructure.BoneDefinition> boneDefinitions = new ArrayList<>();

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
                boneDefinitions.add(new VxGltfStructure.BoneDefinition(i, node.getName(), ibm));
            }
        }

        // 3. Build Node Hierarchy and Skeleton
        SceneModel scene = gltfModel.getSceneModels().get(0);
        List<NodeModel> rootNodes = scene.getNodeModels();

        // Flatten the hierarchy to get a stable list of nodes.
        List<NodeModel> flatNodeList = VxGltfStructure.flatten(rootNodes);

        // Build the Master Skeleton from the flat list.
        VxSkeleton skeleton = VxGltfStructure.buildSkeleton(flatNodeList, boneDefinitions);

        // 4. Create the Bake Map.
        // Maps: [Skin Joint List Index] -> [Global Skeleton Array Index].
        // This map is passed to the geometry processor to rewrite vertex attributes.
        int[] jointBakeMap = null;
        if (!gltfModel.getSkinModels().isEmpty()) {
            SkinModel skin = gltfModel.getSkinModels().get(0);
            List<NodeModel> joints = skin.getJoints();
            jointBakeMap = new int[joints.size()];

            for (int i = 0; i < joints.size(); i++) {
                NodeModel jointNode = joints.get(i);

                // Find the global index of this joint using the flat node list.
                // This avoids reliance on names, supporting models with unnamed nodes.
                int skeletonIndex = flatNodeList.indexOf(jointNode);

                if (skeletonIndex == -1) {
                    VelGFX.LOGGER.warn("Skin joint '{}' defined in glTF but not found in Skeleton hierarchy.", jointNode.getName());
                    skeletonIndex = 0; // Fallback to root to prevent crash
                }
                jointBakeMap[i] = skeletonIndex;
            }
        }

        // 5. Process Geometry with Vertex Baking.
        VxGltfGeometry.GeometryResult geometry = VxGltfGeometry.processSkinnedGeometry(
                gltfModel, materials, allCommands, jointBakeMap
        );

        // 6. Load remaining components.
        List<VxMorphTarget> morphTargets = VxGltfMorphLoader.extractMorphTargets(gltfModel);
        VxMorphController morphController = null;
        if (!morphTargets.isEmpty()) {
            morphController = new VxMorphController(morphTargets);
        }

        Map<String, VxAnimation> animations = VxGltfAnimation.parseAnimations(gltfModel, skeleton);

        // 7. Upload baked geometry to the GPU Arena.
        VxArenaMesh sourceMesh = VxArenaManager.getInstance()
                .getArena(VxSkinnedVertexLayout.getInstance())
                .allocate(geometry.vertices, geometry.indices, allCommands, null);

        // 8. Create Model
        return new VxSkinnedModel(skeleton, sourceMesh, animations, morphController);
    }

    /**
     * Maps global draw commands to specific bones/nodes based on their mesh assignment.
     * <p>
     * This allows the renderer to know which parts of the mesh belong to which rigid body node.
     * Uses the flattened node list to map indices correctly.
     *
     * @param model        The glTF model.
     * @param flatNodeList The flattened list of nodes corresponding to skeleton indices.
     * @param allCommands  The list of all draw commands.
     * @param skeleton     The skeleton instance.
     * @return A map of bone index to list of draw commands.
     */
    private static Map<Integer, List<VxDrawCommand>> mapCommands(
            GltfModel model,
            List<NodeModel> flatNodeList,
            List<VxDrawCommand> allCommands,
            VxSkeleton skeleton) {

        // 1. Group Commands by MeshModel
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

        // 2. Iterate Skeleton indices (0..N)
        // Since the skeleton was built from flatNodeList, index 'i' in the skeleton corresponds
        // to index 'i' in the flat list.
        for (int i = 0; i < skeleton.boneCount; i++) {
            NodeModel node = flatNodeList.get(i);

            // If the node has meshes, assign their commands to this bone index
            for (MeshModel m : node.getMeshModels()) {
                List<VxDrawCommand> c = meshToCommands.get(m);
                if (c != null) {
                    result.computeIfAbsent(i, k -> new ArrayList<>()).addAll(c);
                }
            }
        }
        return result;
    }

    /**
     * Reads the glTF model from the Java Classpath.
     * <p>
     * Instead of reading a raw InputStream (which loses context), this method
     * converts the resource to a URI. This allows the glTF reader to resolve
     * relative references, such as external {@code .bin} files or texture images,
     * even when packed inside a JAR.
     *
     * @param location The resource location.
     * @return The parsed JglTF Model object with all buffers loaded.
     */
    private static GltfModel readModelFromClasspath(VxResourceLocation location) {
        String path = location.getPath();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        try {
            // Get the URL of the resource (handles 'jar:file:...' or 'file:...')
            URL url = VxGltfLoader.class.getResource(path);

            if (url == null) {
                throw new RuntimeException("Model file not found in classpath: " + path);
            }

            // Convert to URI to provide a base path for relative lookups
            URI uri = url.toURI();
            GltfModelReader reader = new GltfModelReader();

            // Use 'read(URI)' instead of 'readWithoutReferences(Stream)'.
            // This ensures external .bin files and textures are correctly loaded.
            return reader.read(uri);

        } catch (Exception e) {
            VelGFX.LOGGER.error("Failed to read glTF model: " + path, e);
            throw new RuntimeException("glTF Import Error", e);
        }
    }
}