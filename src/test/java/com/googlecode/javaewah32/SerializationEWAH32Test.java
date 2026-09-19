package com.googlecode.javaewah32;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

/**
 * Serialization/deserialization property tests for the 32-bit bitmap.
 */
@SuppressWarnings("javadoc")
public class SerializationEWAH32Test {

    static final long SEED = 0x5EAD_3200_3232_3232L;

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
                int run = 1 + r.nextInt(8 * 32);
                boolean v = r.nextBoolean();
                for (int k = 0; k < run && i < nbits; k++) {
                    ref[i++] = v;
                }
            }
        }
        return ref;
    }

    static EWAHCompressedBitmap32[] sampleBitmaps() {
        Random r = new Random(SEED);
        ShapeLike[] shapes = ShapeLike.values();
        EWAHCompressedBitmap32[] out =
                new EWAHCompressedBitmap32[shapes.length * 3];
        int k = 0;
        for (ShapeLike shape : shapes) {
            for (int rep = 0; rep < 3; rep++) {
                int n = 1 + r.nextInt(2500);
                out[k++] = PropertyEWAH32Test.fromBooleans(make(r, shape, n));
            }
        }
        return out;
    }

    static byte[] serialize(EWAHCompressedBitmap32 bmp) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.serialize(new DataOutputStream(bos));
        Assert.assertEquals(bmp.serializedSizeInBytes(), bos.size());
        return bos.toByteArray();
    }

    @Test
    public void dataOutputRoundTripPreservesContent() throws IOException {
        for (EWAHCompressedBitmap32 bmp : sampleBitmaps()) {
            byte[] data = serialize(bmp);
            EWAHCompressedBitmap32 back = new EWAHCompressedBitmap32();
            back.deserialize(new DataInputStream(
                    new ByteArrayInputStream(data)));
            Assert.assertEquals("DataOutput round trip", bmp, back);
            Assert.assertEquals("DataOutput round trip size",
                    bmp.sizeInBits(), back.sizeInBits());
            Assert.assertEquals("DataOutput round trip bytes",
                    bmp.serializedSizeInBytes(),
                    back.serializedSizeInBytes());
        }
    }

    @Test
    public void byteBufferViewWorksAtNonZeroPosition() throws IOException {
        EWAHCompressedBitmap32 bmp = EWAHCompressedBitmap32.bitmapOf(0, 2,
                31, 32, 1 << 19, (1 << 19) + 31);
        byte[] data = serialize(bmp);
        for (int padding : new int[] {4, 8, 12}) {
            byte[] padded = new byte[padding + data.length];
            new Random(SEED).nextBytes(padded);
            System.arraycopy(data, 0, padded, padding, data.length);
            ByteBuffer bb = ByteBuffer.wrap(padded);
            bb.position(padding);
            int positionBefore = bb.position();
            EWAHCompressedBitmap32 view = new EWAHCompressedBitmap32(bb);
            Assert.assertEquals("view must not advance caller buffer",
                    positionBefore, bb.position());
            Assert.assertEquals("non-zero position view", bmp, view);
            Assert.assertEquals("view toList", bmp.toList(), view.toList());
        }
    }

    @Test
    public void truncatedByteBufferIsRejectedAndLeavesPositionIntact()
            throws IOException {
        byte[] data = serialize(EWAHCompressedBitmap32.bitmapOf(1, 31, 32,
                2000));
        for (int cut = 0; cut < data.length; cut++) {
            ByteBuffer bb = ByteBuffer.wrap(data, 0, cut);
            int positionBefore = bb.position();
            try {
                new EWAHCompressedBitmap32(bb);
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
        EWAHCompressedBitmap32[] bitmaps = sampleBitmaps();
        EWAHCompressedBitmap32 first = bitmaps[0];
        EWAHCompressedBitmap32 second = bitmaps[bitmaps.length - 1];
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
        EWAHCompressedBitmap32 back1 = new EWAHCompressedBitmap32();
        back1.deserialize(in);
        EWAHCompressedBitmap32 back2 = new EWAHCompressedBitmap32();
        back2.deserialize(in);
        Assert.assertEquals(first, back1);
        Assert.assertEquals(second, back2);
        Assert.assertEquals(0, in.available());
    }

    @Test
    public void truncatedDataInputIsRejectedAndValidRecordStaysUsable()
            throws IOException {
        EWAHCompressedBitmap32 bmp = EWAHCompressedBitmap32.bitmapOf(5, 33,
                500);
        byte[] good = serialize(bmp);
        byte[] corrupted = good.clone();
        // Record layout: 8 header bytes + 4 bytes per word + 4 trailing.
        int actualWords = (good.length - 12) / 4;
        int declaredWords = actualWords + 2;
        ByteBuffer.wrap(corrupted, 4, 4).putInt(declaredWords);
        byte[] suffix = new byte[5];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(corrupted);
        bos.write(suffix);
        DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(bos.toByteArray()));
        EWAHCompressedBitmap32 victim = new EWAHCompressedBitmap32();
        try {
            victim.deserialize(in);
            Assert.fail("corrupt DataInput must fail");
        } catch (IOException expected) {
            // EOFException is the documented failure mode.
        }
        DataInputStream verify = new DataInputStream(
                new ByteArrayInputStream(good));
        EWAHCompressedBitmap32 back = new EWAHCompressedBitmap32();
        back.deserialize(verify);
        Assert.assertEquals(bmp, back);
    }

    @Test
    public void memoryMappedFileViewRoundTrips() throws IOException {
        EWAHCompressedBitmap32[] bitmaps = sampleBitmaps();
        for (EWAHCompressedBitmap32 bmp : new EWAHCompressedBitmap32[] {
                bitmaps[1], bitmaps[4], bitmaps[7]}) {
            File tmpfile = File.createTempFile("javaewah-prop32", ".bin");
            tmpfile.deleteOnExit();
            FileOutputStream fos = new FileOutputStream(tmpfile);
            bmp.serialize(new DataOutputStream(fos));
            long length = fos.getChannel().position();
            fos.close();
            RandomAccessFile raf = new RandomAccessFile(tmpfile, "r");
            ByteBuffer mapped = raf.getChannel().map(
                    FileChannel.MapMode.READ_ONLY, 0, length);
            EWAHCompressedBitmap32 view = new EWAHCompressedBitmap32(mapped);
            raf.close();
            Assert.assertEquals("mapped view", bmp, view);
            Assert.assertEquals("mapped view list", bmp.toList(),
                    view.toList());
            Assert.assertEquals("mapped view size", bmp.sizeInBits(),
                    view.sizeInBits());
        }
    }
}
