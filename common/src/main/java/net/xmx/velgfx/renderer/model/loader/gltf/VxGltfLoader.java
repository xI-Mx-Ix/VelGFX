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
import net.xmx.velgfx.renderer.model.morph.VxMorphController;
import net.xmx.velgfx.renderer.model.morph.VxMorphTarget;
import net.xmx.velgfx.renderer.model.skeleton.VxBone;
import net.xmx.velgfx.renderer.model.skeleton.VxNode;
import net.xmx.velgfx.renderer.model.skeleton.VxSkeleton;
import net.xmx.velgfx.resources.VxResourceLocation;
import org.joml.Matrix4f;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
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

        // 4. Build Node Hierarchy
        // glTF files can contain multiple scenes, but we typically use the default one (index 0).
        SceneModel scene = gltfModel.getSceneModels().get(0);
        VxNode rootNode = new VxNode("GLTF_ROOT", null, new Matrix4f());

        for (NodeModel node : scene.getNodeModels()) {
            rootNode.addChild(VxGltfStructure.processNodeHierarchy(node, rootNode));
        }

        // 5. Extract Animations and Mappings
        // Map Nodes to specific Draw Commands to support Rigid Body Animation.
        Map<String, List<VxDrawCommand>> nodeCommands = VxGltfStructure.mapNodesToCommands(gltfModel, scene, allCommands);
        Map<String, VxAnimation> animations = VxGltfAnimation.parseAnimations(gltfModel);

        // Static skeleton wrapper (no bones, just hierarchy)
        VxSkeleton skeleton = new VxSkeleton(rootNode, Collections.emptyList(), new Matrix4f());

        return new VxStaticModel(skeleton, arenaMesh, animations, nodeCommands);
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

        // 2. Process Geometry & Extract Bone Definitions
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
        List<VxBone> finalBones = VxGltfStructure.buildSkeletonBones(boneDefinitions, rootNode);
        Matrix4f globalInverse = new Matrix4f(); // Identity for glTF 2.0
        VxSkeleton skeleton = new VxSkeleton(rootNode, finalBones, globalInverse);

        // 5. Extract Morph Targets
        // This invokes the Morph Loader to process Sparse Accessors and upload to TBO.
        List<VxMorphTarget> morphTargets = VxGltfMorphLoader.extractMorphTargets(gltfModel);
        VxMorphController morphController = null;
        if (!morphTargets.isEmpty()) {
            morphController = new VxMorphController(morphTargets);
        }

        // 6. Extract Animations
        Map<String, VxAnimation> animations = VxGltfAnimation.parseAnimations(gltfModel);

        // 7. Upload Geometry to GPU Arena
        // Skinned models use the Skinned Vertex Layout (80 bytes, high precision floats).
        VxArenaMesh sourceMesh = VxArenaManager.getInstance()
                .getArena(VxSkinnedVertexLayout.getInstance())
                .allocate(geometry.vertices, geometry.indices, allCommands, null);

        // 8. Create Model
        // Pass the morphController to the constructor
        return new VxSkinnedModel(skeleton, sourceMesh, animations, morphController);
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