#version 330 core

// Dummy Fragment Shader
// Transform Feedback usually discards rasterization, but a valid program needs a fragment shader attached.
void main() {
    // Discard everything
    discard;
}