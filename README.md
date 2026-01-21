# VelGFX

VelGFX is a high-performance rendering and model API designed for modern Minecraft versions.

It provides a comprehensive solution for importing and rendering complex 3D assets without the performance overhead typically associated with custom models in Minecraft. While the library includes a robust **glTF 2.0 / .glb** loader standard, the underlying rendering architecture is completely format-agnostic. The rendering logic is decoupled from the file parsing, meaning developers can easily implement parsers for other formats (such as OBJ) and feed the data directly into the VelGFX pipeline.

## Inspiration & Philosophy

This project was heavily inspired by **[MCglTF](https://github.com/ModularMods/MCglTF)**, a project that paved the way for modern 3D model rendering in Minecraft. As that project is no longer in active development, VelGFX builds upon the foundational concepts established there but adopts a different design philosophy regarding usability and integration.

Where previous solutions often required manual patching of shaderpacks or specific adjustments to model files, VelGFX focuses on an "out-of-the-box" experience. The goal is to allow assets to be dropped into the game and render correctly immediately, handling complex material conversions and pipeline states internally.

## Technical Architecture

VelGFX is not just a model loader; it is a specialized rendering engine built to bypass the limitations of the vanilla rendering pipeline.

**System Requirements:**
Due to the usage of advanced rendering techniques, VelGFX requires **OpenGL 4.0** or higher.

### Arena-Based Memory Management
To ensure maximum performance, the engine avoids frequent memory allocations. Instead, it utilizes a pre-allocated **Arena Buffer** system. Static geometry and skinned instances share massive, singular Vertex Buffer Objects (VBOs). Memory segments within these arenas are allocated and freed dynamically using a First-Fit strategy, significantly reducing draw call overhead and memory fragmentation.

### Hardware Skinning & Morph Targets
Vertex deformation is handled entirely on the GPU to keep the CPU free for game logic.
*   **Transform Feedback (TFO):** Skinning calculations are performed via a compute pass using Transform Feedback, writing the deformed vertices to memory for the main render pass.
*   **Texture Buffer Objects (TBO):** Morph target deltas are stored in massive Texture Buffers rather than uniform arrays. This allows for a significantly higher number of active morph targets without hitting shader uniform limits.

### Advanced Rendering Capabilities
The engine fully supports complex rendering states that are often difficult to manage in custom renderers:
*   **Render Layers:** Native support for **Opaque**, **Cutout** (Alpha Test), and **Translucent** layers.
*   **Sorting:** Translucent geometry is automatically sorted back-to-front for correct alpha blending.
*   **Emissive Textures:** Full support for emissive maps in all render layers.

## Automated PBR Pipeline

VelGFX bridges the gap between standard 3D workflows and Minecraft shaderpacks.

The engine features an internal PBR converter that processes materials during the loading phase. It takes standard glTF PBR attributes (Metallic, Roughness, Normal, Occlusion) and automatically "bakes" them into **LabPBR 1.3** compliant textures using a GPU pass.

This means standard glTF models will render with full PBR properties (reflections, surface detail, shadows) when used with Iris and compatible shaderpacks, without requiring the user to manually edit texture channels or patch the shaderpack.

## Developer Experience

The API is designed to abstract away the complexity of modern graphics programming. As a user of VelGFX, **you do not need knowledge of OpenGL.**

*   **Managed Resources:** You never interact with raw pointers, handles, or buffers.
*   **Garbage Collection:** The engine includes a custom Garbage Collector bridge. When a high-level model object becomes unreachable in Java, the collector tracks it and ensures the associated GPU resources (VRAM) are safely released on the render thread.
*   **Simple Integration:** Loading a model, applying animations, and rendering requires only a few lines of code.

### Usage Example

```java
// 1. Load the model
// Textures, PBR conversion, and memory allocation are handled automatically.
VxResourceLocation location = new VxResourceLocation("mymod", "models/creature.glb");
VxSkinnedModel model = VxModelManager.getSkinnedModel(location)
        .orElseThrow()
        .createInstance();

// 2. Control Animation
model.playAnimation("Walk", 0.2f); // Play with 0.2s cross-fade

// 3. Render
// The engine handles the matrix stack, lightmaps, and render types.
model.update(deltaTime);
model.render(poseStack, packedLight);
```

## Compatibility

*   **Iris Shaders:** Fully Supported.
*   **Sodium / Embeddium:** Compatible.
*   **OptiFine:** Not supported due to closed-source pipeline conflicts.