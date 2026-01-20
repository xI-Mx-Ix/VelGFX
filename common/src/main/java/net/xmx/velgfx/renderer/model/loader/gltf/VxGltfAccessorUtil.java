/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.loader.gltf;

import de.javagl.jgltf.model.AccessorData;
import de.javagl.jgltf.model.AccessorModel;
import de.javagl.jgltf.model.GltfConstants;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A utility class responsible for extracting and normalizing data from glTF Accessors.
 * <p>
 * glTF data is stored in raw binary buffers with varying component types (e.g., unsigned bytes,
 * shorts, floats) and strides. This class abstracts the complexity of reading these buffers
 * and provides unified methods to retrieve data as normalized floating-point arrays or
 * raw integer arrays.
 *
 * @author xI-Mx-Ix
 */
public class VxGltfAccessorUtil {

    /**
     * Reads the data of a specific accessor and converts it into a flat array of floats.
     * <p>
     * This method handles automatic normalization of integer types. For example, if the
     * accessor contains {@code UNSIGNED_BYTE} colors (0-255), they will be converted
     * to the 0.0 to 1.0 floating-point range required by OpenGL.
     *
     * @param accessor The glTF accessor model to read from.
     * @return A flat array containing all components of the accessor data.
     */
    public static float[] readAccessorAsFloats(AccessorModel accessor) {
        if (accessor == null) {
            return new float[0];
        }

        int elementCount = accessor.getCount();
        int numComponents = accessor.getElementType().getNumComponents();
        float[] result = new float[elementCount * numComponents];

        AccessorData data = accessor.getAccessorData();
        ByteBuffer buffer = data.createByteBuffer();
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        int componentType = accessor.getComponentType();
        int byteStride = accessor.getByteStride();
        int elementByteSize = numComponents * getComponentByteSize(componentType);

        // If the stride is defined as 0 in the file, it means the data is tightly packed.
        if (byteStride == 0) {
            byteStride = elementByteSize;
        }

        for (int i = 0; i < elementCount; i++) {
            int elementOffset = i * byteStride;

            for (int c = 0; c < numComponents; c++) {
                int readPosition = elementOffset + (c * getComponentByteSize(componentType));
                float value = 0.0f;

                switch (componentType) {
                    case GltfConstants.GL_FLOAT:
                        value = buffer.getFloat(readPosition);
                        break;
                    case GltfConstants.GL_BYTE:
                        // Normalize signed byte (-128 to 127) to -1.0 to 1.0
                        value = Math.max(buffer.get(readPosition) / 127.0f, -1.0f);
                        break;
                    case GltfConstants.GL_UNSIGNED_BYTE:
                        // Normalize unsigned byte (0 to 255) to 0.0 to 1.0
                        value = (buffer.get(readPosition) & 0xFF) / 255.0f;
                        break;
                    case GltfConstants.GL_SHORT:
                        // Normalize signed short to -1.0 to 1.0
                        value = Math.max(buffer.getShort(readPosition) / 32767.0f, -1.0f);
                        break;
                    case GltfConstants.GL_UNSIGNED_SHORT:
                        // Normalize unsigned short to 0.0 to 1.0
                        value = (buffer.getShort(readPosition) & 0xFFFF) / 65535.0f;
                        break;
                    case GltfConstants.GL_UNSIGNED_INT:
                    case GltfConstants.GL_INT:
                        // Direct cast for integers (rarely used for float attributes)
                        value = (float) buffer.getInt(readPosition);
                        break;
                    default:
                        throw new IllegalArgumentException("Unsupported component type: " + componentType);
                }
                result[i * numComponents + c] = value;
            }
        }
        return result;
    }

    /**
     * Reads the data of a specific accessor and converts it into a flat array of integers.
     * <p>
     * This is primarily used for index buffers (indices) and joint indices (skinning).
     *
     * @param accessor The glTF accessor model to read from.
     * @return A flat array containing all components as integers.
     */
    public static int[] readAccessorAsInts(AccessorModel accessor) {
        if (accessor == null) {
            return new int[0];
        }

        int elementCount = accessor.getCount();
        int numComponents = accessor.getElementType().getNumComponents();
        int[] result = new int[elementCount * numComponents];

        AccessorData data = accessor.getAccessorData();
        ByteBuffer buffer = data.createByteBuffer();
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        int componentType = accessor.getComponentType();
        int byteStride = accessor.getByteStride();
        int elementByteSize = numComponents * getComponentByteSize(componentType);

        if (byteStride == 0) {
            byteStride = elementByteSize;
        }

        for (int i = 0; i < elementCount; i++) {
            int elementOffset = i * byteStride;

            for (int c = 0; c < numComponents; c++) {
                int readPosition = elementOffset + (c * getComponentByteSize(componentType));
                int value = 0;

                switch (componentType) {
                    case GltfConstants.GL_UNSIGNED_BYTE:
                        value = buffer.get(readPosition) & 0xFF;
                        break;
                    case GltfConstants.GL_UNSIGNED_SHORT:
                        value = buffer.getShort(readPosition) & 0xFFFF;
                        break;
                    case GltfConstants.GL_UNSIGNED_INT:
                        value = buffer.getInt(readPosition);
                        break;
                    case GltfConstants.GL_BYTE:
                        value = buffer.get(readPosition);
                        break;
                    case GltfConstants.GL_SHORT:
                        value = buffer.getShort(readPosition);
                        break;
                    case GltfConstants.GL_INT:
                        value = buffer.getInt(readPosition);
                        break;
                    default:
                        throw new IllegalArgumentException("Unsupported component type for integer access: " + componentType);
                }
                result[i * numComponents + c] = value;
            }
        }
        return result;
    }

    /**
     * Determines the size in bytes of a specific glTF component type.
     *
     * @param componentType The OpenGL constant representing the type.
     * @return The size in bytes (1, 2, or 4).
     */
    private static int getComponentByteSize(int componentType) {
        switch (componentType) {
            case GltfConstants.GL_BYTE:
            case GltfConstants.GL_UNSIGNED_BYTE:
                return 1;
            case GltfConstants.GL_SHORT:
            case GltfConstants.GL_UNSIGNED_SHORT:
                return 2;
            case GltfConstants.GL_INT:
            case GltfConstants.GL_UNSIGNED_INT:
            case GltfConstants.GL_FLOAT:
                return 4;
            default:
                throw new IllegalArgumentException("Unknown component type: " + componentType);
        }
    }
}