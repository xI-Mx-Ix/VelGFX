#version 150

/**
 * Fog calculation module.
 * Manages fog uniforms and application logic.
 */

// --- Uniforms ---
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

/**
 * Applies linear distance fog to the fragment color.
 *
 * @param inColor  The original fragment color.
 * @param distance The distance from the camera to the fragment (View Space length).
 * @return The color blended with the fog color based on distance.
 */
vec4 applyFog(vec4 inColor, float distance) {
    if (distance <= FogStart) {
        return inColor;
    }

    // Calculate linear fog factor
    float fogValue = distance < FogEnd ? smoothstep(FogStart, FogEnd, distance) : 1.0;

    // Mix based on fog alpha to allow for transparent fog (e.g., in water)
    return vec4(mix(inColor.rgb, FogColor.rgb, fogValue * FogColor.a), inColor.a);
}