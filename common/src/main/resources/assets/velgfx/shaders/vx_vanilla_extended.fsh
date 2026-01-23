#version 150

// --- Constants ---
#define MINECRAFT_LIGHT_POWER   0.6
#define MINECRAFT_AMBIENT_LIGHT 0.4

// --- Samplers ---
uniform sampler2D Sampler0; // Albedo Map (Base Color)
uniform sampler2D Sampler1; // Overlay Texture (Integer Lookup for Damage/Tint)
uniform sampler2D Sampler2; // Lightmap (Block Light / Sky Light)
uniform sampler2D Sampler3; // Specular Map (Alpha Channel = Emissive Strength)

// --- Uniforms ---
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

uniform vec3 Light0_Direction; // Light direction in View Space
uniform vec3 Light1_Direction; // Light direction in View Space

uniform float AlphaCutoff; // Threshold for alpha testing (0.0 for opaque, >0.0 for cutout)
uniform float UseEmissive; // Toggle: 1.0 to enable emissive map, 0.0 to disable

// --- Inputs ---
in vec3 v_PositionView;
in vec3 v_NormalView;
in vec4 v_Color;
in vec2 v_TexCoord0;

flat in ivec2 v_OverlayUV; // Non-interpolated overlay indices
in vec2 v_LightMapUV;      // Normalized lightmap coordinates

out vec4 fragColor;

/**
 * Calculates the standard Minecraft directional lighting factor.
 * Both light directions must be in View Space to match the normal.
 */
float computeDirectionalLight(vec3 normal, vec3 lightDir0, vec3 lightDir1) {
    float light0 = max(0.0, dot(lightDir0, normal));
    float light1 = max(0.0, dot(lightDir1, normal));
    return min(1.0, (light0 + light1) * MINECRAFT_LIGHT_POWER + MINECRAFT_AMBIENT_LIGHT);
}

/**
 * Applies linear distance fog to the fragment color.
 */
vec4 applyFog(vec4 inColor, float distance) {
    if (distance <= FogStart) {
        return inColor;
    }
    float fogValue = distance < FogEnd ? smoothstep(FogStart, FogEnd, distance) : 1.0;
    return vec4(mix(inColor.rgb, FogColor.rgb, fogValue * FogColor.a), inColor.a);
}

/**
 * Custom Vanilla Extended Fragment Shader.
 * <p>
 * Performs:
 * 1. Base Albedo sampling with Alpha Testing.
 * 2. Vanilla Overlay application (Damage Flash).
 * 3. Directional Lighting (Sun/Moon).
 * 4. Lightmap Sampling (Block/Sky Light).
 * 5. Emissive Masking (High emissive strength overrides shadows).
 * 6. Fog Application.
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
    // The overlay texture is a lookup table (not a normal texture).
    // We use texelFetch to get the exact pixel based on the integer UVs.
    // Logic: mix(OverlayRGB, BaseRGB, OverlayAlpha)
    // If OverlayAlpha is 1.0 (Normal state), the base color is preserved.
    // If OverlayAlpha is 0.0 (Hurt state), the overlay color overrides the base.
    vec4 overlay = texelFetch(Sampler1, v_OverlayUV, 0);
    color.rgb = mix(overlay.rgb, color.rgb, overlay.a);

    // 3. Lighting Calculation
    vec3 normal = normalize(v_NormalView);
    float dirLightFactor = computeDirectionalLight(normal, Light0_Direction, Light1_Direction);

    // Lightmap Sampling
    // We clamp the UVs to the inner 15/16ths of the texture to prevent
    // bleeding artifacts from the texture border, matching vanilla behavior.
    vec2 lightUV = clamp(v_LightMapUV, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
    vec4 lightMapColor = texture(Sampler2, lightUV);

    // Combine Lightmap with Directional Light
    vec3 environmentalLight = lightMapColor.rgb * dirLightFactor;

    // 4. Emissive / Specular Logic
    // We sample the Emissive Strength from the Alpha channel of Sampler3.
    // UseEmissive uniform safeguards against using a missing texture (which defaults to opaque white).
    float emissiveStrength = 0.0;
    if (UseEmissive > 0.5) {
        emissiveStrength = texture(Sampler3, v_TexCoord0).a;
    }

    // Masking Logic:
    // Pixels with 0.0 emissive strength are fully affected by environmental light (shadows).
    // Pixels with 1.0 emissive strength are fully bright (ignore shadows).
    vec3 finalLight = mix(environmentalLight, vec3(1.0), emissiveStrength);

    // Apply final lighting to the color
    color.rgb *= finalLight;

    // 5. Final Fog Application
    fragColor = applyFog(color, length(v_PositionView));
}