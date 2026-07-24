package org.cef.misc;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Provides typed access to a native memory address.
 *
 * <p>Instances supplied to {@code CefAudioHandler.onAudioStreamPacket} are special bounded views
 * over CEF-owned memory. Those views and every child returned by {@link #getData(int)} are confined
 * to the callback thread and invalidated as soon as the callback returns. They must not be retained
 * or accessed from another thread. Copy any data that is needed after the callback.
 *
 * <p>The public address constructor remains available for compatibility and cannot infer the
 * allocation's lifetime or bounds. Callers creating such an instance remain responsible for both.
 * LWJGL is resolved lazily only if such a legacy view calls {@link #forCapacity(int)}; bounded
 * audio callback views use JNI direct buffers and have no LWJGL dependency.
 */
public class DataPointer {
    private static final int UNBOUNDED = -1;
    private static final String INVALID_POINTER = "The native data pointer is no longer valid";
    private static final String WRONG_THREAD = "Callback-scoped native data may only be accessed on the callback thread";

    private final long address;
    private final Lifetime lifetime;
    private final View view;
    private ByteBuffer dataBuffer;
    private boolean initialized;
    private int alignment;

    /** Creates an unbounded view over caller-managed native memory. */
    public DataPointer(long address) {
        this(address, new Lifetime(false), View.unbounded());
    }

    /** Creates the bounded callback-scoped view used only by the native audio bridge. */
    private DataPointer(long address, ByteBuffer pointerBuffer, long[] channelAddresses, ByteBuffer[] channelBuffers, int frames, int pointerSize) {
        this(address, new Lifetime(true), View.audio(pointerBuffer, channelAddresses, channelBuffers, frames, pointerSize));
    }

    private DataPointer(long address, Lifetime lifetime, View view) {
        if (address == 0 && view.bounds.maximumCapacity > 0) throw new IllegalArgumentException("address must not be null for non-empty native data");
        this.address = address;
        this.lifetime = lifetime;
        this.view = view;
        dataBuffer = view.buffer;
        initialized = dataBuffer != null;
        alignment = view.bounds.alignment;
    }

    /**
     * Maps {@code capacity} bytes at this address. Callback-scoped audio views reject capacities
     * larger than the channel or frame bound supplied by native code.
     */
    public DataPointer forCapacity(int capacity) {
        lifetime.ensureAccessible();
        validateCapacity(capacity);
        if (view.buffer != null) {
            dataBuffer.position(0);
            dataBuffer.limit(capacity);
        } else {
            dataBuffer = LegacyMemoryUtil.map(address, capacity);
        }
        initialized = true;
        return this;
    }

    /**
     * Selects the power-of-two byte stride used by typed indexed access. The audio bridge fixes
     * this value from the native pointer/sample representation and does not allow changing it.
     */
    public DataPointer withAlignment(int alignment) {
        lifetime.ensureAccessible();
        if (alignment < 0 || alignment > 30) throw new IllegalArgumentException("alignment must be between 0 and 30");
        if (lifetime.callbackScoped && alignment != this.alignment) throw new UnsupportedOperationException("Callback-scoped pointer alignment cannot be changed");
        this.alignment = alignment;
        return this;
    }

    /**
     * Returns the native address while this view is valid. Never retain an address obtained from a
     * callback-scoped audio view or dereference it outside the callback.
     */
    public long getAddress() {
        lifetime.ensureAccessible();
        return address;
    }

    /**
     * Dereferences a pointer element. For audio packets, {@code offset} is a channel index and the
     * returned view contains exactly the frame count passed to the callback.
     */
    public DataPointer getData(int offset) {
        ensurePointerDereferenceAllowed();
        int byteOffset = checkedByteOffset(offset, view.bounds.pointerSize);
        if (view.channelBuffers != null) {
            View childView = View.samples(view.channelBuffers[offset], view.bounds.childElementCount);
            return new DataPointer(view.channelAddresses[offset], lifetime, childView);
        }
        long childAddress;
        if (view.bounds.pointerSize == Integer.BYTES) {
            childAddress = Integer.toUnsignedLong(dataBuffer.getInt(byteOffset));
        } else {
            childAddress = dataBuffer.getLong(byteOffset);
        }
        return new DataPointer(childAddress, lifetime, View.unbounded());
    }

    public long getLong(int offset) {
        return dataBuffer.getLong(checkedByteOffset(offset, Long.BYTES));
    }

    public int getInt(int offset) {
        return dataBuffer.getInt(checkedByteOffset(offset, Integer.BYTES));
    }

    public short getShort(int offset) {
        return dataBuffer.getShort(checkedByteOffset(offset, Short.BYTES));
    }

    public byte getByte(int offset) {
        return dataBuffer.get(checkedByteOffset(offset, Byte.BYTES));
    }

    public double getDouble(int offset) {
        return dataBuffer.getDouble(checkedByteOffset(offset, Double.BYTES));
    }

    public float getFloat(int offset) {
        return dataBuffer.getFloat(checkedByteOffset(offset, Float.BYTES));
    }

    /** Called by the native bridge immediately after the Java audio callback returns. */
    private void invalidate() {
        lifetime.invalidate();
        dataBuffer = null;
        initialized = false;
    }

    private void validateCapacity(int capacity) {
        if (capacity < 0) throw new IllegalArgumentException("capacity must not be negative");
        int maximumCapacity = view.bounds.maximumCapacity;
        if (maximumCapacity != UNBOUNDED && capacity > maximumCapacity) throw new IndexOutOfBoundsException("capacity exceeds the native data bound");
        if (address == 0 && capacity > 0) throw new IllegalStateException("Cannot map a null native address");
    }

    private int checkedByteOffset(int offset, int valueSize) {
        lifetime.ensureAccessible();
        if (!initialized) throw new IllegalStateException("DataPointer#forCapacity must be called before the data can be accessed");
        int elementCount = view.bounds.elementCount;
        if (offset < 0 || elementCount != UNBOUNDED && offset >= elementCount) throw new IndexOutOfBoundsException("offset is outside the native data element bound");
        long byteOffset = (long) offset << alignment;
        long endOffset = byteOffset + valueSize;
        if (byteOffset < 0 || endOffset < byteOffset || endOffset > dataBuffer.limit()) throw new IndexOutOfBoundsException("access is outside the mapped native data capacity");
        return (int) byteOffset;
    }

    private void ensurePointerDereferenceAllowed() {
        lifetime.ensureAccessible();
        if (!view.bounds.pointerDereferenceAllowed) throw new UnsupportedOperationException("PCM sample data cannot be dereferenced as another pointer");
    }

    private static ByteBuffer nativeBuffer(ByteBuffer buffer, int requiredCapacity) {
        if (buffer == null || !buffer.isDirect()) throw new IllegalArgumentException("Audio data requires a direct byte buffer");
        if (buffer.capacity() < requiredCapacity) throw new IllegalArgumentException("Direct byte buffer is smaller than the native data bound");
        ByteBuffer duplicate = buffer.duplicate().order(ByteOrder.nativeOrder());
        duplicate.clear();
        duplicate.limit(requiredCapacity);
        return duplicate.slice().order(ByteOrder.nativeOrder());
    }

    private static int checkedCapacity(String name, int elementCount, int elementSize) {
        if (elementCount < 0) throw new IllegalArgumentException(name + " must not be negative");
        try {
            return Math.multiplyExact(elementCount, elementSize);
        } catch (ArithmeticException err) {
            throw new IllegalArgumentException(name + " exceeds the supported native buffer capacity", err);
        }
    }

    private static int pointerAlignment(int pointerSize) {
        if (pointerSize != Integer.BYTES && pointerSize != Long.BYTES) throw new IllegalArgumentException("Unsupported native pointer size: " + pointerSize);
        return Integer.numberOfTrailingZeros(pointerSize);
    }

    private static final class View {
        private final Bounds bounds;
        private final ByteBuffer buffer;
        private final long[] channelAddresses;
        private final ByteBuffer[] channelBuffers;

        private View(Bounds bounds, ByteBuffer buffer, long[] channelAddresses, ByteBuffer[] channelBuffers) {
            this.bounds = bounds;
            this.buffer = buffer;
            this.channelAddresses = channelAddresses;
            this.channelBuffers = channelBuffers;
        }

        private static View unbounded() {
            return new View(Bounds.unbounded(), null, null, null);
        }

        private static View audio(ByteBuffer pointerBuffer, long[] channelAddresses, ByteBuffer[] channelBuffers, int frames, int pointerSize) {
            if (channelAddresses == null || channelBuffers == null) throw new IllegalArgumentException("Audio channel data must not be null");
            if (channelAddresses.length == 0 || channelAddresses.length != channelBuffers.length) throw new IllegalArgumentException("Audio channel data has inconsistent bounds");
            Bounds bounds = Bounds.audio(channelAddresses.length, frames, pointerSize);
            long[] addresses = channelAddresses.clone();
            ByteBuffer[] buffers = channelBuffers.clone();
            for (int channel = 0; channel < buffers.length; channel++) {
                if (addresses[channel] == 0 && bounds.childCapacity > 0) throw new IllegalArgumentException("Audio channel address must not be null");
                buffers[channel] = nativeBuffer(buffers[channel], bounds.childCapacity);
            }
            return new View(bounds, nativeBuffer(pointerBuffer, bounds.maximumCapacity), addresses, buffers);
        }

        private static View samples(ByteBuffer buffer, int frames) {
            Bounds bounds = Bounds.samples(frames);
            return new View(bounds, nativeBuffer(buffer, bounds.maximumCapacity), null, null);
        }
    }

    private static final class Bounds {
        private final int maximumCapacity;
        private final int elementCount;
        private final int pointerSize;
        private final int childCapacity;
        private final int childElementCount;
        private final boolean pointerDereferenceAllowed;
        private final int alignment;

        private Bounds(int maximumCapacity, int elementCount, int pointerSize, int childCapacity, int childElementCount, boolean pointerDereferenceAllowed, int alignment) {
            this.maximumCapacity = maximumCapacity;
            this.elementCount = elementCount;
            this.pointerSize = pointerSize;
            this.childCapacity = childCapacity;
            this.childElementCount = childElementCount;
            this.pointerDereferenceAllowed = pointerDereferenceAllowed;
            this.alignment = alignment;
        }

        private static Bounds unbounded() {
            return new Bounds(UNBOUNDED, UNBOUNDED, Long.BYTES, UNBOUNDED, UNBOUNDED, true, 3);
        }

        private static Bounds audio(int channels, int frames, int pointerSize) {
            if (channels <= 0) throw new IllegalArgumentException("channels must be greater than zero");
            int alignment = pointerAlignment(pointerSize);
            int pointerBytes = checkedCapacity("channels", channels, pointerSize);
            int sampleBytes = checkedCapacity("frames", frames, Float.BYTES);
            return new Bounds(pointerBytes, channels, pointerSize, sampleBytes, frames, true, alignment);
        }

        private static Bounds samples(int frames) {
            int sampleBytes = checkedCapacity("frames", frames, Float.BYTES);
            return new Bounds(sampleBytes, frames, Long.BYTES, UNBOUNDED, UNBOUNDED, false, 2);
        }
    }

    private static final class Lifetime {
        private final boolean callbackScoped;
        private final Thread ownerThread;
        private volatile boolean valid = true;

        private Lifetime(boolean callbackScoped) {
            this.callbackScoped = callbackScoped;
            ownerThread = callbackScoped ? Thread.currentThread() : null;
        }

        private void ensureAccessible() {
            if (!valid) throw new IllegalStateException(INVALID_POINTER);
            if (callbackScoped && Thread.currentThread() != ownerThread) throw new IllegalStateException(WRONG_THREAD);
        }

        private void invalidate() {
            valid = false;
        }
    }

    /** Resolves optional LWJGL support only when a legacy unbounded view explicitly needs it. */
    private static final class LegacyMemoryUtil {
        private static final MethodHandle MEM_BYTE_BUFFER;
        private static final Throwable LOAD_FAILURE;

        static {
            MethodHandle method = null;
            Throwable failure = null;
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                ClassLoader loader = lookup.lookupClass().getClassLoader();
                Class<?> memoryUtil = Class.forName("org.lwjgl.system.MemoryUtil", false, loader);
                MethodType type = MethodType.methodType(ByteBuffer.class, long.class, int.class);
                method = lookup.findStatic(memoryUtil, "memByteBuffer", type);
            } catch (Throwable err) {
                failure = err;
            }
            MEM_BYTE_BUFFER = method;
            LOAD_FAILURE = failure;
        }

        private static ByteBuffer map(long address, int capacity) {
            if (MEM_BYTE_BUFFER == null) throw new IllegalStateException("LWJGL MemoryUtil is required for legacy raw-address access", LOAD_FAILURE);
            try {
                ByteBuffer buffer = (ByteBuffer) MEM_BYTE_BUFFER.invoke(address, capacity);
                return buffer.order(ByteOrder.nativeOrder());
            } catch (Throwable err) {
                throw new IllegalStateException("Failed to create a legacy native byte buffer", err);
            }
        }
    }
}
