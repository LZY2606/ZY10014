package com.googlecode.javaewah32;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

import com.googlecode.javaewah.ChunkIterator;
import com.googlecode.javaewah.IntIterator;

/**
 * Seeded property tests for the 32-bit word-aligned hybrid bitmap.
 * Mirrors {@link com.googlecode.javaewah.PropertyEWAH64Test} with 32-bit
 * words; every failure message carries the fixed seed for reproduction.
 */
@SuppressWarnings("javadoc")
public class PropertyEWAH32Test {

    static final long SEED = 0x5EED_3232_3232_3232L;
    static final int W = EWAHCompressedBitmap32.WORD_IN_BITS;

    enum Shape { SPARSE, DENSE, LONG_ZERO_RUNS, LONG_ONE_RUNS }

    static boolean[] randomBits(Random r, Shape shape, int nbits) {
        boolean[] ref = new boolean[nbits];
        switch (shape) {
            case SPARSE:
                for (int i = 0; i < nbits; i++) {
                    ref[i] = r.nextInt(64) == 0;
                }
                break;
            case DENSE:
                for (int i = 0; i < nbits; i++) {
                    ref[i] = r.nextInt(100) < 80;
                }
                break;
            case LONG_ZERO_RUNS: {
                int i = 0;
                while (i < nbits) {
                    int run = 1 + r.nextInt(8 * W);
                    boolean v = r.nextInt(8) == 0;
                    for (int k = 0; k < run && i < nbits; k++) {
                        ref[i++] = v;
                    }
                }
                break;
            }
            case LONG_ONE_RUNS: {
                int i = 0;
                while (i < nbits) {
                    int run = 1 + r.nextInt(8 * W);
                    boolean v = r.nextInt(8) != 0;
                    for (int k = 0; k < run && i < nbits; k++) {
                        ref[i++] = v;
                    }
                }
                break;
            }
        }
        return ref;
    }

    static EWAHCompressedBitmap32 fromBooleans(boolean[] ref) {
        EWAHCompressedBitmap32 bmp = new EWAHCompressedBitmap32();
        for (int i = 0; i < ref.length; i++) {
            if (ref[i]) {
                bmp.set(i);
            }
        }
        if (ref.length > 0 && bmp.sizeInBits() < ref.length) {
            bmp.setSizeInBits(ref.length, false);
        }
        return bmp;
    }

    static List<Integer> positions(boolean[] ref) {
        List<Integer> list = new ArrayList<Integer>();
        for (int i = 0; i < ref.length; i++) {
            if (ref[i]) {
                list.add(i);
            }
        }
        return list;
    }

    static int cardinality(boolean[] ref) {
        int c = 0;
        for (boolean b : ref) {
            if (b) {
                c++;
            }
        }
        return c;
    }

    static boolean[] op(boolean[] a, boolean[] b, int op) {
        int n = Math.max(a.length, b.length);
        boolean[] out = new boolean[n];
        for (int i = 0; i < n; i++) {
            boolean x = i < a.length && a[i];
            boolean y = i < b.length && b[i];
            switch (op) {
                case 0: out[i] = x & y; break;
                case 1: out[i] = x | y; break;
                case 2: out[i] = x ^ y; break;
                default: out[i] = x & !y; break;
            }
        }
        return out;
    }

    static EWAHCompressedBitmap32 applyOp(EWAHCompressedBitmap32 a,
                                          EWAHCompressedBitmap32 b, int op) {
        switch (op) {
            case 0: return a.and(b);
            case 1: return a.or(b);
            case 2: return a.xor(b);
            default: return a.andNot(b);
        }
    }

    static List<Integer> drain(IntIterator it) {
        List<Integer> list = new ArrayList<Integer>();
        while (it.hasNext()) {
            list.add(it.next());
        }
        return list;
    }

