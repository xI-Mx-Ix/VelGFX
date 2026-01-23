#version 150

/**
 * Lighting calculation module.
 * Defines constants and functions for standard Minecraft directional lighting.
 */

// --- Constants ---
#define MINECRAFT_LIGHT_POWER   0.6
#define MINECRAFT_AMBIENT_LIGHT 0.4

/**
 * Calculates the standard Minecraft directional lighting factor.
 * Both light directions must be in View Space to match the normal.
 *
 * @param normal    The vertex normal in view space.
 * @param lightDir0 The first light direction (e.g., Sun) in view space.
 * @param lightDir1 The second light direction (e.g., Moon/Fill) in view space.
 * @return A scalar multiplier for the light intensity (0.0 to 1.0).
 */
float computeDirectionalLight(vec3 normal, vec3 lightDir0, vec3 lightDir1) {
    float light0 = max(0.0, dot(lightDir0, normal));
    float light1 = max(0.0, dot(lightDir1, normal));
    return min(1.0, (light0 + light1) * MINECRAFT_LIGHT_POWER + MINECRAFT_AMBIENT_LIGHT);
}