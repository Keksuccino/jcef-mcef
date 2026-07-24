// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.misc.DataPointer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicReference;

class DataPointerTest {
    private static final long ROOT_ADDRESS = 0x1000L;
    private static final Constructor<DataPointer> AUDIO_CONSTRUCTOR = audioConstructor();
    private static final Method INVALIDATE_METHOD = invalidateMethod();

    @Test
    void loadsAndUsesAudioViewWithoutLwjgl() throws Exception {
        DataPointer packet = packet(2, 2);

        assertEquals(ROOT_ADDRESS, packet.getAddress());
        assertEquals(10.25f, packet.getData(0).getFloat(0));
        assertEquals(21.25f, packet.getData(1).getFloat(1));
    }

    @Test
    void enforcesRootChannelAndFrameBounds() throws Exception {
        DataPointer packet = packet(2, 2);
        DataPointer channel = packet.getData(1);

        assertThrows(IndexOutOfBoundsException.class, () -> packet.forCapacity(2 * Long.BYTES + 1));
        assertThrows(IndexOutOfBoundsException.class, () -> packet.getData(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> packet.getData(2));
        assertThrows(IndexOutOfBoundsException.class, () -> channel.forCapacity(2 * Float.BYTES + 1));
        assertThrows(IndexOutOfBoundsException.class, () -> channel.getFloat(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> channel.getFloat(2));
        assertThrows(UnsupportedOperationException.class, () -> channel.getData(0));

        packet.forCapacity(Long.BYTES);
        assertThrows(IndexOutOfBoundsException.class, () -> packet.getData(1));
        packet.forCapacity(2 * Long.BYTES);
        assertEquals(21.25f, packet.getData(1).getFloat(1));
    }

    @Test
    void invalidationIsSharedWithExistingChannelViews() throws Exception {
        DataPointer packet = packet(1, 1);
        DataPointer channel = packet.getData(0);

        INVALIDATE_METHOD.invoke(packet);

        assertThrows(IllegalStateException.class, packet::getAddress);
        assertThrows(IllegalStateException.class, () -> packet.getData(0));
        assertThrows(IllegalStateException.class, channel::getAddress);
        assertThrows(IllegalStateException.class, () -> channel.getFloat(0));
    }

    @Test
    void rejectsAccessFromAnyThreadOtherThanCallbackThread() throws Exception {
        DataPointer packet = packet(1, 1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable accessFromWrongThread = () -> {
            try {
                packet.getData(0);
            } catch (Throwable err) {
                failure.set(err);
            }
        };
        Thread otherThread = new Thread(accessFromWrongThread);

        otherThread.start();
        otherThread.join();

        assertTrue(failure.get() instanceof IllegalStateException);
        assertEquals(10.25f, packet.getData(0).getFloat(0));
    }

    @Test
    void readsPointersAndSamplesUsingNativeByteOrder() throws Exception {
        DataPointer packet = packet(1, 2);

        assertEquals(channelAddress(0), packet.getLong(0));
        assertEquals(10.25f, packet.getData(0).getFloat(0));
        assertEquals(11.25f, packet.getData(0).getFloat(1));
    }

    @Test
    void supportsAZeroFramePacketWithoutExposingSamples() throws Exception {
        DataPointer packet = packet(1, 0);
        DataPointer channel = packet.getData(0);

        channel.forCapacity(0);
        assertThrows(IndexOutOfBoundsException.class, () -> channel.getFloat(0));
    }

    private static DataPointer packet(int channels, int frames) throws Exception {
        ByteBuffer pointers = ByteBuffer.allocateDirect(channels * Long.BYTES).order(ByteOrder.nativeOrder());
        long[] addresses = new long[channels];
        ByteBuffer[] samples = new ByteBuffer[channels];
        for (int channel = 0; channel < channels; channel++) {
            addresses[channel] = channelAddress(channel);
            pointers.putLong(channel * Long.BYTES, addresses[channel]);
            samples[channel] = ByteBuffer.allocateDirect(frames * Float.BYTES).order(ByteOrder.nativeOrder());
            for (int frame = 0; frame < frames; frame++) {
                samples[channel].putFloat(frame * Float.BYTES, channel * 10.0f + frame + 10.25f);
            }
        }
        return AUDIO_CONSTRUCTOR.newInstance(ROOT_ADDRESS, pointers, addresses, samples, frames, Long.BYTES);
    }

    private static long channelAddress(int channel) {
        return 0x2000L + channel * 0x100L;
    }

    private static Constructor<DataPointer> audioConstructor() {
        try {
            Constructor<DataPointer> constructor = DataPointer.class.getDeclaredConstructor(long.class, ByteBuffer.class, long[].class, ByteBuffer[].class, int.class, int.class);
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException err) {
            throw new ExceptionInInitializerError(err);
        }
    }

    private static Method invalidateMethod() {
        try {
            Method method = DataPointer.class.getDeclaredMethod("invalidate");
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException err) {
            throw new ExceptionInInitializerError(err);
        }
    }
}