    static void assertChunkExport(String ctx, boolean[] ref,
                                  EWAHCompressedBitmap32 bmp, long seed) {
        ChunkIterator ci = bmp.chunkIterator();
        int pos = 0;
        while (pos < ref.length) {
            Assert.assertTrue(ctx + " chunk hasNext at " + pos
                    + " seed=" + seed, ci.hasNext());
            boolean bit = ci.nextBit();
            int len = ci.nextLength();
            Assert.assertTrue(ctx + " positive chunk length seed=" + seed,
                    len > 0);
            Assert.assertTrue(ctx + " chunk overflow seed=" + seed,
                    pos + len <= ref.length);
            for (int k = 0; k < len; k++) {
                Assert.assertEquals(ctx + " chunk bit at " + (pos + k)
                        + " seed=" + seed, ref[pos + k], bit);
            }
            ci.move(len);
            pos += len;
        }
        Assert.assertFalse(ctx + " chunk exhausted seed=" + seed, ci.hasNext());
    }

    static void assertMatches(String ctx, boolean[] ref,
                              EWAHCompressedBitmap32 bmp, long seed) {
        Assert.assertEquals(ctx + " sizeInBits seed=" + seed, ref.length,
                bmp.sizeInBits());
        Assert.assertEquals(ctx + " cardinality seed=" + seed,
                cardinality(ref), bmp.cardinality());
        Assert.assertEquals(ctx + " toList seed=" + seed, positions(ref),
                bmp.toList());
        Assert.assertEquals(ctx + " forward iterator seed=" + seed,
                positions(ref), drain(bmp.intIterator()));
        List<Integer> backward = drain(bmp.reverseIntIterator());
        List<Integer> expectedBack = positions(ref);
        Collections.reverse(expectedBack);
        Assert.assertEquals(ctx + " reverse iterator seed=" + seed,
                expectedBack, backward);
        for (int i = 0; i < ref.length; i++) {
            Assert.assertEquals(ctx + " get(" + i + ") seed=" + seed,
                    ref[i], bmp.get(i));
        }
        Assert.assertFalse(ctx + " get(sizeInBits) must be false seed=" + seed,
                bmp.get(ref.length));
        assertChunkExport(ctx, ref, bmp, seed);
    }

    static boolean[] allTrue(int n) {
        boolean[] v = new boolean[n];
        for (int i = 0; i < n; i++) {
            v[i] = true;
        }
        return v;
    }

    // ------------------------------------------------------------------
    // Property tests
    // ------------------------------------------------------------------

    @Test
    public void sparseBitmapsMatchBooleanModelAndOrXorAndNot() {
        Random r = new Random(SEED);
        for (int iter = 0; iter < 60; iter++) {
            int na = 1 + r.nextInt(700);
            int nb = 1 + r.nextInt(700);
            boolean[] a = randomBits(r, Shape.SPARSE, na);
            boolean[] b = randomBits(r, Shape.SPARSE, nb);
            EWAHCompressedBitmap32 ba = fromBooleans(a);
            EWAHCompressedBitmap32 bb = fromBooleans(b);
            for (int op = 0; op < 4; op++) {
                assertMatches("sparse op=" + op, op(a, b, op),
                        applyOp(ba, bb, op), SEED);
            }
            assertMatches("sparse input a", a, ba, SEED);
            assertMatches("sparse input b", b, bb, SEED);
        }
    }

    @Test
    public void denseBitmapsMatchBooleanModelAndOrXorAndNot() {
        Random r = new Random(SEED ^ 0x9E3779B97F4A7C15L);
        for (int iter = 0; iter < 60; iter++) {
            int na = 1 + r.nextInt(700);
            int nb = 1 + r.nextInt(700);
            boolean[] a = randomBits(r, Shape.DENSE, na);
            boolean[] b = randomBits(r, Shape.DENSE, nb);
            EWAHCompressedBitmap32 ba = fromBooleans(a);
            EWAHCompressedBitmap32 bb = fromBooleans(b);
            for (int op = 0; op < 4; op++) {
                assertMatches("dense op=" + op, op(a, b, op),
                        applyOp(ba, bb, op), SEED);
            }
            assertMatches("dense input a", a, ba, SEED);
            assertMatches("dense input b", b, bb, SEED);
        }
    }

