#version 150

/**
 * VelGFX - Vanilla Extended Vertex Shader (Instanced)
 *
 * This shader implements the hardware-instanced rendering pipeline.
 * It replaces standard uniform-based rendering with attribute-based batching,
 * reading model matrices and auxiliary data directly from the instance buffer.
 *
 * @author xI-Mx-Ix
 */

// Standard Mesh Attributes (Per-Vertex)
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec3 Normal;
in vec4 Tangent;

// Instanced Attributes (Per-Instance)
// These attributes advance once per instance (Divisor 1).
// The Model Matrix is split into 4 column vectors (Attribute locations 10-13).
in mat4 i_ModelMat;

// Packed auxiliary data (Attribute 14).
// x = Packed Lightmap Coordinates (BlockLight | SkyLight)
// y = Packed Overlay Coordinates (U | V)
in ivec2 i_AuxData;

// Global Uniforms
uniform mat4 ViewMat;
uniform mat4 ProjMat;

// Vertex Shader Outputs
out vec3 v_PositionView;
out vec4 v_Color;
out vec2 v_TexCoord0;
out mat3 v_TBN;

// Flat qualifier prevents interpolation for integer indices
flat out ivec2 v_OverlayUV;
out vec2 v_LightMapUV;

/**
 * Entry point.
 * Transforms vertices to clip space and prepares TBN and texture data for the fragment stage.
 */
void main() {
    // Calculate the ModelView Matrix by combining the global View Matrix
    // with the per-instance Model Matrix from the buffer.
    mat4 ModelViewMat = ViewMat * i_ModelMat;

    // Calculate the Normal Matrix to transform normals into View Space.
    // The inverse-transpose is required to handle non-uniform scaling correctly.
    // We cast to mat3 to discard translation components before inversion.
    mat3 NormalMat = transpose(inverse(mat3(ModelViewMat)));

    // Transform local position to View Space (relative to camera)
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    v_PositionView = viewPos.xyz;

    // Transform to Clip Space for the rasterizer
    gl_Position = ProjMat * viewPos;

    // TBN Matrix Construction
    // Transform Normal and Tangent to View Space
    vec3 n = normalize(NormalMat * Normal);
    vec3 t = normalize(NormalMat * Tangent.xyz);

    // Gram-Schmidt Orthogonalization
    // Re-aligns the tangent vector to be perfectly perpendicular to the normal,
    // correcting interpolation errors or mesh artifacts.
    float dotProduct = dot(t, n);
    if (abs(dotProduct) < 0.99) {
        t = normalize(t - dotProduct * n);
    }

    // Calculate Bitangent using the cross product and handedness (w component)
    vec3 b = cross(n, t) * Tangent.w;

    // Construct the final TBN matrix
    v_TBN = mat3(t, b, n);

    // Pass standard color and texture coordinates
    v_Color = Color;
    v_TexCoord0 = UV0;

    // Unpack Overlay UVs from the high/low bits of the integer (i_AuxData.y)
    int packedOverlay = i_AuxData.y;
    v_OverlayUV = ivec2(packedOverlay & 0xFFFF, packedOverlay >> 16);

    // Unpack Lightmap UVs from the integer (i_AuxData.x)
    // Convert from Minecraft's 0-240 integer range to normalized 0.0-1.0 floats
    int packedLight = i_AuxData.x;
    vec2 lightCoords = vec2(packedLight & 0xFFFF, packedLight >> 16);
    v_LightMapUV = lightCoords / 256.0;
}