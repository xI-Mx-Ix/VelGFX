#version 150

/**
 * Lighting calculation module.
 * Defines constants and functions for standard Minecraft directional lighting,
 * as well as Physically Based Rendering (PBR) math for specular reflections.
 */

// --- Constants ---
#define PI 3.14159265359
#define MINECRAFT_AMBIENT_LIGHT 0.4

/**
 * Calculates the standard Minecraft directional lighting factor (Diffuse only).
 * Utilizes the calculated light colors to determine contribution.
 *
 * @param normal     The vertex normal in view space.
 * @param lightDir0  The first light direction (Sun).
 * @param lightColor0 The intensity/color of the first light.
 * @param lightDir1  The second light direction (Moon).
 * @param lightColor1 The intensity/color of the second light.
 * @return A scalar multiplier for the light intensity (0.0 to 1.0).
 */
float computeDirectionalLight(vec3 normal, vec3 lightDir0, vec3 lightColor0, vec3 lightDir1, vec3 lightColor1) {
    // Calculate N dot L for both sources
    float nDotL0 = max(0.0, dot(lightDir0, normal));
    float nDotL1 = max(0.0, dot(lightDir1, normal));

    // Weight the contribution by the light's current intensity (brightness)
    // We use the luminance or max component of the color to drive the vanilla shading factor
    float intensity0 = length(lightColor0) * nDotL0;
    float intensity1 = length(lightColor1) * nDotL1;

    // Scale down slightly to match vanilla-ish brightness curve
    float combined = (intensity0 + intensity1) * 0.6;

    return min(1.0, combined + MINECRAFT_AMBIENT_LIGHT);
}

// --- PBR Functions (Cook-Torrance BRDF) ---

/**
 * Normal Distribution Function (NDF) using Trowbridge-Reitz GGX.
 * Approximates the amount of microfacets aligned with the halfway vector.
 *
 * @param N         The surface normal vector.
 * @param H         The halfway vector between View and Light.
 * @param roughness The roughness of the surface (0.0 smooth, 1.0 rough).
 * @return The distribution factor.
 */
float distributionGGX(vec3 N, vec3 H, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float NdotH = max(dot(N, H), 0.0);
    float NdotH2 = NdotH * NdotH;

    float num = a2;
    float denom = (NdotH2 * (a2 - 1.0) + 1.0);
    denom = PI * denom * denom;

    return num / max(denom, 0.0001);
}

/**
 * Geometry Function (Schlick-GGX).
 * Approximates self-shadowing of microfacets (geometry obstruction).
 *
 * @param NdotV     Dot product of Normal and View/Light vector.
 * @param roughness The roughness of the surface.
 * @return The geometry shadowing factor.
 */
float geometrySchlickGGX(float NdotV, float roughness) {
    float r = (roughness + 1.0);
    float k = (r * r) / 8.0;

    float num = NdotV;
    float denom = NdotV * (1.0 - k) + k;

    return num / max(denom, 0.0001);
}

/**
 * Smith's method for Geometry Shadowing.
 * Combines geometry obstruction for both the View vector and the Light vector.
 */
float geometrySmith(vec3 N, vec3 V, vec3 L, float roughness) {
    float NdotV = max(dot(N, V), 0.0);
    float NdotL = max(dot(N, L), 0.0);
    float ggx1 = geometrySchlickGGX(NdotL, roughness);
    float ggx2 = geometrySchlickGGX(NdotV, roughness);
    return ggx1 * ggx2;
}

/**
 * Fresnel Equation (Schlick approximation).
 * Calculates the ratio of light reflected versus refracted based on the viewing angle.
 *
 * @param cosTheta The dot product of the Halfway vector and View vector.
 * @param F0       The surface base reflectivity (0.04 for dielectrics, Albedo for metals).
 */
vec3 fresnelSchlick(float cosTheta, vec3 F0) {
    return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

/**
 * Calculates the specular contribution of a single light source using the Cook-Torrance BRDF.
 *
 * @param N         The surface normal (View Space).
 * @param V         The view direction (View Space, towards camera).
 * @param L         The light direction (View Space).
 * @param albedo    The base color of the surface.
 * @param roughness The roughness factor (0.0 - 1.0).
 * @param metallic  The metallic factor (0.0 - 1.0).
 * @param intensity The intensity/color of the light source.
 * @return The calculated specular light component.
 */
vec3 computePBRLight(vec3 N, vec3 V, vec3 L, vec3 albedo, float roughness, float metallic, vec3 intensity) {
    vec3 H = normalize(V + L);

    // Base reflectivity (F0)
    // Non-metals use 0.04, Metals use the albedo color itself.
    vec3 F0 = vec3(0.04);
    F0 = mix(F0, albedo, metallic);

    // Cook-Torrance BRDF terms
    float NDF = distributionGGX(N, H, roughness);
    float G = geometrySmith(N, V, L, roughness);
    vec3 F = fresnelSchlick(max(dot(H, V), 0.0), F0);

    vec3 numerator = NDF * G * F;
    float denominator = 4.0 * max(dot(N, V), 0.0) * max(dot(N, L), 0.0) + 0.0001;
    vec3 specular = numerator / denominator;

    // Apply Light Intensity and Lambert's Cosine Law (NdotL)
    float NdotL = max(dot(N, L), 0.0);
    return specular * intensity * NdotL;
}