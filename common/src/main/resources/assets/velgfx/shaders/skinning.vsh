#version 330 core

// Input: VxSkinnedVertexLayout
layout(location = 0) in vec3 in_Pos;
layout(location = 1) in vec2 in_UV;
layout(location = 2) in vec3 in_Normal;
layout(location = 3) in vec4 in_Tangent;
layout(location = 4) in vec4 in_Weights;
layout(location = 5) in vec4 in_Joints;

const int MAX_BONES = 100;
uniform mat4 u_BoneMatrices[MAX_BONES];

// Output: Transform Feedback
out vec3 out_Pos;
out vec3 out_Normal;
out vec2 out_UV;
out vec4 out_Tangent;

void main() {
    vec4 totalPos = vec4(0.0);
    vec3 totalNormal = vec3(0.0);
    vec3 totalTangent = vec3(0.0);

    // Sum of weights check to prevent uninitialized behavior
    float weightSum = dot(in_Weights, vec4(1.0));

    if (weightSum < 0.001) {
        // Fallback for unskinned vertices: Pass through
        totalPos = vec4(in_Pos, 1.0);
        totalNormal = in_Normal;
        totalTangent = in_Tangent.xyz;
    } else {
        // Apply skinning matrix
        for(int i = 0; i < 4; i++) {
            int joint = int(in_Joints[i]);
            float weight = in_Weights[i];

            if(joint >= 0 && weight > 0.0) {
                mat4 boneMat = u_BoneMatrices[joint];
                // Assume uniform scaling, extract rotation matrix
                mat3 rotMat = mat3(boneMat);

                totalPos += (boneMat * vec4(in_Pos, 1.0)) * weight;
                totalNormal += (rotMat * in_Normal) * weight;
                totalTangent += (rotMat * in_Tangent.xyz) * weight;
            }
        }
    }

    out_Pos = totalPos.xyz;
    out_Normal = normalize(totalNormal);
    out_UV = in_UV;
    out_Tangent = vec4(normalize(totalTangent), in_Tangent.w);
}