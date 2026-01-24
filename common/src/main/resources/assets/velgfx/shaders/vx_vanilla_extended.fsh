#version 150

/**
 * PBR Fragment Shader.
 * Imports lighting and fog modules to assemble the final pixel color.
 *
 * Features:
 * - LabPBR Material Decoding (Roughness, Metallic, Emissive).
 * - Automatic PBR detection based on texture presence.
 * - Normal Mapping (Tangent Space).
 * - Direct Specular Highlights (Cook-Torrance).
 * - Vanilla Diffuse Lighting & Fog.
 */

// --- Imports ---
#import "velgfx:shaders/include/vx_light.glsl"
#import "velgfx:shaders/include/vx_fog.glsl"

// --- Samplers ---
uniform sampler2D Sampler0; // Albedo Map (Base Color)
uniform sampler2D Sampler1; // Overlay Texture (Damage/Tint)
uniform sampler2D Sampler2; // Lightmap (Block Light / Sky Light)
uniform sampler2D Sampler3; // LabPBR Specular (R=Smoothness, G=Metallic, B=N/A, A=Emissive)
uniform sampler2D Sampler4; // Normal Map (Tangent Space)

// --- Uniforms ---
uniform vec4 ColorModulator;
uniform vec3 Light0_Direction; // Light direction in View Space (Sun)
uniform vec3 Light1_Direction; // Light direction in View Space (Moon/Fill)
uniform float AlphaCutoff;     // Threshold for alpha testing

// --- Inputs ---
in vec3 v_PositionView;
in vec4 v_Color;
in vec2 v_TexCoord0;
in mat3 v_TBN; // Tangent-Bitangent-Normal Matrix

flat in ivec2 v_OverlayUV; // Non-interpolated overlay indices
in vec2 v_LightMapUV; // Normalized lightmap coordinates

out vec4 fragColor;

// --- Constants ---
#define EMISSIVE_GAIN 3.0

// Light intensities for specular calculation
#define LIGHT0_INTENSITY vec3(2.5)
#define LIGHT1_INTENSITY vec3(0.8)

/**
 * Main entry point.
 */
void main() {
// 1. Base Color Processing
vec4 albedo = texture(Sampler0, v_TexCoord0);

// Alpha Test (Cutout)
if (albedo.a < AlphaCutoff) {
discard;
}

// Apply Vertex Color and Global Modulator
// 'color' represents the Base Color of the surface before lighting.
vec4 color = albedo * v_Color * ColorModulator;

// 2. PBR Material Decoding (Auto-Detect)

// Sample LabPBR Data (Sampler3)
// If bound 0 (black): R=0 (Smoothness 0 -> Rough 1), G=0 (Metal 0), A=0 (Emissive 0).
// This perfectly defaults to a standard dull vanilla block.
vec4 specData = texture(Sampler3, v_TexCoord0);

float roughness = 1.0 - specData.r; // Red is Smoothness
float metallic = specData.g; // Green is Metallic
float emissiveStrength = specData.a; // Alpha is Emissive

// Clamp roughness to prevent mathematical artifacts at 0.0
roughness = max(roughness, 0.04);

// Sample Normal Map (Sampler4)
vec3 normalSample = texture(Sampler4, v_TexCoord0).rgb;
vec3 normal;

// Check if Normal Map is present.
// If we bound texture 0, the sample is black (0,0,0).
// Length squared of (0,0,0) is 0. If > 0.0, we have data.
if (dot(normalSample, normalSample) > 0.01) {
// Expand from [0, 1] to [-1, 1]
vec3 map = normalSample * 2.0 - 1.0;
// Transform from Tangent Space to View Space
normal = normalize(v_TBN * map);
} else {
// Fallback: Use geometry normal (Z-axis of TBN is the Vertex Normal)
normal = normalize(v_TBN[2]);
}

// 3. Lighting Data Preparation

// View Vector (Camera is at origin in View Space, so V = -Position)
vec3 viewDir = normalize(- v_PositionView);

// Sample the Minecraft Lightmap (Environment Lighting)
// Clamp to inner 15/16ths to prevent border bleeding.
vec2 lightUV = clamp(v_LightMapUV, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
vec3 lightMapColor = texture(Sampler2, lightUV).rgb;

// Calculate simple directional factor for the environment light
float dirLightFactor = computeDirectionalLight(normal, Light0_Direction, Light1_Direction);

// 4. Physical Light Component Calculation

// A. Diffuse Term (Environment Lighting)
// Non-metals (Dielectrics) reflect the base color diffusely.
// Metals absorb the base color (it goes into the specular reflection).
// Formula: Diffuse = BaseColor * (1 - Metallic) * Irradiance
vec3 dielectricColor = color.rgb * (1.0 - metallic);
vec3 diffuseTerm = dielectricColor * lightMapColor * dirLightFactor;

// B. Emissive Term (Self-Illumination)
// The object glows with its own base color, amplified by gain.
vec3 emissiveTerm = color.rgb * EMISSIVE_GAIN;

// C. Specular Term (Direct Reflections)
// Calculates the physical reflection of the specific light sources.
vec3 specularTerm = vec3(0.0);

// Optimization: Skip specular math if roughness is very high (matte) and not metallic.
if (roughness < 0.9 || metallic > 0.1) {
specularTerm += computePBRLight(normal, viewDir, Light0_Direction, color.rgb, roughness, metallic, LIGHT0_INTENSITY);
specularTerm += computePBRLight(normal, viewDir, Light1_Direction, color.rgb, roughness, metallic, LIGHT1_INTENSITY);
}

// 5. Final Composition

// Mix the Diffuse Term (shadowed environment) with the Emissive Term (unshadowed glow).
// As emissiveStrength approaches 1.0, the object stops receiving shadows and purely glows.
vec3 baseLighting = mix(diffuseTerm, emissiveTerm, emissiveStrength);

// Add Specular Highlights on top.
vec3 finalRGB = baseLighting + specularTerm;

// Assign back to color variable
color.rgb = finalRGB;

// 6. Overlay Application (Damage Tint)
vec4 overlay = texelFetch(Sampler1, v_OverlayUV, 0);
color.rgb = mix(overlay.rgb, color.rgb, overlay.a);

// 7. Fog Application
float viewDistance = length(v_PositionView);

// Emissive objects visually penetrate fog.
float fogDistance = viewDistance * (1.0 - emissiveStrength * 0.8);

fragColor = applyFog(color, fogDistance);
}