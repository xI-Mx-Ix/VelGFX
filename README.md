# VelGFX

VelGFX is a high-performance rendering engine and model API designed for modern Minecraft versions.

It provides a comprehensive solution for importing and rendering complex 3D assets without the performance overhead typically associated with custom models in Minecraft. While the library includes a robust **glTF 2.0 / .glb** loader as the standard, the underlying rendering architecture is format-agnostic. The rendering logic is decoupled from file parsing, allowing developers to implement parsers for other formats and feed the data directly into the VelGFX pipeline.

## Inspiration & Philosophy

This project draws inspiration from **[MCglTF](https://github.com/ModularMods/MCglTF)**, the project that pioneered modern 3D model rendering in Minecraft. However, VelGFX adopts a distinct philosophy focused on "out-of-the-box" usability.

Where previous solutions often required manual shaderpack patching or specific asset preparation, VelGFX is designed to "just work." You drop a standard glTF asset into the game, and the engine handles the complex material conversion, pipeline injection, and rendering state management automatically.

## Unified PBR Pipeline (Vanilla & Shaders)

VelGFX bridges the gap between high-fidelity 3D workflows and Minecraft’s blocky rendering engine.

### Native Vanilla PBR
The engine features a **custom rendering pipeline** that injects itself into the Vanilla render loop. This means VelGFX models support **Normal Mapping**, **Specular Highlights**, and **Emissive Textures** natively, even without a shaderpack installed.

*   **Dynamic Lighting:** Models react to the sun and moon position with proper specular reflections.
*   **Emissivity:** Full support for glowing textures (emissive maps) that work in the dark.
*   **LabPBR Baking:** During loading, the engine automatically "bakes" standard glTF attributes (Metallic, Roughness, Occlusion) into optimized **LabPBR 1.3** compliant textures using a GPU compute pass.

### Shaderpack Integration
If **Iris** is detected, VelGFX automatically adapts. It performs runtime shader analysis to inject the baked PBR textures into the shaderpack's expected inputs. This ensures your models look identical to the rest of the shader-enhanced world without any manual configuration.

## Technical Architecture

VelGFX is not just a model loader; it is a specialized rendering engine built to bypass the limitations of the standard Minecraft render system.

**System Requirements:**
VelGFX requires **OpenGL 4.0** or higher.

### Structure of Arrays (SoA) Animation
Unlike traditional object-oriented animation systems that suffer from pointer chasing, VelGFX utilizes a **Structure of Arrays (SoA)** architecture for skeletons and animations. Matrix calculations are performed on contiguous flat arrays, maximizing CPU cache locality and significantly improving performance when animating hundreds of entities.

### Arena-Based Memory Management
To minimize Java Garbage Collection pressure, the engine uses a pre-allocated **Arena Buffer** system. Static geometry and skinned instances share massive, singular Vertex Buffer Objects (VBOs). Memory segments are allocated and freed dynamically using a First-Fit strategy, drastically reducing draw call overhead.

### Hardware Instanced Rendering
For the Vanilla rendering backend, VelGFX implements a fully automatic **Hardware Instancing** pipeline. Instead of issuing individual draw calls for every entity, the engine aggregates compatible meshes (sharing geometry and materials) into batches. It then submits them via a single `glDrawElementsInstanced` command. This architecture allows for the rendering of thousands of identical entities with negligible CPU cost.

### Hardware Skinning & Morph Targets
Vertex deformation is handled entirely on the GPU to keep the CPU free for game logic.
*   **Transform Feedback:** Skinning calculations are performed via a vertex shader compute pass, writing deformed vertices to memory for the main render pass.
*   **Texture Buffer Objects (TBO):** Morph target deltas are stored in high-capacity Texture Buffers. This allows for complex facial animations and deformations that exceed standard uniform limits.

## Developer Experience

The API is designed to abstract away the complexity of modern graphics programming. As a user of VelGFX, **you do not need knowledge of OpenGL.**

*   **Managed Resources:** You never interact with raw pointers, handles, or buffers.
*   **Garbage Collection Bridge:** The engine includes a custom Garbage Collector. When a high-level model object becomes unreachable in Java, the collector tracks it and ensures the associated GPU resources (VRAM) are safely released on the render thread.
*   **Simple Integration:** Loading a model, applying animations, and rendering requires only a few lines of code.

### Usage Example

```java
// 1. Load the model
// PBR conversion, memory allocation, and texture baking are automatic.
VxResourceLocation location = new VxResourceLocation("mymod", "models/creature.glb");
VxSkinnedModel model = VxModelManager.getSkinnedModel(location)
        .orElseThrow()
        .createInstance();

// 2. Control Animation
model.playAnimation("Walk", 0.2f); // Play with 0.2s cross-fade blending

// 3. Render
// The engine handles the matrix stack, lightmaps, PBR state, and render types.
model.update(deltaTime);
model.render(poseStack, packedLight);
```

## Compatibility

*   **Iris Shaders:** Fully Supported.
*   **Sodium / Embeddium:** Fully Compatible.
*   **OptiFine:** **Not Supported.** OptiFine is not supported primarily because it is not available for modern Fabric versions, in addition to its closed-source nature making pipeline integration impossible.