/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.gl.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import net.xmx.velgfx.renderer.VelGFX;
import net.xmx.velgfx.resources.VxResourceLocation;
import net.xmx.velgfx.resources.VxShaderResourceLoader;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Abstract base class for OpenGL Shader Programs.
 * <p>
 * This class encapsulates the lifecycle of a shader program, including compilation of
 * Vertex and Fragment shaders, linking, validation, and uniform management.
 * It uses a caching mechanism for uniform locations to minimize GL calls during rendering.
 *
 * @author xI-Mx-Ix
 */
public abstract class VxShaderProgram implements AutoCloseable {

    private final int programId;
    private final int vertexShaderId;
    private final int fragmentShaderId;

    /**
     * Cache for uniform locations to avoid repeated JNI calls to glGetUniformLocation.
     * Key: Uniform Name, Value: GL Location ID.
     */
    private final Map<String, Integer> uniformLocationCache = new HashMap<>();

    /**
     * Constructs and links a new shader program.
     *
     * @param vertexLoc   The location of the vertex shader source.
     * @param fragmentLoc The location of the fragment shader source.
     * @throws RuntimeException If compilation or linking fails.
     */
    public VxShaderProgram(VxResourceLocation vertexLoc, VxResourceLocation fragmentLoc) {
        RenderSystem.assertOnRenderThread();

        try {
            this.vertexShaderId = loadAndCompileShader(vertexLoc, GL20.GL_VERTEX_SHADER);
            this.fragmentShaderId = loadAndCompileShader(fragmentLoc, GL20.GL_FRAGMENT_SHADER);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader source files", e);
        }

        this.programId = GL20.glCreateProgram();
        GL20.glAttachShader(programId, vertexShaderId);
        GL20.glAttachShader(programId, fragmentShaderId);

        // Hook for subclasses to bind vertex attribute indices before linking
        bindAttributes();

        preLink(programId);

        GL20.glLinkProgram(programId);
        if (GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == GL20.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(programId);
            cleanup();
            throw new IllegalStateException("Shader Linking Error: " + log);
        }

        GL20.glValidateProgram(programId);
        if (GL20.glGetProgrami(programId, GL20.GL_VALIDATE_STATUS) == GL20.GL_FALSE) {
            VelGFX.LOGGER.warn("Shader Validation Warning: {}", GL20.glGetProgramInfoLog(programId));
        }

        // Hook for subclasses to register their specific uniforms
        registerUniforms();
    }

    /**
     * Loads source code from the resource loader and compiles the shader object.
     *
     * @param location The resource location.
     * @param type     The GL shader type (e.g., GL_VERTEX_SHADER).
     * @return The OpenGL shader ID.
     * @throws IOException If the file cannot be read.
     */
    private int loadAndCompileShader(VxResourceLocation location, int type) throws IOException {
        String source = VxShaderResourceLoader.loadShaderSource(location);
        int shaderId = GL20.glCreateShader(type);

        GL20.glShaderSource(shaderId, source);
        GL20.glCompileShader(shaderId);

        if (GL20.glGetShaderi(shaderId, GL20.GL_COMPILE_STATUS) == GL20.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shaderId);
            String typeStr = (type == GL20.GL_VERTEX_SHADER) ? "Vertex" : "Fragment";
            throw new IllegalStateException("Shader Compilation Error (" + typeStr + "): " + log);
        }

        return shaderId;
    }

    /**
     * Abstract method for subclasses to bind vertex attributes to specific indices.
     * Example: bindAttribute(0, "a_Position");
     */
    protected abstract void bindAttributes();

    /**
     * Abstract method for subclasses to create/cache their uniforms after linking.
     */
    protected abstract void registerUniforms();

    /**
     * A hook called immediately before {@code glLinkProgram}.
     * <p>
     * Subclasses can override this to configure Transform Feedback varyings
     * using {@code glTransformFeedbackVaryings}.
     *
     * @param programId The OpenGL program ID.
     */
    protected void preLink(int programId) {
        // Default implementation does nothing
    }

    /**
     * Binds a vertex attribute variable to a specific index.
     * Must be called inside {@link #bindAttributes()}.
     *
     * @param attribute The index (e.g., 0 for position).
     * @param variableName The name of the attribute in the shader source.
     */
    protected void bindAttribute(int attribute, String variableName) {
        GL20.glBindAttribLocation(programId, attribute, variableName);
    }

    /**
     * Creates a uniform mapping in the cache.
     *
     * @param uniformName The name of the uniform in the shader.
     */
    protected void createUniform(String uniformName) {
        int location = GL20.glGetUniformLocation(programId, uniformName);
        if (location < 0) {
            VelGFX.LOGGER.warn("Uniform '{}' not found in shader or optimized out.", uniformName);
        }
        uniformLocationCache.put(uniformName, location);
    }

    /**
     * Retrieves the cached location of a uniform.
     *
     * @param uniformName The name of the uniform.
     * @return The GL location ID.
     */
    public int getUniformLocation(String uniformName) {
        return uniformLocationCache.getOrDefault(uniformName, -1);
    }

    public void setUniform(String name, int value) {
        GL20.glUniform1i(getUniformLocation(name), value);
    }

    public void setUniform(String name, float value) {
        GL20.glUniform1f(getUniformLocation(name), value);
    }

    public void setUniform(String name, boolean value) {
        GL20.glUniform1f(getUniformLocation(name), value ? 1.0f : 0.0f);
    }

    public void setUniform(String name, Vector3f vector) {
        GL20.glUniform3f(getUniformLocation(name), vector.x, vector.y, vector.z);
    }

    /**
     * Uploads a 4x4 matrix using off-heap memory stack for performance.
     */
    public void setUniform(String name, Matrix4f matrix) {
        int location = getUniformLocation(name);
        if (location == -1) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(16);
            matrix.get(buffer);
            GL20.glUniformMatrix4fv(location, false, buffer);
        }
    }

    /**
     * Activates this shader program for rendering.
     */
    public void bind() {
        GL20.glUseProgram(programId);
    }

    /**
     * Unbinds the current shader program.
     */
    public void unbind() {
        GL20.glUseProgram(0);
    }

    /**
     * Releases all OpenGL resources associated with this program.
     */
    private void cleanup() {
        unbind();
        if (programId != 0) {
            GL20.glDetachShader(programId, vertexShaderId);
            GL20.glDetachShader(programId, fragmentShaderId);
            GL20.glDeleteShader(vertexShaderId);
            GL20.glDeleteShader(fragmentShaderId);
            GL20.glDeleteProgram(programId);
        }
    }

    @Override
    public void close() {
        cleanup();
    }
}