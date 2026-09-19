/*
 * Serialization/deserialization property tests for the 64-bit
 * EWAHCompressedBitmap. Uses only java.util.Random with fixed seeds;
 * no third-party framework is required.
 *
 * In particular these tests pin the behaviour around the caller's
 * ByteBuffer/DataInput position when the input is malformed.
 */
package com.googlecode.javaewah;

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

@SuppressWarnings("javadoc")
public final class SerializationProperty64Test {

    static final long SEED = 99117733L;

    static byte[] serialize(final EWAHCompressedBitmap b) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.serialize(new DataOutputStream(bos));
        return bos.toByteArray();
    }

    static EWAHCompressedBitmap randomBitmap(final long seed,
                                             final int size) {
        final Random r = new Random(seed);
        final EWAHCompressedBitmap b = new EWAHCompressedBitmap();
        int i = 0;
        while (i < size) {
            final int block = 1 + r.nextInt(160);
            final boolean dense = (i / block) % 2 == 1;
            for (int k = 0; k < block && i < size; k++, i++) {
                final double p = dense ? 0.9 : 0.05;
                if (r.nextDouble() < p) {
                    b.set(i);
                }
            }
        }
        // explicit sizes on both sides of a 64-bit boundary
        b.setSizeInBits(size, false);
        return b;
    }

    /** Round trip through serialize/deserialize over many shapes. */
    @Test
    public void roundTripRandomBitmapsViaDataInput() throws IOException {
        final long seed = SEED;
        final int[] sizes = {
                1, 63, 64, 65, 66, 127, 128, 129, 319, 320, 321, 5000
        };
        int index = 0;
        for (final int size : sizes) {
            final EWAHCompressedBitmap original =
                    randomBitmap(seed + index, size);
            final byte[] data = serialize(original);
            assertEquals("seed=" + seed + " case=" + index
                            + " serializedSizeInBytes",
                    data.length, original.serializedSizeInBytes());
            final EWAHCompressedBitmap decoded = new EWAHCompressedBitmap();
            decoded.deserialize(new DataInputStream(
                    new ByteArrayInputStream(data)));
            assertEquals("seed=" + seed + " case=" + index, original, decoded);
            assertEquals("seed=" + seed + " case=" + index + " sizeInBits",
                    size, decoded.sizeInBits());
            index++;
        }
    }

    /**
     * View constructor must accept a ByteBuffer with a non-zero position
     * (the serialized form lives in a slice of a larger buffer) and must
     * never mutate the caller's position.
     */
    @Test
    public void byteBufferWithNonZeroPositionIsAccepted() throws IOException {
        final EWAHCompressedBitmap original = randomBitmap(SEED + 1, 1000);
        final byte[] payload = serialize(original);
        final int pad = 11;
        final ByteBuffer bb = ByteBuffer.allocate(payload.length + pad + 5);
        bb.position(pad);
        bb.put(payload);
        bb.position(pad);

        final int positionBefore = bb.position();
        final EWAHCompressedBitmap view = new EWAHCompressedBitmap(bb);
        assertEquals("caller ByteBuffer position must not move",
                positionBefore, bb.position());
        assertEquals(original, view);
        assertEquals(original.sizeInBits(), view.sizeInBits());

        // the same backing bytes viewed from an explicit slice
        bb.position(pad);
        final ByteBuffer sliced = bb.slice();
        final EWAHCompressedBitmap view2 =
                new EWAHCompressedBitmap(sliced);
        assertEquals(original, view2);
    }

    /**
     * Truncated ByteBuffers must fail loudly and must not move the
     * caller's position, leaving the buffer recoverable.
     */
    @Test
    public void truncatedByteBufferFailsWithoutMovingPosition()
            throws IOException {
        final EWAHCompressedBitmap original = randomBitmap(SEED + 2, 2000);
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
                new EWAHCompressedBitmap(bb);
                fail("expected a failure for truncated input cut=" + cut
                        + "/" + payload.length);
            } catch (final RuntimeException expected) {
                // IndexOutOfBoundsException (or a related index exception)
                // must be thrown rather than silently accepting data.
            }
            assertEquals("position must be unchanged on failure cut=" + cut,
                    positionBefore, bb.position());
        }
    }

    /** Truncated DataInput must fail with an EOF/IOException. */
    @Test
    public void truncatedDataInputThrowsIOException() throws IOException {
        final EWAHCompressedBitmap original = randomBitmap(SEED + 3, 2000);
        final byte[] payload = serialize(original);
        for (final int cut : new int[]{0, 1, 7, payload.length - 5,
                payload.length - 4}) {
            final DataInputStream in = new DataInputStream(
                    new ByteArrayInputStream(Arrays.copyOf(payload, cut)));
            final EWAHCompressedBitmap decoded = new EWAHCompressedBitmap();
            try {
                decoded.deserialize(in);
                fail("expected EOFException for cut=" + cut + "/"
                        + payload.length);
            } catch (final EOFException expected) {
                // required contract of DataInput.readInt/readLong
            }
        }
    }

    /**
     * Two objects serialized back to back must deserialize independently;
     * after consuming exactly one object the stream is positioned at the
     * start of the next one, and a truncated first object must fail
     * without claiming success.
     */
    @Test
    public void twoObjectsDeserializeConsecutively() throws IOException {
        final EWAHCompressedBitmap first = randomBitmap(SEED + 4, 700);
        final EWAHCompressedBitmap second = randomBitmap(SEED + 5, 1300);
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
        final EWAHCompressedBitmap d1 = new EWAHCompressedBitmap();
        d1.deserialize(in);
        assertEquals(first, d1);
        assertEquals("stream must be left at the second object",
                in.available(), second.serializedSizeInBytes());

        final EWAHCompressedBitmap d2 = new EWAHCompressedBitmap();
        d2.deserialize(in);
        assertEquals(second, d2);
        assertEquals(0, in.available());

        // The second frame starting right after the first one must be
        // read on a frame boundary: give it only one trailing byte and the
        // attempt to read the next object must fail with EOF while leaving
        // that single byte untouched until the failed 4-byte header read,
        // i.e. it must not silently swallow/skip the following frame.
        final byte[] oneByteAfter = Arrays.copyOf(data,
                first.serializedSizeInBytes() + 1);
        final DataInputStream oin = new DataInputStream(
                new ByteArrayInputStream(oneByteAfter));
        final EWAHCompressedBitmap d3 = new EWAHCompressedBitmap();
        d3.deserialize(oin);
        assertEquals(first, d3);
        try {
            new EWAHCompressedBitmap().deserialize(oin);
            fail("expected EOFException when the next frame header is cut");
        } catch (final EOFException expected) {
            // DataInput never fabricates bytes: a truncated frame cannot
            // be mistaken for a valid second object.
        }

        // A stream that ends exactly at the first frame boundary must fail
        // to read the next object rather than returning a stale bitmap.
        final DataInputStream onlyFirst = new DataInputStream(
                new ByteArrayInputStream(
                        Arrays.copyOf(data, first.serializedSizeInBytes())));
        final EWAHCompressedBitmap d4 = new EWAHCompressedBitmap();
        d4.deserialize(onlyFirst);
        assertEquals(first, d4);
        try {
            new EWAHCompressedBitmap().deserialize(onlyFirst);
            fail("expected EOFException at end of a single-object stream");
        } catch (final EOFException expected) {
            // expected
        }
        assertEquals(0, onlyFirst.available());
    }

    /** Memory-mapped file view equals the source bitmap. */
    @Test
    public void memoryMappedFileViewRoundTrips() throws IOException {
        final int[] sizes = {65, 128, 129, 3000};
        int index = 0;
        for (final int size : sizes) {
            final EWAHCompressedBitmap original =
                    randomBitmap(SEED + 10 + index, size);
            final File tmp = File.createTempFile("javaewah-prop64", ".bin");
            tmp.deleteOnExit();
            final RandomAccessFile raf = new RandomAccessFile(tmp, "rw");
            try {
                original.serialize(new DataOutputStream(
                        new java.io.FileOutputStream(tmp)));
            } finally {
                raf.close();
            }
            final RandomAccessFile read = new RandomAccessFile(tmp, "r");
            try {
                final long length = tmp.length();
                final ByteBuffer mapped = read.getChannel().map(
                        FileChannel.MapMode.READ_ONLY, 0, length);
                final EWAHCompressedBitmap view =
                        new EWAHCompressedBitmap(mapped);
                assertEquals("seed=" + SEED + " mmap case=" + index,
                        original, view);
                assertEquals(size, view.sizeInBits());

                // aggregations on the mapped view behave like the source
                final EWAHCompressedBitmap self = view.and(view);
                assertEquals(view, self);
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

    /**
     * A memory-mapped view that is truncated (file cut short) must fail
     * the constructor instead of producing a corrupt bitmap silently.
     */
    @Test
    public void truncatedMemoryMappedViewFailsLoudly() throws IOException {
        final EWAHCompressedBitmap original = randomBitmap(SEED + 20, 4000);
        final byte[] payload = serialize(original);
        final File tmp = File.createTempFile("javaewah-prop64-trunc", ".bin");
        tmp.deleteOnExit();
        final java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
        fos.write(payload, 0, payload.length - 9);
        fos.close();
        final RandomAccessFile read = new RandomAccessFile(tmp, "r");
        try {
            final ByteBuffer mapped = read.getChannel().map(
                    FileChannel.MapMode.READ_ONLY, 0, tmp.length());
            try {
                new EWAHCompressedBitmap(mapped);
                fail("expected failure for truncated memory-mapped view");
            } catch (final RuntimeException expected) {
                // index failure rather than silent corruption
            }
        } finally {
            read.close();
            tmp.delete();
        }
    }

    /** Round trip must preserve raw serialized bytes (deterministic). */
    @Test
    public void serializationIsByteStable() throws IOException {
        final EWAHCompressedBitmap b = randomBitmap(SEED + 30, 1777);
        final byte[] first = serialize(b);
        final EWAHCompressedBitmap d = new EWAHCompressedBitmap();
        d.deserialize(new DataInputStream(new ByteArrayInputStream(first)));
        final byte[] second = serialize(d);
        assertArrayEquals(first, second);
    }
}
