#version 150

/**
 * VelGFX - Vanilla Extended Fragment Shader (PBR)
 *
 * This shader implements a Physically Based Rendering pipeline for Minecraft.
 * It decodes LabPBR material data and calculates lighting using the Cook-Torrance model.
 *
 * Features:
 * - LabPBR Material Decoding (Roughness, Metallic, Emissive).
 * - Automatic PBR detection based on texture presence.
 * - Normal Mapping (Tangent Space).
 * - Direct Specular Highlights (Cook-Torrance).
 * - Vanilla Diffuse Lighting & Fog integration.
 *
 * @author xI-Mx-Ix
 */

// Imports
#import "velgfx:shaders/include/vx_light.glsl"
#import "velgfx:shaders/include/vx_fog.glsl"

// Samplers
uniform sampler2D Sampler0; // Albedo Map (Base Color)
uniform sampler2D Sampler1; // Overlay Texture (Damage/Tint)
uniform sampler2D Sampler2; // Lightmap (Block Light / Sky Light)
uniform sampler2D Sampler3; // LabPBR Specular (R=Smoothness, G=Metallic, B=N/A, A=Emissive)
uniform sampler2D Sampler4; // Normal Map (Tangent Space)

// Global Uniforms
uniform vec4 ColorModulator;
uniform vec3 Light0_Direction; // Light direction in View Space (Sun)
uniform vec3 Light0_Color;     // Calculated intensity and color of Sun
uniform vec3 Light1_Direction; // Light direction in View Space (Moon/Fill)
uniform vec3 Light1_Color;     // Calculated intensity and color of Moon
uniform float AlphaCutoff;     // Threshold for alpha testing

// Fragment Shader Inputs
in vec3 v_PositionView;
in vec4 v_Color;
in vec2 v_TexCoord0;
in mat3 v_TBN; // Tangent-Bitangent-Normal Matrix

flat in ivec2 v_OverlayUV; // Non-interpolated overlay indices
in vec2 v_LightMapUV; // Normalized lightmap coordinates

// Fragment Shader Outputs
out vec4 fragColor;

// Constants
#define EMISSIVE_GAIN 3.0

/**
 * Entry point.
 * Performs material decoding, lighting calculations, and final color composition.
 */
void main() {
    // 1. Base Color Processing
    // Fetch the base color from the albedo texture.
    vec4 albedo = texture(Sampler0, v_TexCoord0);

    // Alpha Test (Cutout)
    // Discards pixels below the threshold to handle transparent/cutout blocks.
    if (albedo.a < AlphaCutoff) {
        discard;
    }

    // Apply Vertex Color and Global Modulator.
    // 'color' represents the Base Color of the surface before any lighting is applied.
    vec4 color = albedo * v_Color * ColorModulator;

    // 2. PBR Material Decoding (Auto-Detect)
    // Sample LabPBR Data (Sampler3).
    // If no texture is bound (or it is black), R=0 (Smoothness 0 -> Rough 1), G=0 (Metal 0), A=0 (Emissive 0).
    // This provides a fallback to standard dull vanilla materials.
    vec4 specData = texture(Sampler3, v_TexCoord0);

    float roughness = 1.0 - specData.r; // Red channel stores Smoothness in LabPBR
    float metallic = specData.g;        // Green channel stores Metallic
    float emissiveStrength = specData.a; // Alpha channel stores Emissive/Glow

    // Clamp roughness to prevent mathematical artifacts (division by zero) in specular math.
    roughness = max(roughness, 0.04);

    // Sample Normal Map (Sampler4)
    vec3 normalSample = texture(Sampler4, v_TexCoord0).rgb;
    vec3 normal;

    // Check if a valid Normal Map is present.
    // If the sample is nearly black, we assume no normal map is bound.
    if (dot(normalSample, normalSample) > 0.01) {
        // Expand sample from range [0, 1] to [-1, 1]
        vec3 map = normalSample * 2.0 - 1.0;
        // Transform the tangent-space normal into View Space using the TBN matrix.
        normal = normalize(v_TBN * map);
    } else {
        // Fallback: Use the original geometry normal (the Z-axis of the TBN matrix).
        normal = normalize(v_TBN[2]);
    }

    // 3. Lighting Data Preparation
    // View Vector: Since the camera is at the origin in View Space, V is simply the inverse position.
    vec3 viewDir = normalize(-v_PositionView);

    // Sample the Minecraft Lightmap (Ambient/Environment Lighting)
    // We clamp the coordinates to the inner 15/16ths to prevent texture border bleeding.
    vec2 lightUV = clamp(v_LightMapUV, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
    vec3 lightMapColor = texture(Sampler2, lightUV).rgb;

    // Calculate the directional lighting factor based on vertex normals and light directions.
    // This utilizes dynamic light colors passed from the engine to determine brightness.
    float dirLightFactor = computeDirectionalLight(normal, Light0_Direction, Light0_Color, Light1_Direction, Light1_Color);

    // 4. Physical Light Component Calculation

    // A. Diffuse Term (Environment Lighting)
    // Dielectrics (non-metals) reflect the base color diffusely.
    // Metals absorb the base color (the color is shifted into the specular reflection).
    vec3 dielectricColor = color.rgb * (1.0 - metallic);
    vec3 diffuseTerm = dielectricColor * lightMapColor * dirLightFactor;

    // B. Emissive Term (Self-Illumination)
    // The object glows with its own base color, amplified by the gain constant.
    vec3 emissiveTerm = color.rgb * EMISSIVE_GAIN;

    // C. Specular Term (Direct Reflections)
    // Calculates the physical reflection of specific directional light sources.
    vec3 specularTerm = vec3(0.0);

    // Optimization: Skip expensive specular math if the surface is very matte and not metallic.
    if (roughness < 0.9 || metallic > 0.1) {
        // Calculate specular highlight for Sun (Light0)
        specularTerm += computePBRLight(normal, viewDir, Light0_Direction, color.rgb, roughness, metallic, Light0_Color);
        // Calculate specular highlight for Moon (Light1)
        specularTerm += computePBRLight(normal, viewDir, Light1_Direction, color.rgb, roughness, metallic, Light1_Color);
    }

    // 5. Final Composition
    // Mix the Diffuse Term (shadowed environment) with the Emissive Term (unshadowed glow).
    // As emissiveStrength approaches 1.0, the object stops receiving shadows and purely glows.
    vec3 baseLighting = mix(diffuseTerm, emissiveTerm, emissiveStrength);

    // Add direct specular highlights on top of the base lighting.
    vec3 finalRGB = baseLighting + specularTerm;

    // Reassign the processed lighting to the color variable.
    color.rgb = finalRGB;

    // 6. Overlay Application (Damage Tint)
    // Fetch and apply the overlay texture (e.g., the red flash when an entity takes damage).
    vec4 overlay = texelFetch(Sampler1, v_OverlayUV, 0);
    color.rgb = mix(overlay.rgb, color.rgb, overlay.a);

    // 7. Fog Application
    float viewDistance = length(v_PositionView);

    // Emissive objects visually "pierce" the fog by reducing the effective fog distance.
    float fogDistance = viewDistance * (1.0 - emissiveStrength * 0.8);

    fragColor = applyFog(color, fogDistance);
}