#version 330 core

// Input Attributes
layout(location = 0) in vec3 in_Pos;
layout(location = 1) in vec2 in_UV;
layout(location = 2) in vec3 in_Normal;
layout(location = 3) in vec4 in_Tangent;
layout(location = 4) in vec4 in_Weights;
layout(location = 5) in vec4 in_Joints;

// Bone Matrices (Max 100)
const int MAX_BONES = 100;
uniform mat4 u_BoneMatrices[MAX_BONES];

// Outputs for Transform Feedback
out vec3 out_Pos;
out vec3 out_Normal;
out vec2 out_UV;
out vec4 out_Tangent;

void main() {
    // 1. Safe Index Casting
    // Add 0.5 before casting to int to prevent 2.999 -> 2 error.
    // This effectively performs a round() operation.
    ivec4 joints = ivec4(in_Joints + vec4(0.5));

    // 2. Calculate Skinning Matrix
    mat4 skinMatrix =
    in_Weights.x * u_BoneMatrices[joints.x] +
    in_Weights.y * u_BoneMatrices[joints.y] +
    in_Weights.z * u_BoneMatrices[joints.z] +
    in_Weights.w * u_BoneMatrices[joints.w];

    // Safety: If skinMatrix is Zero (e.g. no weights/bones loaded), fallback to Identity
    // to prevent the mesh from collapsing to (0,0,0).
    // Note: A robust engine ensures weights sum to 1, but this helps debugging.
    if (length(skinMatrix[0]) < 0.001) {
        skinMatrix = mat4(1.0);
    }

    // 3. Transform Position (Bind Pose -> Animated World Space)
    vec4 pos = skinMatrix * vec4(in_Pos, 1.0);
    out_Pos = pos.xyz;

    // 4. Transform Normal & Tangent
    // Extract rotation component.
    // Note: For non-uniform scaling, use transpose(inverse(mat3(skinMatrix))),
    // but for standard skeletal animation, mat3 is sufficient and faster.
    mat3 rotMatrix = mat3(skinMatrix);

    out_Normal = normalize(rotMatrix * in_Normal);
    out_Tangent.xyz = normalize(rotMatrix * in_Tangent.xyz);
    out_Tangent.w = in_Tangent.w;

    // 5. Pass-through UV
    out_UV = in_UV;

    // 6. Write gl_Position
    // Required to prevent driver optimization stripping uniforms,
    // even though rasterization is discarded.
    gl_Position = pos;
}