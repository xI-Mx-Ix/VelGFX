#version 150

/**
 * Standard Vertex Shader.
 * Transforms vertices to View/Clip space and prepares data for the fragment shader.
 */

// --- Attributes ---
in vec3 Position;
in vec4 Color;
in vec2 UV0;       // Texture Coordinates
in ivec2 UV1;      // Overlay UV (Integer indices, e.g., for damage flash)
in ivec2 UV2;      // Lightmap UV (Packed integer 0..240)
in vec3 Normal;
in vec4 Tangent;   // Tangent vector (required by layout, currently unused)

// --- Uniforms ---
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 NormalMat;

// --- Outputs ---
out vec3 v_PositionView; // Vertex position in View Space (for fog)
out vec3 v_NormalView;   // Vertex normal in View Space (for lighting)
out vec4 v_Color;        // Per-vertex color (includes tint)
out vec2 v_TexCoord0;    // Main texture coordinates

// passed as 'flat' to prevent interpolation, as these are integer array indices
flat out ivec2 v_OverlayUV;

// normalized lightmap coordinates (0.0 - 1.0)
out vec2 v_LightMapUV;

void main() {
    // 1. Transform Position to View Space
    // This represents the position relative to the camera.
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    v_PositionView = viewPos.xyz;

    // 2. Transform Normal to View Space
    // The Normal Matrix handles non-uniform scaling correctly.
    v_NormalView = normalize(NormalMat * Normal);

    // 3. Pass basic attributes
    v_Color = Color;
    v_TexCoord0 = UV0;

    // 4. Pass Overlay Coordinates
    // These are integer indices pointing to pixels in the overlay texture (Sampler1).
    // We pass them without interpolation (flat) to ensure we hit the exact pixel center.
    v_OverlayUV = UV1;

    // 5. Calculate Lightmap Coordinates
    // Minecraft passes light values as shorts (0 to 240).
    // We normalize this to the 0.0 - 1.0 range expected by texture().
    v_LightMapUV = vec2(UV2) / 256.0;

    // 6. Final Clip Space Position
    gl_Position = ProjMat * viewPos;
}