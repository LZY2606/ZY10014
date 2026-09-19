/*
 * Serialization/deserialization property tests for the 32-bit
 * EWAHCompressedBitmap32. Uses only java.util.Random with fixed seeds;
 * no third-party framework is required.
 *
 * In particular these tests pin the behaviour around the caller's
 * ByteBuffer/DataInput position when the input is malformed.
 */
package com.googlecode.javaewah32;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Test;

import com.googlecode.javaewah.IntIterator;

@SuppressWarnings("javadoc")
public final class SerializationProperty32Test {

    static final long SEED = 55221188L;

    static byte[] serialize(final EWAHCompressedBitmap32 b) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.serialize(new DataOutputStream(bos));
        return bos.toByteArray();
    }

    static EWAHCompressedBitmap32 randomBitmap(final long seed,
                                               final int size) {
        final Random r = new Random(seed);
        final EWAHCompressedBitmap32 b = new EWAHCompressedBitmap32();
        int i = 0;
        while (i < size) {
            final int block = 1 + r.nextInt(97);
            final boolean dense = (i / block) % 2 == 1;
            for (int k = 0; k < block && i < size; k++, i++) {
                final double p = dense ? 0.9 : 0.05;
                if (r.nextDouble() < p) {
                    b.set(i);
                }
            }
        }
        b.setSizeInBits(size, false);
        return b;
    }

    @Test
    public void roundTripRandomBitmapsViaDataInput() throws IOException {
        final long seed = SEED;
        final int[] sizes = {
                1, 31, 32, 33, 34, 63, 64, 65, 95, 96, 97, 3000
        };
        int index = 0;
        for (final int size : sizes) {
            final EWAHCompressedBitmap32 original =
                    randomBitmap(seed + index, size);
            final byte[] data = serialize(original);
            assertEquals("seed=" + seed + " case=" + index
                            + " serializedSizeInBytes",
                    data.length, original.serializedSizeInBytes());
            final EWAHCompressedBitmap32 decoded =
                    new EWAHCompressedBitmap32();
            decoded.deserialize(new DataInputStream(
                    new ByteArrayInputStream(data)));
            assertEquals("seed=" + seed + " case=" + index, original, decoded);
            assertEquals("seed=" + seed + " case=" + index + " sizeInBits",
                    size, decoded.sizeInBits());
            index++;
        }
    }

    @Test
    public void byteBufferWithNonZeroPositionIsAccepted() throws IOException {
        final EWAHCompressedBitmap32 original = randomBitmap(SEED + 1, 800);
        final byte[] payload = serialize(original);
        final int pad = 13;
        final ByteBuffer bb = ByteBuffer.allocate(payload.length + pad + 7);
        bb.position(pad);
        bb.put(payload);
        bb.position(pad);

        final int positionBefore = bb.position();
        final EWAHCompressedBitmap32 view =
                new EWAHCompressedBitmap32(bb);
        assertEquals("caller ByteBuffer position must not move",
                positionBefore, bb.position());
        assertEquals(original, view);
        assertEquals(original.sizeInBits(), view.sizeInBits());

        bb.position(pad);
        final ByteBuffer sliced = bb.slice();
        final EWAHCompressedBitmap32 view2 =
                new EWAHCompressedBitmap32(sliced);
        assertEquals(original, view2);
    }

    @Test
    public void truncatedByteBufferFailsWithoutMovingPosition()
            throws IOException {
        final EWAHCompressedBitmap32 original = randomBitmap(SEED + 2, 1500);
        final byte[] payload = serialize(original);
        assertTrue(payload.length > 8);
        final int[] cuts = {
                0, 1, 4, 7, 8, payload.length / 2, payload.length - 5,
                payload.length - 4
        };
        for (final int cut : cuts) {
            final ByteBuffer bb =
                    ByteBuffer.wrap(Arrays.copyOf(payload, cut));
            final int positionBefore = bb.position();
            try {
                new EWAHCompressedBitmap32(bb);
                fail("expected a failure for truncated input cut=" + cut
                        + "/" + payload.length);
            } catch (final RuntimeException expected) {
                // index failure rather than silently accepting data
            }
            assertEquals("position must be unchanged on failure cut=" + cut,
                    positionBefore, bb.position());
        }
    }

    @Test
    public void truncatedDataInputThrowsIOException() throws IOException {
        final EWAHCompressedBitmap32 original = randomBitmap(SEED + 3, 1500);
        final byte[] payload = serialize(original);
        for (final int cut : new int[]{0, 1, 7, payload.length - 5,
                payload.length - 4}) {
            final DataInputStream in = new DataInputStream(
                    new ByteArrayInputStream(Arrays.copyOf(payload, cut)));
            final EWAHCompressedBitmap32 decoded =
                    new EWAHCompressedBitmap32();
            try {
                decoded.deserialize(in);
                fail("expected EOFException for cut=" + cut + "/"
                        + payload.length);
            } catch (final EOFException expected) {
                // required contract of DataInput
            }
        }
    }

    @Test
    public void twoObjectsDeserializeConsecutively() throws IOException {
        final EWAHCompressedBitmap32 first = randomBitmap(SEED + 4, 400);
        final EWAHCompressedBitmap32 second = randomBitmap(SEED + 5, 900);
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bos);
        first.serialize(out);
        second.serialize(out);

        final byte[] data = bos.toByteArray();
        assertEquals(data.length,
                first.serializedSizeInBytes()
                        + second.serializedSizeInBytes());

        final DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(data));
        final EWAHCompressedBitmap32 d1 = new EWAHCompressedBitmap32();
        d1.deserialize(in);
        assertEquals(first, d1);
        assertEquals("stream must be left at the second object",
                in.available(), second.serializedSizeInBytes());

        final EWAHCompressedBitmap32 d2 = new EWAHCompressedBitmap32();
        d2.deserialize(in);
        assertEquals(second, d2);
        assertEquals(0, in.available());

        // Frame boundary: after the first object, only one trailing byte
        // is available; reading the next object must fail with EOF rather
        // than silently skipping past the boundary.
        final byte[] oneByteAfter = Arrays.copyOf(data,
                first.serializedSizeInBytes() + 1);
        final DataInputStream oin = new DataInputStream(
                new ByteArrayInputStream(oneByteAfter));
        final EWAHCompressedBitmap32 d3 = new EWAHCompressedBitmap32();
        d3.deserialize(oin);
        assertEquals(first, d3);
        try {
            new EWAHCompressedBitmap32().deserialize(oin);
            fail("expected EOFException when the next frame header is cut");
        } catch (final EOFException expected) {
            // DataInput never fabricates bytes
        }

        final DataInputStream onlyFirst = new DataInputStream(
                new ByteArrayInputStream(
                        Arrays.copyOf(data, first.serializedSizeInBytes())));
        final EWAHCompressedBitmap32 d4 = new EWAHCompressedBitmap32();
        d4.deserialize(onlyFirst);
        assertEquals(first, d4);
        try {
            new EWAHCompressedBitmap32().deserialize(onlyFirst);
            fail("expected EOFException at end of a single-object stream");
        } catch (final EOFException expected) {
            // expected
        }
        assertEquals(0, onlyFirst.available());
    }

    @Test
    public void memoryMappedFileViewRoundTrips() throws IOException {
        final int[] sizes = {33, 64, 65, 2500};
        int index = 0;
        for (final int size : sizes) {
            final EWAHCompressedBitmap32 original =
                    randomBitmap(SEED + 10 + index, size);
            final File tmp = File.createTempFile("javaewah-prop32", ".bin");
            tmp.deleteOnExit();
            final java.io.FileOutputStream fos =
                    new java.io.FileOutputStream(tmp);
            original.serialize(new DataOutputStream(fos));
            fos.close();
            final RandomAccessFile read = new RandomAccessFile(tmp, "r");
            try {
                final long length = tmp.length();
                final ByteBuffer mapped = read.getChannel().map(
                        FileChannel.MapMode.READ_ONLY, 0, length);
                final EWAHCompressedBitmap32 view =
                        new EWAHCompressedBitmap32(mapped);
                assertEquals("seed=" + SEED + " mmap case=" + index,
                        original, view);
                assertEquals(size, view.sizeInBits());

                assertEquals(view, view.and(view));
                assertEquals(0, view.xor(view).cardinality());

                final List<Integer> forward = new ArrayList<Integer>();
                final IntIterator fit = view.intIterator();
                while (fit.hasNext()) {
                    forward.add(fit.next());
                }
                final List<Integer> reverse = new ArrayList<Integer>();
                final IntIterator rit = view.reverseIntIterator();
                while (rit.hasNext()) {
                    reverse.add(rit.next());
                }
                assertEquals(forward.size(), reverse.size());
                for (int k = 0; k < forward.size(); k++) {
                    assertEquals(forward.get(forward.size() - 1 - k),
                            reverse.get(k));
                }
            } finally {
                read.close();
                tmp.delete();
            }
            index++;
        }
    }

    @Test
    public void truncatedMemoryMappedViewFailsLoudly() throws IOException {
        final EWAHCompressedBitmap32 original = randomBitmap(SEED + 20, 3000);
        final byte[] payload = serialize(original);
        final File tmp =
                File.createTempFile("javaewah-prop32-trunc", ".bin");
        tmp.deleteOnExit();
        final java.io.FileOutputStream fos =
                new java.io.FileOutputStream(tmp);
        fos.write(payload, 0, payload.length - 7);
        fos.close();
        final RandomAccessFile read = new RandomAccessFile(tmp, "r");
        try {
            final ByteBuffer mapped = read.getChannel().map(
                    FileChannel.MapMode.READ_ONLY, 0, tmp.length());
            try {
                new EWAHCompressedBitmap32(mapped);
                fail("expected failure for truncated memory-mapped view");
            } catch (final RuntimeException expected) {
                // index failure rather than silent corruption
            }
        } finally {
            read.close();
            tmp.delete();
        }
    }

    @Test
    public void serializationIsByteStable() throws IOException {
        final EWAHCompressedBitmap32 b = randomBitmap(SEED + 30, 1234);
        final byte[] first = serialize(b);
        final EWAHCompressedBitmap32 d = new EWAHCompressedBitmap32();
        d.deserialize(new DataInputStream(new ByteArrayInputStream(first)));
        final byte[] second = serialize(d);
        assertArrayEquals(first, second);
    }
}
