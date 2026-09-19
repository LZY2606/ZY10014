package com.googlecode.javaewah;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

/**
 * Serialization/deserialization property tests for the 64-bit bitmap,
 * including non-zero ByteBuffer positions, truncated input, back-to-back
 * records and memory-mapped views.
 */
@SuppressWarnings("javadoc")
public class SerializationEWAH64Test {

    static final long SEED = 0x5EAD_0000_6464_6464L;

    static EWAHCompressedBitmap[] sampleBitmaps() {
        Random r = new Random(SEED);
        ShapeLike[] shapes = ShapeLike.values();
        EWAHCompressedBitmap[] out = new EWAHCompressedBitmap[shapes.length * 3];
        int k = 0;
        for (ShapeLike shape : shapes) {
            for (int rep = 0; rep < 3; rep++) {
                int n = 1 + r.nextInt(2500);
                boolean[] bits = make(r, shape, n);
                out[k++] = PropertyEWAH64Test.fromBooleans(bits);
            }
        }
        return out;
    }

    enum ShapeLike { SPARSE, DENSE, RUNS }

    static boolean[] make(Random r, ShapeLike shape, int nbits) {
        boolean[] ref = new boolean[nbits];
        if (shape == ShapeLike.SPARSE) {
            for (int i = 0; i < nbits; i++) {
                ref[i] = r.nextInt(48) == 0;
            }
        } else if (shape == ShapeLike.DENSE) {
            for (int i = 0; i < nbits; i++) {
                ref[i] = r.nextInt(100) < 75;
            }
        } else {
            int i = 0;
            while (i < nbits) {
                int run = 1 + r.nextInt(4 * 64);
                boolean v = r.nextBoolean();
                for (int k = 0; k < run && i < nbits; k++) {
                    ref[i++] = v;
                }
            }
        }
        return ref;
    }

