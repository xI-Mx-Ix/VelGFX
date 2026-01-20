/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.loader.gltf;

import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.NodeModel;
import de.javagl.jgltf.model.SceneModel;
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
import net.xmx.velgfx.renderer.model.skeleton.VxBone;
import net.xmx.velgfx.renderer.model.skeleton.VxNode;
import net.xmx.velgfx.renderer.model.skeleton.VxSkeleton;
import net.xmx.velgfx.resources.VxResourceLocation;
import org.joml.Matrix4f;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A comprehensive loader for glTF 2.0 and GLB assets.
 * <p>
 * This class serves as the entry point for the glTF loading pipeline. It orchestrates the
 * reading of the file stream and delegates the processing of materials, geometry,
 * scene structure, and animations to specialized helper classes.
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

        // 4. Build Node Hierarchy
        // glTF files can contain multiple scenes, but we typically use the default one (index 0).
        // We create a synthetic root node to hold the glTF scene.
        SceneModel scene = gltfModel.getSceneModels().get(0);
        VxNode rootNode = new VxNode("GLTF_ROOT", null, new Matrix4f());

        for (NodeModel node : scene.getNodeModels()) {
            rootNode.addChild(VxGltfStructure.processNodeHierarchy(node, rootNode));
        }

        // 5. Extract Animations and Mappings
        // Map Nodes to specific Draw Commands to support Rigid Body Animation.
        // Requires passing the full glTF Model to resolve mesh references.
        Map<String, List<VxDrawCommand>> nodeCommands = VxGltfStructure.mapNodesToCommands(gltfModel, scene, allCommands);
        Map<String, VxAnimation> animations = VxGltfAnimation.parseAnimations(gltfModel);

        // Static skeleton wrapper (no bones, just hierarchy)
        VxSkeleton skeleton = new VxSkeleton(rootNode, Collections.emptyList(), new Matrix4f());

        return new VxStaticModel(skeleton, arenaMesh, animations, nodeCommands);
    }

    /**
     * Imports a file as a {@link VxSkinnedModel} suitable for skeletal animation.
     * <p>
     * A skinned model includes additional vertex attributes (Weights and Joints) and
     * constructs a full skeleton with Inverse Bind Matrices for GPU skinning.
     * <p>
     * Unlike Static Models, Skinned Models do not require a Node-to-Command mapping
     * because the mesh is rendered as a whole and deformed by the shader using bone uniforms.
     *
     * @param location The resource location of the model file.
     * @return A constructed skinned model.
     * @throws RuntimeException If the model cannot be loaded or parsed.
     */
    public static VxSkinnedModel loadSkinned(VxResourceLocation location) {
        GltfModel gltfModel = readModelFromClasspath(location);

        // 1. Extract Materials
        List<VxMaterial> materials = VxGltfMaterial.parseMaterials(gltfModel);

        // 2. Process Geometry & Extract Bone Definitions
        // This extracts vertices with Weights/Joints and reads the Skin's Inverse Bind Matrices.
        List<VxDrawCommand> allCommands = new ArrayList<>();
        List<VxGltfStructure.BoneDefinition> boneDefinitions = new ArrayList<>();

        VxGltfGeometry.GeometryResult geometry = VxGltfGeometry.processSkinnedGeometry(gltfModel, materials, allCommands, boneDefinitions);

        // 3. Build Node Hierarchy
        SceneModel scene = gltfModel.getSceneModels().get(0);
        VxNode rootNode = new VxNode("GLTF_ROOT", null, new Matrix4f());

        for (NodeModel node : scene.getNodeModels()) {
            rootNode.addChild(VxGltfStructure.processNodeHierarchy(node, rootNode));
        }

        // 4. Construct Final Skeleton
        // Links the flat list of Bone Definitions (from Skin) to the actual Scene Graph Nodes.
        List<VxBone> finalBones = VxGltfStructure.buildSkeletonBones(boneDefinitions, rootNode);

        // In glTF 2.0, Bind Matrices are usually absolute inverse world transforms.
        // Therefore, the global inverse transform of the model root is typically Identity.
        Matrix4f globalInverse = new Matrix4f();

        VxSkeleton skeleton = new VxSkeleton(rootNode, finalBones, globalInverse);

        // 5. Extract Animations
        Map<String, VxAnimation> animations = VxGltfAnimation.parseAnimations(gltfModel);

        // 6. Upload Geometry to GPU Arena
        // Skinned models use the Skinned Vertex Layout (80 bytes, high precision floats)
        // for higher accuracy during vertex deformation in the Compute Shader / Transform Feedback.
        VxArenaMesh sourceMesh = VxArenaManager.getInstance()
                .getArena(VxSkinnedVertexLayout.getInstance())
                .allocate(geometry.vertices, geometry.indices, allCommands, null);

        return new VxSkinnedModel(skeleton, sourceMesh, animations);
    }

    /**
     * Reads the glTF model from the Java Classpath.
     *
     * @param location The resource location.
     * @return The parsed JglTF Model object.
     */
    private static GltfModel readModelFromClasspath(VxResourceLocation location) {
        // Ensure the path starts with a slash to indicate absolute classpath resolution.
        // Class.getResourceAsStream("assets/...") looks relative to the class package.
        // Class.getResourceAsStream("/assets/...") looks at the root of the JAR.
        String path = location.getPath();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        try (InputStream stream = VxGltfLoader.class.getResourceAsStream(path)) {
            if (stream == null) {
                // If stream is still null, the file truly doesn't exist at that path
                throw new RuntimeException("Model file not found in classpath: " + path);
            }

            GltfModelReader reader = new GltfModelReader();
            // "readWithoutReferences" loads the main file.
            // GLB files have all data embedded, so this works perfectly.
            return reader.readWithoutReferences(new BufferedInputStream(stream));
        } catch (Exception e) {
            VelGFX.LOGGER.error("Failed to read glTF model: " + path, e);
            throw new RuntimeException("glTF Import Error", e);
        }
    }
}