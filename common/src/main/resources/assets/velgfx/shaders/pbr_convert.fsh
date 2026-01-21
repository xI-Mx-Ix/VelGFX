#version 330 core

in vec2 v_UV;

// Multiple Render Targets (MRT) Output
// Target 0: The combined Albedo map (Base Color + baked AO + baked Emissive)
layout (location = 0) out vec4 out_Albedo;
// Target 1: The packed Specular map (LabPBR Standard)
layout (location = 1) out vec4 out_Specular;

// --- Source Textures ---
uniform sampler2D u_TexAlbedo;
uniform sampler2D u_TexMR;        // Roughness (G), Metallic (B)
uniform sampler2D u_TexOcclusion; // Occlusion (R)
uniform sampler2D u_TexEmissive;

// --- Texture Presence Flags ---
uniform bool u_HasAlbedo;
uniform bool u_HasMR;
uniform bool u_HasOcclusion;
uniform bool u_HasEmissive;

// --- PBR Factors ---
uniform vec4 u_BaseColorFactor;
uniform vec3 u_EmissiveFactor;
uniform float u_RoughnessFactor;
uniform float u_MetallicFactor;
uniform float u_OcclusionStrength;

/**
 * Fragment shader for baking PBR textures.
 *
 * This shader consolidates separate glTF textures and factors into optimized
 * maps required by the rendering pipeline (specifically LabPBR format).
 */
void main() {
    // --- 1. Fetch Source Values ---

    // Albedo: Factor * Texture (if present)
    vec4 baseColor = u_BaseColorFactor;
    if (u_HasAlbedo) {
        baseColor *= texture(u_TexAlbedo, v_UV);
    }

    // Metallic-Roughness (glTF: Green=Roughness, Blue=Metallic)
    vec3 mrSample = vec3(1.0); // Default to white to preserve scalar factors
    if (u_HasMR) {
        mrSample = texture(u_TexMR, v_UV).rgb;
    }

    // Occlusion (glTF: Red=Occlusion)
    // If explicit occlusion map exists, use it.
    // Otherwise, check if MR map is treated as ORM (common optimization),
    // but strictly speaking, we rely on the u_TexOcclusion slot here.
    float occlusionVal = 1.0;
    if (u_HasOcclusion) {
        occlusionVal = texture(u_TexOcclusion, v_UV).r;
    }

    // Emissive: Texture (if present)
    vec3 emissiveColor = vec3(0.0);
    if (u_HasEmissive) {
        emissiveColor = texture(u_TexEmissive, v_UV).rgb;
    }

    // --- 2. Calculate Final Albedo (MRT 0) ---

    // Bake Ambient Occlusion into the color map
    // Formula: Color * (1.0 + Strength * (AO - 1.0))
    float finalAo = 1.0 + u_OcclusionStrength * (occlusionVal - 1.0);

    vec3 albedoRgb = baseColor.rgb * finalAo;

    // Bake Emissive Color (Additive)
    // Formula: Color + (EmissiveTexture * EmissiveFactor)
    albedoRgb += emissiveColor * u_EmissiveFactor;

    out_Albedo = vec4(albedoRgb, baseColor.a);

    // --- 3. Calculate Specular / LabPBR (MRT 1) ---

    // Combine texture channels with scalar factors
    float roughness = mrSample.g * u_RoughnessFactor;
    float metallic = mrSample.b * u_MetallicFactor;

    // LabPBR Encoding:
    // Red   = Smoothness (1.0 - Roughness)
    // Green = Metallic
    // Blue  = Reserved (0.0)
    // Alpha = Emissive Strength (Luminance based on factors)

    float smoothness = 1.0 - roughness;

    // Calculate Perceptual Luminance for Emissive Strength
    vec3 finalEmissive = emissiveColor * u_EmissiveFactor;
    float emissiveLuma = dot(finalEmissive, vec3(0.2126, 0.7152, 0.0722));

    out_Specular = vec4(smoothness, metallic, 0.0, emissiveLuma);
}