    @Test
    public void longZeroRunsMatchBooleanModel() {
        Random r = new Random(SEED ^ 0xD1B54A32D192ED03L);
        for (int iter = 0; iter < 40; iter++) {
            int na = 1 + r.nextInt(3000);
            int nb = 1 + r.nextInt(3000);
            boolean[] a = randomBits(r, Shape.LONG_ZERO_RUNS, na);
            boolean[] b = randomBits(r, Shape.LONG_ZERO_RUNS, nb);
            EWAHCompressedBitmap32 ba = fromBooleans(a);
            EWAHCompressedBitmap32 bb = fromBooleans(b);
            for (int op = 0; op < 4; op++) {
                assertMatches("zeroRuns op=" + op, op(a, b, op),
                        applyOp(ba, bb, op), SEED);
            }
        }
    }

    @Test
    public void longOneRunsMatchBooleanModel() {
        Random r = new Random(SEED ^ 0xA0761D6478BD642FL);
        for (int iter = 0; iter < 40; iter++) {
            int na = 1 + r.nextInt(3000);
            int nb = 1 + r.nextInt(3000);
            boolean[] a = randomBits(r, Shape.LONG_ONE_RUNS, na);
            boolean[] b = randomBits(r, Shape.LONG_ONE_RUNS, nb);
            EWAHCompressedBitmap32 ba = fromBooleans(a);
            EWAHCompressedBitmap32 bb = fromBooleans(b);
            for (int op = 0; op < 4; op++) {
                assertMatches("oneRuns op=" + op, op(a, b, op),
                        applyOp(ba, bb, op), SEED);
            }
        }
    }

    @Test
    public void operationsAreExactAtWordBoundarySizes() {
        int[] offsets = {0, 1, W - 1, W, W + 1};
        long seed = 0xBADC0FFEE0DDF00DL;
        for (int ka = 0; ka < 14; ka++) {
            for (int da = 0; da < offsets.length; da++) {
                for (int db = 0; db < offsets.length; db++) {
                    int na = ka * W + offsets[da];
                    int nb = 9 * W + offsets[db];
                    if (na == 0 || nb == 0) {
                        continue;
                    }
                    Random r = new Random(seed + na * 101L + nb);
                    boolean[] a = randomBits(r,
                            (da + db) % 2 == 0 ? Shape.SPARSE : Shape.DENSE,
                            na);
                    boolean[] b = randomBits(r, Shape.LONG_ONE_RUNS, nb);
                    EWAHCompressedBitmap32 ba = fromBooleans(a);
                    EWAHCompressedBitmap32 bb = fromBooleans(b);
                    Assert.assertEquals("sizeA seed=" + seed, na,
                            ba.sizeInBits());
                    Assert.assertEquals("sizeB seed=" + seed, nb,
                            bb.sizeInBits());
                    for (int op = 0; op < 4; op++) {
                        assertMatches("boundary op=" + op + " na=" + na
                                + " nb=" + nb, op(a, b, op),
                                applyOp(ba, bb, op), seed);
                    }
                }
            }
        }
    }

    @Test
    public void expertLiteralLastWordValidBitsAreMasked() {
        long seed = 0x1A57BEEFCAFE3232L;
        int[] sizes = {1, 7, W - 1, W, W + 1, 2 * W + 13, 6 * W + 17};
        for (int size : sizes) {
            Random r = new Random(seed + size);
            int fullWords = size / W;
            int rem = size % W;
            EWAHCompressedBitmap32 bmp = new EWAHCompressedBitmap32();
            boolean[] ref = new boolean[size];
            for (int w = 0; w < fullWords; w++) {
                int word = r.nextInt();
                if (word == ~0) {
                    word = ~1;
                }
                bmp.addWord(word);
                for (int k = 0; k < W; k++) {
                    ref[w * W + k] = ((word >>> k) & 1) == 1;
                }
            }
            if (rem > 0) {
                int dirty = r.nextInt() | (~0 << rem);
                bmp.addWord(dirty);
                int masked = dirty & ((1 << rem) - 1);
                for (int k = 0; k < rem; k++) {
                    ref[fullWords * W + k] = ((masked >>> k) & 1) == 1;
                }
            }
            bmp.setSizeInBitsWithinLastWord(size);
            assertMatches("expert literal size=" + size, ref, bmp, seed);
            for (int p : bmp.toArray()) {
                Assert.assertTrue("phantom bit " + p + " >= " + size
                        + " seed=" + seed, p < size);
            }
        }
    }

