/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.renderer.model.loader;

import net.xmx.velgfx.renderer.VelGFX;
import net.xmx.velgfx.resources.VxResourceLocation;
import org.lwjgl.assimp.*;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles Low-level Input/Output operations for the Assimp Loader.
 * <p>
 * This class provides a custom {@link AIFileIO} implementation that redirects Assimp's
 * native file access requests to the Java Classpath (Resources). This allows models
 * and their dependencies (bin files, textures, mtl files) to be loaded directly from inside a JAR.
 *
 * @author xI-Mx-Ix
 */
public class VxAssimpIO {

    /**
     * Map to hold active file handles. This is critical to prevent the JVM Garbage Collector
     * from reaping the callback instances or the ByteBuffers while the native C++ code is executing.
     */
    private static final Map<Long, AssimpFileHandle> OPEN_FILES = new ConcurrentHashMap<>();

    /**
     * Creates an {@link AIFileIO} structure configured to read from the classpath.
     *
     * @param baseLocation The location of the main model file (used as context for relative paths).
     * @return An allocated AIFileIO structure.
     */
    public static AIFileIO createFileIO(VxResourceLocation baseLocation) {
        AIFileIO fileIo = AIFileIO.calloc();

        // 1. Define the Open Procedure
        fileIo.OpenProc(AIFileOpenProc.create((pFileIO, pFileName, openMode) -> {
            String requestedFileName = MemoryUtil.memUTF8(pFileName);

            // Normalize path separators
            requestedFileName = requestedFileName.replace('\\', '/');

            // Resolve relative paths (e.g., "../textures/skin.png") relative to the model
            String baseDir = baseLocation.getDirectory();
            String fullPath = resolveClasspathPath(baseDir, requestedFileName);

            ByteBuffer data;
            try {
                data = loadResourceToBuffer(fullPath);

                // Assimp cannot handle empty files (0 bytes). Treat as not found.
                if (data == null || data.limit() == 0) {
                    VelGFX.LOGGER.error("Assimp IO: Found file but it is empty: " + fullPath);
                    if (data != null) MemoryUtil.memFree(data);
                    return 0;
                }
            } catch (Exception e) {
                // Return NULL (0) to signal Assimp that the file was not found.
                if (requestedFileName.endsWith(".bin")) {
                    VelGFX.LOGGER.error("Assimp IO Critical: Could not find geometry file (bin): " + fullPath);
                }
                return 0;
            }

            // Create a handle to manage the buffer and callbacks for this specific file
            AssimpFileHandle handle = new AssimpFileHandle(data);

            // Allocate the AIFile struct
            AIFile aiFile = AIFile.calloc();
            aiFile.ReadProc(handle.readProc);
            aiFile.SeekProc(handle.seekProc);
            aiFile.TellProc(handle.tellProc);
            aiFile.FileSizeProc(handle.sizeProc);
            aiFile.FlushProc(handle.flushProc);

            // Store the handle reference to prevent Garbage Collection while Assimp uses it
            long fileAddress = aiFile.address();
            OPEN_FILES.put(fileAddress, handle);

            return fileAddress;
        }));

        // 2. Define the Close Procedure
        fileIo.CloseProc(AIFileCloseProc.create((pFileIO, pFile) -> {
            // Retrieve and remove the handle
            AssimpFileHandle handle = OPEN_FILES.remove(pFile);
            if (handle != null) {
                handle.free(); // Free the buffer and callback stubs
            }
            // Free the AIFile struct itself
            AIFile.create(pFile).free();
        }));

        return fileIo;
    }

    /**
     * Frees the main AIFileIO struct and its global callbacks.
     *
     * @param fileIo The structure to free.
     */
    public static void freeFileIO(AIFileIO fileIo) {
        fileIo.OpenProc().free();
        fileIo.CloseProc().free();
        fileIo.free();
    }

    /**
     * Loads a classpath resource into a direct ByteBuffer using LWJGL MemoryUtil.
     */
    private static ByteBuffer loadResourceToBuffer(String path) throws IOException {
        String resourcePath = path.startsWith("/") ? path : "/" + path;
        try (InputStream stream = VxAssimpLoader.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IOException("Resource not found in classpath: " + resourcePath);
            }
            byte[] bytes = stream.readAllBytes();

            ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
            buffer.put(bytes);
            buffer.flip();
            return buffer;
        }
    }

    /**
     * Resolves complex relative paths (e.g. containing ".." or ".") to a canonical classpath string.
     *
     * @param baseDir The directory context.
     * @param target  The relative path.
     * @return The canonical absolute path.
     */
    private static String resolveClasspathPath(String baseDir, String target) {
        if (target.startsWith("/")) return target; // Already absolute

        // Ensure baseDir ends with /
        String prefix = baseDir.endsWith("/") ? baseDir : baseDir + "/";
        String combined = prefix + target;
        String[] parts = combined.split("/");

        Deque<String> stack = new ArrayDeque<>();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;

            if (part.equals("..")) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
            } else {
                stack.addLast(part);
            }
        }

        return String.join("/", stack);
    }

    /**
     * Helper class to encapsulate the state of a single open file.
     */
    private static class AssimpFileHandle {
        final ByteBuffer data;
        final AIFileReadProc readProc;
        final AIFileSeek seekProc;
        final AIFileTellProc tellProc;
        final AIFileTellProc sizeProc;
        final AIFileFlushProc flushProc;

        AssimpFileHandle(ByteBuffer sourceData) {
            this.data = sourceData;

            // Read: Safely copy bytes from our buffer to Assimp's pointer
            this.readProc = AIFileReadProc.create((pFile, pBuffer, size, count) -> {
                long totalRequestedBytes = size * count;
                long remaining = data.remaining();
                long actualReadBytes = Math.min(totalRequestedBytes, remaining);

                if (actualReadBytes > 0) {
                    // Wrap the target native address in a DirectByteBuffer.
                    // This is safer than memCopy for potentially unaligned addresses.
                    ByteBuffer target = MemoryUtil.memByteBuffer(pBuffer, (int) actualReadBytes);

                    // Slice source to avoid modifying its limit permanently during copy
                    int oldLimit = data.limit();
                    data.limit(data.position() + (int) actualReadBytes);
                    target.put(data);
                    data.limit(oldLimit); // Restore limit
                }

                // Assimp expects return value in 'items' (chunks of 'size'), not bytes.
                return (size > 0) ? (actualReadBytes / size) : 0;
            });

            // Seek: Reposition the buffer
            this.seekProc = AIFileSeek.create((pFile, offset, origin) -> {
                int newPosition = switch (origin) {
                    case Assimp.aiOrigin_SET -> (int) offset;
                    case Assimp.aiOrigin_CUR -> data.position() + (int) offset;
                    case Assimp.aiOrigin_END -> data.limit() + (int) offset;
                    default -> -1;
                };

                // Allow seeking to EOF (limit) as Assimp does this to check file size
                if (newPosition < 0 || newPosition > data.limit()) {
                    return Assimp.aiReturn_FAILURE;
                }
                data.position(newPosition);
                return Assimp.aiReturn_SUCCESS;
            });

            this.tellProc = AIFileTellProc.create((pFile) -> (long) data.position());
            this.sizeProc = AIFileTellProc.create((pFile) -> (long) data.limit());
            this.flushProc = AIFileFlushProc.create((pFile) -> {});
        }

        void free() {
            readProc.free();
            seekProc.free();
            tellProc.free();
            sizeProc.free();
            flushProc.free();
            MemoryUtil.memFree(data); // Important: Free the off-heap buffer
        }
    }
}