    static byte[] serialize(EWAHCompressedBitmap bmp) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.serialize(new DataOutputStream(bos));
        Assert.assertEquals(bmp.serializedSizeInBytes(), bos.size());
        return bos.toByteArray();
    }

    static EWAHCompressedBitmap deserialize(byte[] data) throws IOException {
        EWAHCompressedBitmap bmp = new EWAHCompressedBitmap();
        bmp.deserialize(new DataInputStream(new ByteArrayInputStream(data)));
        return bmp;
    }

    @Test
    public void dataOutputRoundTripPreservesContent() throws IOException {
        for (EWAHCompressedBitmap bmp : sampleBitmaps()) {
            byte[] data = serialize(bmp);
            EWAHCompressedBitmap back = deserialize(data);
            Assert.assertEquals("DataOutput round trip", bmp, back);
            Assert.assertEquals("DataOutput round trip size",
                    bmp.sizeInBits(), back.sizeInBits());
            Assert.assertEquals("DataOutput round trip bytes",
                    bmp.serializedSizeInBytes(), back.serializedSizeInBytes());
        }
    }

    @Test
    public void byteBufferViewWorksAtNonZeroPosition() throws IOException {
        EWAHCompressedBitmap bmp = EWAHCompressedBitmap.bitmapOf(0, 2, 55, 64,
                1 << 20, (1 << 20) + 63);
        byte[] data = serialize(bmp);
        for (int padding : new int[] {8, 16, 24}) {
            byte[] padded = new byte[padding + data.length];
            new Random(SEED).nextBytes(padded);
            System.arraycopy(data, 0, padded, padding, data.length);
            ByteBuffer bb = ByteBuffer.wrap(padded);
            bb.position(padding);
            int positionBefore = bb.position();
            EWAHCompressedBitmap view = new EWAHCompressedBitmap(bb);
            Assert.assertEquals("view must not advance caller buffer",
                    positionBefore, bb.position());
            Assert.assertEquals("non-zero position view", bmp, view);
            Assert.assertEquals("view toList", bmp.toList(), view.toList());
        }
    }

    @Test
    public void truncatedByteBufferIsRejectedAndLeavesPositionIntact()
            throws IOException {
        byte[] data = serialize(EWAHCompressedBitmap.bitmapOf(1, 63, 64,
                2000));
        for (int cut = 0; cut < data.length; cut++) {
            ByteBuffer bb = ByteBuffer.wrap(data, 0, cut);
            int positionBefore = bb.position();
            try {
                new EWAHCompressedBitmap(bb);
                Assert.fail("Expected failure for truncated buffer length="
                        + cut + " (full=" + data.length + ")");
            } catch (RuntimeException expected) {
                // IndexOutOfBoundsException / BufferUnderflowException
            }
            Assert.assertEquals("caller buffer position must be unchanged at "
                    + "cut=" + cut, positionBefore, bb.position());
        }
    }

    @Test
    public void twoObjectsDeserializeBackToBackFromOneStream()
            throws IOException {
        EWAHCompressedBitmap[] bitmaps = sampleBitmaps();
        EWAHCompressedBitmap first = bitmaps[0];
        EWAHCompressedBitmap second = bitmaps[bitmaps.length - 1];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutput out = new DataOutputStream(bos);
        first.serialize(out);
        int firstSize = first.serializedSizeInBytes();
        second.serialize(out);
        byte[] all = bos.toByteArray();
        Assert.assertEquals(firstSize + second.serializedSizeInBytes(),
                all.length);

        DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(all));
        EWAHCompressedBitmap back1 = new EWAHCompressedBitmap();
        back1.deserialize(in);
        EWAHCompressedBitmap back2 = new EWAHCompressedBitmap();
        back2.deserialize(in);
        Assert.assertEquals(first, back1);
        Assert.assertEquals(second, back2);
        Assert.assertEquals(0, in.available());
    }

    @Test
    public void truncatedDataInputIsRejectedWithoutConsumingNextRecord()
            throws IOException {
        EWAHCompressedBitmap bmp = EWAHCompressedBitmap.bitmapOf(5, 65, 500);
        byte[] good = serialize(bmp);
        // Corrupt the declared word count: more words than bytes remain,
        // but small enough that no huge allocation is attempted.
        byte[] corrupted = good.clone();
        // Record layout: 8 header bytes + 8 bytes per word + 4 trailing bytes.
        int actualWords = (good.length - 12) / 8;
        int declaredWords = actualWords + 2;
        ByteBuffer.wrap(corrupted, 4, 4).putInt(declaredWords);
        // Only a short, deliberately insufficient suffix follows the
        // corrupt record: the parser must fail rather than silently
        // consuming that suffix to satisfy the inflated word count.
        byte[] suffix = new byte[7];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(corrupted);
        bos.write(suffix);
        DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(bos.toByteArray()));
        EWAHCompressedBitmap victim = new EWAHCompressedBitmap();
        try {
            victim.deserialize(in);
            Assert.fail("corrupt DataInput must fail");
        } catch (IOException expected) {
            // EOFException: stream ran out, this is the documented failure.
        }
        // The valid serialized payload stays intact on its own.
        DataInputStream verify = new DataInputStream(
                new ByteArrayInputStream(good));
        EWAHCompressedBitmap back = new EWAHCompressedBitmap();
        back.deserialize(verify);
        Assert.assertEquals(bmp, back);
    }

    @Test
    public void memoryMappedFileViewRoundTrips() throws IOException {
        EWAHCompressedBitmap[] bitmaps = sampleBitmaps();
        for (EWAHCompressedBitmap bmp : new EWAHCompressedBitmap[] {
                bitmaps[1], bitmaps[4], bitmaps[7]}) {
            File tmpfile = File.createTempFile("javaewah-prop64", ".bin");
            tmpfile.deleteOnExit();
            FileOutputStreamLike out = new FileOutputStreamLike(tmpfile);
            bmp.serialize(new DataOutputStream(out));
            long length = out.channel.position();
            out.close();
            RandomAccessFile raf = new RandomAccessFile(tmpfile, "r");
            ByteBuffer mapped = raf.getChannel().map(
                    FileChannel.MapMode.READ_ONLY, 0, length);
            EWAHCompressedBitmap view = new EWAHCompressedBitmap(mapped);
            raf.close();
            Assert.assertEquals("mapped view", bmp, view);
            Assert.assertEquals("mapped view list", bmp.toList(),
                    view.toList());
            Assert.assertEquals("mapped view size", bmp.sizeInBits(),
                    view.sizeInBits());
        }
    }

    static class FileOutputStreamLike extends java.io.FileOutputStream {
        final java.nio.channels.FileChannel channel;

        FileOutputStreamLike(File f) throws IOException {
            super(f);
            this.channel = getChannel();
        }
    }
}