    @Test
    public void expertRunningOneLastWordIsMaskedWhenShrunk() {
        long seed = 0x600DF00DDEAD3232L;
        int[] sizes = {1, 3, W - 1, 3 * W + 7};
        for (int size : sizes) {
            EWAHCompressedBitmap32 bmp = new EWAHCompressedBitmap32();
            int words = (size + W - 1) / W;
            bmp.addStreamOfEmptyWords(true, words);
            bmp.setSizeInBitsWithinLastWord(size);
            Assert.assertEquals("running size seed=" + seed, size,
                    bmp.sizeInBits());
            Assert.assertEquals("running cardinality size=" + size, size,
                    bmp.cardinality());
            Assert.assertEquals("running positions size=" + size,
                    positions(allTrue(size)), bmp.toList());
            for (int p : bmp.toArray()) {
                Assert.assertTrue("running phantom bit " + p + " >= " + size,
                        p < size);
            }
        }
    }

    @Test
    public void aggregationAcceptsDuplicateInputAndOutputAliases() {
        long seed = 0xA11CE515C0DE3232L;
        Random r = new Random(seed);
        for (int iter = 0; iter < 30; iter++) {
            boolean[] a = randomBits(r, Shape.SPARSE, 1 + r.nextInt(900));
            boolean[] b = randomBits(r, Shape.DENSE, 1 + r.nextInt(900));
            EWAHCompressedBitmap32 ba = fromBooleans(a);
            EWAHCompressedBitmap32 bb = fromBooleans(b);

            assertMatches("static or duplicate input", op(a, a, 1),
                    EWAHCompressedBitmap32.or(ba, ba), seed);
            assertMatches("static xor duplicate input", op(a, a, 2),
                    EWAHCompressedBitmap32.xor(ba, ba), seed);
            assertMatches("static and duplicate input", op(a, a, 0),
                    EWAHCompressedBitmap32.and(ba, ba), seed);
            assertMatches("FastAggregation or duplicate input",
                    op(a, a, 1), FastAggregation32.or(ba, ba), seed);
            assertMatches("FastAggregation xor duplicate input",
                    op(a, a, 2), FastAggregation32.xor(ba, ba), seed);

            for (int op = 0; op < 4; op++) {
                assertAliasedBinary(fromBooleans(a), fromBooleans(b),
                        a, b, op, seed);
            }
            assertAliasedBinary(fromBooleans(b), fromBooleans(a),
                    b, a, 3, seed);
        }

        boolean[] c = randomBits(new Random(seed ^ 1), Shape.SPARSE, 400);
        boolean[] d = randomBits(new Random(seed ^ 2), Shape.LONG_ZERO_RUNS,
                700);
        EWAHCompressedBitmap32 bc = fromBooleans(c);
        EWAHCompressedBitmap32 bd = fromBooleans(d);
        FastAggregation32.orToContainer(bc, bc, bd);
        assertMatches("FastAggregation or container-aliased", op(c, d, 1),
                bc, seed);
    }

    private static void assertAliasedBinary(EWAHCompressedBitmap32 x,
                                            EWAHCompressedBitmap32 y,
                                            boolean[] rx, boolean[] ry, int op,
                                            long seed) {
        EWAHCompressedBitmap32 container = x;
        switch (op) {
            case 0: container.andToContainer(y, container); break;
            case 1: container.orToContainer(y, container); break;
            case 2: container.xorToContainer(y, container); break;
            default: container.andNotToContainer(y, container); break;
        }
        assertMatches("aliased binary op=" + op, op(rx, ry, op), container,
                seed);
    }
}
