#version 150

// --- Attributes ---
in vec3 in_Pos;
in vec2 in_UV;
in vec3 in_Normal;
in vec4 in_Tangent;
in vec4 in_Weights;
in vec4 in_Joints;

// --- Uniforms ---
uniform mat4 u_BoneMatrices[100];
uniform samplerBuffer u_MorphDeltas;
uniform int   u_ActiveMorphIndices[8];
uniform float u_ActiveMorphWeights[8];
uniform int   u_ActiveMorphCount;
uniform int   u_MeshBaseVertex;

// The global transformation matrix of the root node.
// Used for geometry that contains no bone weights (rigid morph meshes).
uniform mat4  u_BaseTransform;

// --- Outputs ---
out vec3 out_Pos;
out vec3 out_Normal;
out vec2 out_UV;
out vec4 out_Tangent;

// --- Constants ---
// Stride in "Texels" (1 Texel = 1 vec3 = 3 floats).
// Order in TBO: [PosDelta, NormalDelta, TangentDelta]
const int MORPH_STRIDE_TEXELS = 3;

void main() {
    int localVertexId = gl_VertexID - u_MeshBaseVertex;

    // --- Morph Target Accumulation ---
    vec3 morphedPos = in_Pos;
    vec3 morphedNormal = in_Normal;
    vec3 morphedTangent = in_Tangent.xyz;

    for (int i = 0; i < u_ActiveMorphCount; i++) {
        float weight = u_ActiveMorphWeights[i];

        if (weight < 0.001) continue;

        int tboOffset = u_ActiveMorphIndices[i] + (localVertexId * MORPH_STRIDE_TEXELS);

        vec3 deltaPos = texelFetch(u_MorphDeltas, tboOffset).rgb;
        vec3 deltaNor = texelFetch(u_MorphDeltas, tboOffset + 1).rgb;
        vec3 deltaTan = texelFetch(u_MorphDeltas, tboOffset + 2).rgb;

        morphedPos     += deltaPos * weight;
        morphedNormal  += deltaNor * weight;
        morphedTangent += deltaTan * weight;
    }

    // --- Skeletal Skinning ---
    vec4 totalLocalPos = vec4(0.0);
    vec4 totalNormal   = vec4(0.0);
    vec4 totalTangent  = vec4(0.0);

    float totalWeight = 0.0;

    for(int i = 0; i < 4; i++) {
        float jointWeight = in_Weights[i];
        if (jointWeight > 0.0) {
            totalWeight += jointWeight;
            int jointIndex = int(in_Joints[i]);
            mat4 boneMatrix = u_BoneMatrices[jointIndex];

            totalLocalPos += (boneMatrix * vec4(morphedPos, 1.0)) * jointWeight;

            mat3 rotMatrix = mat3(boneMatrix);
            totalNormal.xyz += (rotMatrix * morphedNormal) * jointWeight;
            totalTangent.xyz += (rotMatrix * morphedTangent) * jointWeight;
        }
    }

    // Fallback for unskinned geometry (e.g., Morph-only meshes).
    // Applies the model's base transformation to ensure correct scaling and rotation.
    if (totalWeight <= 0.001) {
        totalLocalPos = u_BaseTransform * vec4(morphedPos, 1.0);

        mat3 normalMatrix = mat3(u_BaseTransform);
        totalNormal   = vec4(normalMatrix * morphedNormal, 0.0);
        totalTangent  = vec4(normalMatrix * morphedTangent, 0.0);
    }

    // --- Output ---
    out_Pos     = totalLocalPos.xyz;
    out_Normal  = normalize(totalNormal.xyz);
    out_UV      = in_UV;
    out_Tangent = vec4(normalize(totalTangent.xyz), in_Tangent.w);
}