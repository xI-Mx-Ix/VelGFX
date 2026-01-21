/*
 * This file is part of VelGFX.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velgfx.resources;

import net.xmx.velgfx.renderer.util.VxGlGarbageCollector;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;

/**
 * A standalone wrapper for off-heap image data, replacing Minecraft's NativeImage.
 * <p>
 * This class uses LWJGL's {@link STBImage} bindings to load images directly into
 * native memory. It manages the lifecycle of the unmanaged memory pointers and
 * ensures correct deallocation depending on the allocation source (STB vs. MemoryUtil).
 *
 * @author xI-Mx-Ix
 */
public class VxNativeImage implements AutoCloseable {

    /**
     * The direct ByteBuffer pointing to the RGBA pixel data.
     */
    private final ByteBuffer pixelData;

    private final int width;
    private final int height;

    /**
     * Flag indicating if the memory was allocated by STB (requires stbi_image_free)
     * or by MemoryUtil (requires memFree).
     */
    private final boolean allocatedByStb;

    private boolean isClosed = false;

    /**
     * Handle to the cleaner task.
     */
    private final Cleaner.Cleanable cleanable;

    /**
     * Internal constructor. Use {@link #read(InputStream)} or {@link #create(int, int)}.
     *
     * @param pixelData      The native memory buffer.
     * @param width          The image width.
     * @param height         The image height.
     * @param allocatedByStb True if allocated via stbi_load, false if via memAlloc.
     */
    private VxNativeImage(ByteBuffer pixelData, int width, int height, boolean allocatedByStb) {
        this.pixelData = pixelData;
        this.width = width;
        this.height = height;
        this.allocatedByStb = allocatedByStb;

        ByteBuffer bufferToFree = this.pixelData;
        boolean isStb = this.allocatedByStb;

        this.cleanable = VxGlGarbageCollector.getInstance().track(this, () -> {
            if (isStb) {
                STBImage.stbi_image_free(bufferToFree);
            } else {
                MemoryUtil.memFree(bufferToFree);
            }
        });
    }

    /**
     * Creates a new blank image with the specified dimensions.
     * The memory is initialized to zero (transparent black).
     *
     * @param width  Width in pixels.
     * @param height Height in pixels.
     * @return A new blank VxNativeImage.
     */
    public static VxNativeImage create(int width, int height) {
        // Allocate native memory for RGBA (4 bytes per pixel)
        // usage of calloc ensures zero-initialization
        ByteBuffer data = MemoryUtil.memCalloc(width * height * 4);
        return new VxNativeImage(data, width, height, false);
    }

