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
in vec4 Tangent;   // Tangent vector (xyz) + handedness (w)

// --- Uniforms ---
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 NormalMat;

// --- Outputs ---
out vec3 v_PositionView; // Vertex position in View Space (for fog)
out vec4 v_Color;        // Per-vertex color (includes tint)
out vec2 v_TexCoord0;    // Main texture coordinates

// The TBN matrix transforms vectors from Tangent Space to View Space.
out mat3 v_TBN;

// passed as 'flat' to prevent interpolation, as these are integer array indices
flat out ivec2 v_OverlayUV;

// normalized lightmap coordinates (0.0 - 1.0)
out vec2 v_LightMapUV;

void main() {
    // 1. Transform Position to View Space
    // This represents the position relative to the camera.
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    v_PositionView = viewPos.xyz;

    // 2. Calculate TBN Matrix (Robust)
    // We transform Normal and Tangent to View Space.
    vec3 n = normalize(NormalMat * Normal);
    vec3 t = normalize(NormalMat * Tangent.xyz);

    // Gram-Schmidt process: Re-orthogonalize T with respect to N.
    // During morph animations, T and N can become parallel (degenerate state).
    // If dot(t, n) is ~1.0, the result of (t - dot * n) is a zero vector.
    // Normalize(0) produces NaNs, causing black/white flickering.
    float dotProduct = dot(t, n);

    // Only re-orthogonalize if vectors are not parallel.
    if (abs(dotProduct) < 0.99) {
        t = normalize(t - dotProduct * n);
    }
    // If they are parallel, we keep 't' as is (best effort) to avoid NaN.

    // Calculate Bitangent
    // The 'w' component of the tangent stores the handedness of the coordinate system.
    vec3 b = cross(n, t) * Tangent.w;

    // Construct the matrix
    v_TBN = mat3(t, b, n);

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