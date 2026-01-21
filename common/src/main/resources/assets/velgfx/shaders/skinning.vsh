#version 150

// --- Attributes (From VxSkinnedVertexLayout) ---
in vec3 in_Pos;
in vec2 in_UV;
in vec3 in_Normal;
in vec4 in_Tangent;
in vec4 in_Weights;
in vec4 in_Joints;

// --- Uniforms ---
// Standard Skinning
uniform mat4 u_BoneMatrices[100];

// Morph Targets (Texture Buffer Object)
uniform samplerBuffer u_MorphDeltas;       // The big buffer containing all deltas (RGB32F)
uniform int   u_ActiveMorphIndices[8];     // Offsets in the TBO for the top 8 active targets
uniform float u_ActiveMorphWeights[8];     // Normalized weights (0.0 - 1.0)
uniform int   u_ActiveMorphCount;          // Actual number of targets to process (0 to 8)
uniform int   u_MeshBaseVertex;            // The starting index of this mesh in the Arena (to calculate local ID)

// --- Outputs (Transform Feedback) ---
out vec3 out_Pos;
out vec3 out_Normal;
out vec2 out_UV;
out vec4 out_Tangent;

// --- Constants ---
// Stride in "Texels" (1 Texel = 1 vec3 = 3 floats).
// Order in TBO: [PosDelta, NormalDelta, TangentDelta]
const int MORPH_STRIDE_TEXELS = 3;

void main() {
    // 1. Calculate Local Vertex Index relative to the mesh start
    // gl_VertexID corresponds to the index in the global Arena Buffer.
    int localVertexId = gl_VertexID - u_MeshBaseVertex;

    // 2. Accumulate Morph Deltas
    // Start with the base static attributes
    vec3 morphedPos = in_Pos;
    vec3 morphedNormal = in_Normal;
    vec3 morphedTangent = in_Tangent.xyz;

    for (int i = 0; i < u_ActiveMorphCount; i++) {
        float weight = u_ActiveMorphWeights[i];

        // Skip insignificant weights to save texture fetches
        if (weight < 0.001) continue;

        // Calculate start texel index for this specific target and vertex
        // TBO Index = (TargetOffsetTexels) + (LocalVertexID * Stride)
        int tboOffset = u_ActiveMorphIndices[i] + (localVertexId * MORPH_STRIDE_TEXELS);

        // Fetch Deltas (RGB32F texture gives us vec3 directly)
        vec3 deltaPos = texelFetch(u_MorphDeltas, tboOffset).rgb;
        vec3 deltaNor = texelFetch(u_MorphDeltas, tboOffset + 1).rgb;
        vec3 deltaTan = texelFetch(u_MorphDeltas, tboOffset + 2).rgb;

        morphedPos     += deltaPos * weight;
        morphedNormal  += deltaNor * weight;
        morphedTangent += deltaTan * weight;
    }

    // 3. Hardware Skinning (Linear Blend Skinning)
    // We apply the bone transformations to the *morphed* geometry.
    vec4 totalLocalPos = vec4(0.0);
    vec4 totalNormal   = vec4(0.0);
    vec4 totalTangent  = vec4(0.0);

    // Track total weight to detect if this is a skinned mesh or a rigid morph mesh
    float totalWeight = 0.0;

    for(int i = 0; i < 4; i++) {
        int   jointIndex = int(in_Joints[i]);
        float jointWeight = in_Weights[i];

        if (jointWeight > 0.0) {
            totalWeight += jointWeight;
            mat4 boneMatrix = u_BoneMatrices[jointIndex];

            totalLocalPos += (boneMatrix * vec4(morphedPos, 1.0)) * jointWeight;

            // Normal matrix is technically transpose(inverse(mat3(boneMatrix))),
            // but for rigid bones with uniform scale, mat3(boneMatrix) suffices.
            mat3 rotMatrix = mat3(boneMatrix);
            totalNormal.xyz += (rotMatrix * morphedNormal) * jointWeight;
            totalTangent.xyz += (rotMatrix * morphedTangent) * jointWeight;
        }
    }

    // Fallback: If the mesh has no bone weights (e.g. rigid morph target mesh like the Cube),
    // do not apply skinning matrices, otherwise the position collapses to zero.
    if (totalWeight <= 0.001) {
        totalLocalPos = vec4(morphedPos, 1.0);
        totalNormal   = vec4(morphedNormal, 0.0);
        totalTangent  = vec4(morphedTangent, 0.0);
    }

    // 4. Output to Transform Feedback Buffer
    out_Pos     = totalLocalPos.xyz;
    out_Normal  = normalize(totalNormal.xyz);
    out_UV      = in_UV;

    // Reconstruct tangent with handedness (W component preserved from input)
    out_Tangent = vec4(normalize(totalTangent.xyz), in_Tangent.w);
}