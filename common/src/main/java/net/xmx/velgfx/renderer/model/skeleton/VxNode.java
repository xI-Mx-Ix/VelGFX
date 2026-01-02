/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.skeleton;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a node in the model's scene graph hierarchy.
 * <p>
 * Nodes form a tree structure. Each node contains a local transformation relative to its parent.
 * The class provides functionality to traverse the tree and compute global world-space transformations
 * based on the parent's state.
 *
 * @author xI-Mx-Ix
 */
public class VxNode {
    private final String name;
    private final VxNode parent;
    private final List<VxNode> children = new ArrayList<>();

    /**
     * The local transformation relative to the parent.
     * Can be the Bind Pose or an Animated Pose set by the Animator.
     */
    private final Matrix4f localTransform;

    /**
     * The calculated global transformation (Model Space).
     */
    private final Matrix4f globalTransform = new Matrix4f();

    /**
     * Constructs a new scene node.
     *
     * @param name           The name of the node.
     * @param parent         The parent node (null if root).
     * @param localTransform The initial local transformation matrix.
     */
    public VxNode(String name, VxNode parent, Matrix4f localTransform) {
        this.name = name;
        this.parent = parent;
        this.localTransform = localTransform;
    }

    /**
     * Adds a child node to this node's hierarchy.
     *
     * @param child The child node.
     */
    public void addChild(VxNode child) {
        this.children.add(child);
    }

    /**
     * Recursively updates the global transform of this node and all its children.
     * <p>
     * {@code Global = ParentGlobal * Local}
     *
     * @param parentTransform The global transform of the parent node (or Identity for root).
     */
    public void updateHierarchy(Matrix4f parentTransform) {
        if (parentTransform != null) {
            parentTransform.mul(localTransform, globalTransform);
        } else {
            globalTransform.set(localTransform);
        }

        for (VxNode child : children) {
            child.updateHierarchy(globalTransform);
        }
    }

    /**
     * Recursively searches the subtree for a node with the specified name.
     *
     * @param targetName The name to search for.
     * @return The node instance if found, otherwise null.
     */
    public VxNode findByName(String targetName) {
        if (this.name.equals(targetName)) return this;
        for (VxNode child : children) {
            VxNode found = child.findByName(targetName);
            if (found != null) return found;
        }
        return null;
    }

    public Matrix4f getLocalTransform() {
        return localTransform;
    }

    public Matrix4f getGlobalTransform() {
        return globalTransform;
    }

    public String getName() {
        return name;
    }

    public List<VxNode> getChildren() {
        return children;
    }
}