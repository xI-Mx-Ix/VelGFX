#version 150

/**
 * Standard Fragment Shader.
 * Imports lighting and fog modules to assemble the final pixel color.
 *
 * This shader includes logic for "Over-Brightening" emissive textures
 * to create a glowing/blinding effect.
 */

// --- Imports ---
// The preprocessor will replace these lines with the contents of the files.
#import "velgfx:shaders/include/vx_light.glsl"
#import "velgfx:shaders/include/vx_fog.glsl"

// --- Samplers ---
uniform sampler2D Sampler0; // Albedo Map (Base Color)
uniform sampler2D Sampler1; // Overlay Texture (Integer Lookup for Damage/Tint)
uniform sampler2D Sampler2; // Lightmap (Block Light / Sky Light)
uniform sampler2D Sampler3; // Specular Map (Alpha Channel = Emissive Strength)

// --- Uniforms ---
uniform vec4 ColorModulator;
uniform vec3 Light0_Direction; // Light direction in View Space
uniform vec3 Light1_Direction; // Light direction in View Space
uniform float AlphaCutoff;     // Threshold for alpha testing
uniform float UseEmissive;     // Toggle: 1.0 to enable emissive map

// --- Inputs ---
in vec3 v_PositionView;
in vec3 v_NormalView;
in vec4 v_Color;
in vec2 v_TexCoord0;

flat in ivec2 v_OverlayUV; // Non-interpolated overlay indices
in vec2 v_LightMapUV;      // Normalized lightmap coordinates

out vec4 fragColor;

// --- Constants ---
// Controls how much brighter than 1.0 the emissive parts should be.
// Values > 1.0 create a "glare" or "blinding" effect by saturating the color channels.
#define EMISSIVE_GAIN 3.0

/**
 * Main entry point.
 * Combines Albedo, Overlay, Lighting, Emissive Maps, and Fog.
 */
void main() {
// 1. Base Color Processing
vec4 albedo = texture(Sampler0, v_TexCoord0);

// Alpha Test (Cutout)
if (albedo.a < AlphaCutoff) {
discard;
}

// Apply Vertex Color and Global Modulator
vec4 color = albedo * v_Color * ColorModulator;

// 2. Overlay Application (Vanilla Logic)
// The overlay texture is a lookup table.
// If OverlayAlpha is 0.0 (Hurt state), the overlay color overrides the base.
vec4 overlay = texelFetch(Sampler1, v_OverlayUV, 0);
color.rgb = mix(overlay.rgb, color.rgb, overlay.a);

// 3. Lighting Calculation
// Uses logic imported from vx_light.glsl
vec3 normal = normalize(v_NormalView);
float dirLightFactor = computeDirectionalLight(normal, Light0_Direction, Light1_Direction);

// Lightmap Sampling
// Clamp to inner 15/16ths to prevent border bleeding.
vec2 lightUV = clamp(v_LightMapUV, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
vec4 lightMapColor = texture(Sampler2, lightUV);

// Combine Lightmap with Directional Light
vec3 environmentalLight = lightMapColor.rgb * dirLightFactor;

// 4. Emissive / Specular Logic
float emissiveStrength = 0.0;
if (UseEmissive > 0.5) {
// Alpha channel of specular map controls emissiveness.
emissiveStrength = texture(Sampler3, v_TexCoord0).a;
}

// Calculate Emissive Light
// We multiply by EMISSIVE_GAIN to allow the color to exceed 1.0 (over-brightening).
// This ensures glowing parts look "hot" and blinding, even in bright environments.
vec3 emissiveLight = vec3(EMISSIVE_GAIN);

// Mix Logic:
// Interpolate between the environmental light (shadows/sky) and the pure emissive light.
// As emissiveStrength approaches 1.0, shadows are completely ignored and the color boosts.
vec3 finalLight = mix(environmentalLight, emissiveLight, emissiveStrength);

// Apply final lighting to the color
color.rgb *= finalLight;

// 5. Final Fog Application
// Calculate the distance from the camera to the fragment.
float viewDistance = length(v_PositionView);

// Fog Penetration Logic:
// If a pixel is emissive, we artificially reduce its distance calculation for the fog.
// This allows the glowing object to "cut through" the fog, remaining visible even far away.
// Factor 0.8 means fully emissive pixels act as if they are 20% of their real distance.
float fogDistance = viewDistance * (1.0 - emissiveStrength * 0.8);

fragColor = applyFog(color, fogDistance);
}