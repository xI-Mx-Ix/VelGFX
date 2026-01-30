#version 330 core

in vec2 v_UV;

// Multiple Render Targets (MRT) Output
// Target 0: The combined Albedo map (Base Color + baked AO + baked Emissive)
layout (location = 0) out vec4 out_Albedo;
// Target 1: The packed Specular map (LabPBR 1.3 Standard)
layout (location = 1) out vec4 out_Specular;

// Source Textures
uniform sampler2D u_TexAlbedo;
uniform sampler2D u_TexMR;        // glTF: Roughness (G), Metallic (B)
uniform sampler2D u_TexOcclusion; // Occlusion (R)
uniform sampler2D u_TexEmissive;

// Texture Presence Flags
uniform bool u_HasAlbedo;
uniform bool u_HasMR;
uniform bool u_HasOcclusion;
uniform bool u_HasEmissive;

// PBR Factors
uniform vec4 u_BaseColorFactor;
uniform vec3 u_EmissiveFactor;
uniform float u_RoughnessFactor;
uniform float u_MetallicFactor;
uniform float u_OcclusionStrength;

/**
 * Fragment shader for baking PBR textures into LabPBR 1.3 format.
 *
 * This shader converts glTF PBR textures into the LabPBR 1.3 standard:
 *
 * Specular Map (_s):
 *   Red   = Perceptual Smoothness (convert from linear roughness)
 *   Green = F0 / Reflectance (for dielectrics) OR Metal flag (230-255)
 *   Blue  = Porosity/SSS (0 for standard materials)
 *   Alpha = Emissive Strength (0-254, linear, NOT 255)
 *
 * LabPBR 1.3 changes from 1.2:
 *   - F0 is now stored linearly (not sqrt-encoded)
 */
void main() {
    // Fetch source values

    // Albedo: Factor * Texture (if present)
    vec4 baseColor = u_BaseColorFactor;
    if (u_HasAlbedo) {
        baseColor *= texture(u_TexAlbedo, v_UV);
    }

    // Metallic-Roughness (glTF: Green=Roughness, Blue=Metallic)
    vec2 mrSample = vec2(1.0, 0.0); // Default: Roughness=1.0, Metallic=0.0
    if (u_HasMR) {
        vec3 mrTexture = texture(u_TexMR, v_UV).rgb;
        mrSample = vec2(mrTexture.g, mrTexture.b); // glTF standard channels
    }

    // Occlusion (glTF: Red=Occlusion)
    float occlusionVal = 1.0;
    if (u_HasOcclusion) {
        occlusionVal = texture(u_TexOcclusion, v_UV).r;
    }

    // Emissive: Texture (if present)
    vec3 emissiveColor = vec3(0.0);
    if (u_HasEmissive) {
        emissiveColor = texture(u_TexEmissive, v_UV).rgb;
    }

    // Calculate final Albedo (MRT 0)

    // Apply Ambient Occlusion using glTF formula
    // Formula: Color * mix(1.0, AO, occlusionStrength)
    float aoFactor = mix(1.0, occlusionVal, u_OcclusionStrength);
    vec3 albedoRgb = baseColor.rgb * aoFactor;

    // Bake Emissive Color (Additive)
    // Formula: Color + (EmissiveTexture * EmissiveFactor)
    albedoRgb += emissiveColor * u_EmissiveFactor;

    out_Albedo = vec4(albedoRgb, baseColor.a);

    // Calculate LabPBR 1.3 Specular Map (MRT 1)

    // Apply glTF factors
    float linearRoughness = clamp(mrSample.x * u_RoughnessFactor, 0.0, 1.0);
    float metallic = clamp(mrSample.y * u_MetallicFactor, 0.0, 1.0);

    // Red channel: Perceptual Smoothness
    // LabPBR 1.3: perceptualSmoothness = 1.0 - sqrt(linearRoughness)
    // This is the inverse of: linearRoughness = pow(1.0 - perceptualSmoothness, 2.0)
    float perceptualSmoothness = 1.0 - sqrt(linearRoughness);

    // Green channel: F0 / Reflectance (or Metal ID)
    // glTF uses metallic workflow: metallic=0 → dielectric, metallic=1 → conductor
    // For LabPBR we need to encode F0 or use hardcoded metal values (230-255)

    float f0_channel;

    if (metallic > 0.5) {
        // Material is metallic
        // Use hardcoded metal value 255 (albedo-based F0)
        // This tells LabPBR shaders to use albedo as F0
        // Note: Values 230-254 are reserved for specific metals (Iron, Gold, etc.)
        // but glTF doesn't provide this information, so we default to 255
        f0_channel = 255.0;
    } else {
        // Material is dielectric
        // Calculate F0 for dielectrics (typically ~0.04 for most materials)
        // glTF spec: F0 = 0.04 for dielectrics
        // LabPBR stores F0 linearly in range [0, 229] → value/255
        // F0 = 0.04 → store as: 0.04 * 229 ≈ 9.16 → ~9
        float dielectricF0 = 0.04; // Standard dielectric F0

        // Map to LabPBR range [0, 229]
        // Linear storage in v1.3: value = F0 * 229
        f0_channel = dielectricF0 * 229.0;
    }

    // Blue channel: Porosity/SSS
    // Set to 0 for standard materials (no porosity, no subsurface scattering)
    float porosity_sss = 0.0;

    // Alpha channel: Emissive Strength
    // LabPBR 1.3: Linear storage, range [0, 254]
    // Important: Value 255 is reserved and will be ignored by shaders

    vec3 finalEmissive = emissiveColor * u_EmissiveFactor;

    // Calculate emissive strength as luminance
    float emissiveLuma = dot(finalEmissive, vec3(0.2126, 0.7152, 0.0722));

    // Map to LabPBR range [0, 254] and store linearly
    float emissiveStrength = clamp(emissiveLuma * 254.0, 0.0, 254.0);

    // Output LabPBR 1.3 Specular Map
    out_Specular = vec4(
    perceptualSmoothness,       // Red: Perceptual smoothness
    f0_channel / 255.0,         // Green: F0 (normalized to [0,1] for texture storage)
    porosity_sss / 255.0,       // Blue: Porosity/SSS (normalized)
    emissiveStrength / 255.0    // Alpha: Emissive strength (normalized, max 254)
    );
}