    /**
     * Reads an image from an input stream using STBImage.
     * <p>
     * The stream is read directly into a resizing native buffer to avoid Java Heap allocation.
     * Crucially, this method forces the image to be decoded as RGBA (4 channels) to ensure
     * alignment with OpenGL texture uploads.
     *
     * @param stream The input stream containing the image file (PNG, JPG, etc.).
     * @return A new VxNativeImage instance.
     * @throws IOException If the stream cannot be read or decoding fails.
     */
    public static VxNativeImage read(InputStream stream) throws IOException {
        ByteBuffer sourceBuffer = readToBuffer(stream);

        try {
            // Prepare stack for output parameters (width, height, channels)
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer w = stack.mallocInt(1);
                IntBuffer h = stack.mallocInt(1);
                IntBuffer c = stack.mallocInt(1);

                // Load image from memory. Force 4 channels (RGBA).
                // STBI_rgb_alpha = 4
                // STB allocates the result buffer using its own malloc implementation.
                ByteBuffer imageData = STBImage.stbi_load_from_memory(sourceBuffer, w, h, c, 4);

                if (imageData == null) {
                    throw new IOException("Failed to load image via STB: " + STBImage.stbi_failure_reason());
                }

                return new VxNativeImage(imageData, w.get(0), h.get(0), true);
            }
        } finally {
            // Always free the temporary source buffer containing the raw file bytes
            MemoryUtil.memFree(sourceBuffer);
        }
    }

    /**
     * Reads an image directly from a ByteBuffer containing the raw file data (PNG/JPG bytes).
     * <p>
     * This is used for embedded textures in GLB files. It automatically handles the conversion
     * of Java Heap buffers (byte arrays) to Direct Native buffers if required by LWJGL.
     *
     * @param buffer The buffer containing the encoded image file data.
     * @return A new VxNativeImage instance.
     * @throws IOException If decoding fails.
     */
    public static VxNativeImage read(ByteBuffer buffer) throws IOException {
        ByteBuffer directBuffer = buffer;
        boolean isTemporaryBuffer = false;

        // LWJGL's STB binding requires a Direct ByteBuffer (Off-Heap).
        // If we pass a Heap buffer to the native library, it causes an Access Violation crash.
        if (!buffer.isDirect()) {
            int size = buffer.remaining();
            // Allocate temporary native memory
            directBuffer = MemoryUtil.memAlloc(size);

            // Save current position to restore later
            int originalPos = buffer.position();

            // Copy data from Java Heap to Native Memory
            directBuffer.put(buffer);
            directBuffer.flip();

            // Restore source buffer position (good practice)
            buffer.position(originalPos);

            isTemporaryBuffer = true;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer c = stack.mallocInt(1);

            // STBI expects a direct buffer. Force 4 channels (RGBA)
            ByteBuffer imageData = STBImage.stbi_load_from_memory(directBuffer, w, h, c, 4);

            if (imageData == null) {
                throw new IOException("Failed to load embedded image via STB: " + STBImage.stbi_failure_reason());
            }

            return new VxNativeImage(imageData, w.get(0), h.get(0), true);
        } finally {
            // If we allocated a temporary direct buffer for the copy, free it now to prevent leaks.
            if (isTemporaryBuffer) {
                MemoryUtil.memFree(directBuffer);
            }
        }
    }

    /**
     * Sets a specific pixel's color in RGBA format.
     * <p>
     * <b>Color Format (Little Endian Integer):</b> 0xAABBGGRR<br>
     * R is the lowest byte, A is the highest.
     *
     * @param x     X coordinate.
     * @param y     Y coordinate.
     * @param color The packed color integer.
     */
    public void setPixelRGBA(int x, int y, int color) {
        if (isClosed) throw new IllegalStateException("Image is closed");
        if (x < 0 || x >= width || y < 0 || y >= height) return;

        // Calculate offset: (y * width + x) * 4 bytes
        int index = (x + y * width) * 4;

        // Unpack integer into bytes (0xAABBGGRR)
        byte a = (byte) ((color >> 24) & 0xFF);
        byte b = (byte) ((color >> 16) & 0xFF);
        byte g = (byte) ((color >> 8) & 0xFF);
        byte r = (byte) (color & 0xFF);

        // Store bytes in RGBA order for OpenGL
        pixelData.put(index, r);
        pixelData.put(index + 1, g);
        pixelData.put(index + 2, b);
        pixelData.put(index + 3, a);
    }

    /**
     * Gets the direct byte buffer containing the pixel data.
     *
     * @return The buffer.
     */
    public ByteBuffer getPixelData() {
        if (isClosed) throw new IllegalStateException("Image is closed");
        return pixelData;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /**
     * Reads an InputStream into a resizable DirectByteBuffer.
     * This avoids creating large byte[] arrays on the Java Heap.
     */
    private static ByteBuffer readToBuffer(InputStream stream) throws IOException {
        ReadableByteChannel rbc = Channels.newChannel(stream);
        ByteBuffer buffer = MemoryUtil.memAlloc(8192); // Start with 8KB

        while (true) {
            int bytes = rbc.read(buffer);
            if (bytes == -1) {
                break;
            }
            if (buffer.remaining() == 0) {
                // Resize buffer: double capacity
                buffer = MemoryUtil.memRealloc(buffer, buffer.capacity() * 2);
            }
        }

        buffer.flip();
        return buffer;
    }

    /**
     * Frees the native memory associated with this image.
     * Must be called when the image is no longer needed to prevent memory leaks.
     */
    @Override
    public void close() {
        if (!isClosed) {
            isClosed = true;
            // Trigger the cleaner immediately and unregister it
            cleanable.clean();
        }
    }
}