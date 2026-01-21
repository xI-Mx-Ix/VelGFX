#version 330 core

// Output UV coordinates to the fragment shader
out vec2 v_UV;

/**
 * Generates a full-screen triangle using the Vertex ID.
 * This covers the screen range [-1, 1] without needing a VBO from Java.
 *
 * ID 0 -> (-1, -1)
 * ID 1 -> ( 3, -1)
 * ID 2 -> (-1,  3)
 * Result: A large triangle covering the entire NDC viewport.
 */
void main() {
    float x = float((gl_VertexID & 1) << 2);
    float y = float((gl_VertexID & 2) << 1);

    v_UV = vec2(x * 0.5, y * 0.5);
    gl_Position = vec4(x - 1.0, y - 1.0, 0.0, 1.0);
}