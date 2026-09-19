/*
 * Property tests for the 32-bit EWAHCompressedBitmap32.
 *
 * Mirrors Property64Test with 32-bit words. Uses only
 * java.util.Random with fixed seeds and java.util.BitSet as an
 * independent oracle: no third-party property testing framework is
 * required. Every randomised assertion reports the seed used.
 */
package com.googlecode.javaewah32;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

import org.junit.Test;

import com.googlecode.javaewah.IntIterator;

@SuppressWarnings("javadoc")
public final class Property32Test {

    static final long SEED = 40198237L;
    static final int WORD = EWAHCompressedBitmap32.WORD_IN_BITS; // 32

    static final class Pair {
        final EWAHCompressedBitmap32 ewah;
        final BitSet bits;

        Pair(final EWAHCompressedBitmap32 ewah, final BitSet bits) {
            this.ewah = ewah;
            this.bits = bits;
        }
    }

    static Pair generate(final Random r, final int size, final int shape) {
        final EWAHCompressedBitmap32 b = new EWAHCompressedBitmap32();
        final BitSet s = new BitSet(size);
        int i = 0;
        while (i < size) {
            final int block = 1 + r.nextInt(97);
            final boolean on;
            final double density;
            switch (shape) {
                case 1: // dense
                    on = true;
                    density = 0.82;
                    break;
                case 2: // long zero runs interrupted by sparse ones
                    on = (i / block) % 2 == 0;
                    density = 0.04;
                    break;
                case 3: // long one runs interrupted by sparse zeroes
                    on = (i / block) % 2 == 1;
                    density = 0.96;
                    break;
                default: // sparse uniform
                    on = true;
                    density = 0.06;
            }
            for (int k = 0; k < block && i < size; k++, i++) {
                final boolean bit = on ? r.nextDouble() < density
                        : r.nextDouble() >= density;
                if (bit) {
                    b.set(i);
                    s.set(i);
                }
            }
        }
        return new Pair(b, s);
    }

    static List<Pair> corpus(final long seed, final int[] sizes) {
        final Random r = new Random(seed);
        final List<Pair> out = new ArrayList<Pair>();
        for (int size : sizes) {
            for (int shape = 0; shape < 4; shape++) {
                out.add(generate(r, size, shape));
            }
        }
        return out;
    }

    static String ctx(final long seed, final int index, final String op) {
        return "seed=" + seed + " case=" + index + " op=" + op;
    }

    static void assertMatches(final EWAHCompressedBitmap32 actual,
                              final BitSet expected, final int expectedSize,
                              final String ctx) {
        assertEquals(ctx + " sizeInBits", expectedSize,
                actual.sizeInBits());
        assertEquals(ctx + " cardinality", expected.cardinality(),
                actual.cardinality());

        for (int i = 0; i < expectedSize; i++) {
            if (expected.get(i) != actual.get(i)) {
                fail(ctx + " mismatch at bit " + i + " expected "
                        + expected.get(i) + " got " + actual.get(i));
            }
        }

        final List<Integer> forward = new ArrayList<Integer>();
        final IntIterator fit = actual.intIterator();
        while (fit.hasNext()) {
            final int p = fit.next();
            assertTrue(ctx + " forward iterator produced bit >= size: " + p,
                    p < expectedSize);
            assertTrue(ctx + " forward iterator produced clear bit " + p,
                    expected.get(p));
            forward.add(p);
        }
        assertEquals(ctx + " forward iteration count", expected.cardinality(),
                forward.size());
        for (int p = expected.nextSetBit(0); p >= 0;
                p = expected.nextSetBit(p + 1)) {
            assertTrue(ctx + " forward iterator missed bit " + p,
                    forward.contains(p));
        }

        final List<Integer> backward = new ArrayList<Integer>();
        final IntIterator rit = actual.reverseIntIterator();
        while (rit.hasNext()) {
            final int p = rit.next();
            assertTrue(ctx + " reverse iterator produced bit >= size: " + p,
                    p < expectedSize);
            backward.add(p);
        }
        assertEquals(ctx + " reverse iteration count", forward.size(),
                backward.size());
        for (int k = 0; k < forward.size(); k++) {
            assertEquals(ctx + " reverse order mismatch at " + k,
                    forward.get(forward.size() - 1 - k), backward.get(k));
        }

        assertArrayEquals(ctx + " toArray", expectedBits(expected),
                actual.toArray());
        final List<Integer> list = actual.toList();
        assertEquals(ctx + " toList count", expected.cardinality(),
                list.size());
        for (int p : list) {
            assertTrue(ctx + " toList produced clear bit " + p,
                    expected.get(p));
        }
    }

    static int[] expectedBits(final BitSet s) {
        final int[] out = new int[s.cardinality()];
        int k = 0;
        for (int p = s.nextSetBit(0); p >= 0; p = s.nextSetBit(p + 1)) {
            out[k++] = p;
        }
        return out;
    }

    static BitSet op(final BitSet x, final BitSet y, final int which) {
        final BitSet z = (BitSet) x.clone();
        switch (which) {
            case 0:
                z.and(y);
                break;
            case 1:
                z.or(y);
                break;
            case 2:
                z.xor(y);
                break;
            default:
                z.andNot(y);
        }
        return z;
    }

    static EWAHCompressedBitmap32 apply(final EWAHCompressedBitmap32 x,
                                        final EWAHCompressedBitmap32 y,
                                        final int which) {
        switch (which) {
            case 0:
                return x.and(y);
            case 1:
                return x.or(y);
            case 2:
                return x.xor(y);
            default:
                return x.andNot(y);
        }
    }

    static final int[] BOUNDARY_SIZES = {
            1, WORD - 1, WORD, WORD + 1, WORD + 2,
            2 * WORD - 1, 2 * WORD, 2 * WORD + 1,
            3 * WORD - 1, 3 * WORD, 3 * WORD + 1
    };

    static String opName(final int op) {
        return new String[]{"AND", "OR", "XOR", "ANDNOT"}[op];
    }

    @Test
    public void randomBinaryAggregationsMatchBitSet() {
        final long seed = SEED;
        final List<Pair> p = corpus(seed, BOUNDARY_SIZES);
        int index = 0;
        for (final Pair x : p) {
            for (final Pair y : p) {
                final int expectedSize = Math.max(x.ewah.sizeInBits(),
                        y.ewah.sizeInBits());
                for (int op = 0; op < 4; op++) {
                    assertMatches(apply(x.ewah, y.ewah, op),
                            op(x.bits, y.bits, op), expectedSize,
                            ctx(seed, index, opName(op)));
                }
                index++;
            }
        }
    }

    @Test
    public void aggregationsWithDuplicateAndAliasedInputs() throws Exception {
        final long seed = SEED ^ 0x7777L;
        final List<Pair> p = corpus(seed,
                new int[]{WORD + 5, 2 * WORD + 3});
        int index = 0;
        for (final Pair x : p) {
            final EWAHCompressedBitmap32 a = x.ewah;
            assertEquals(ctx(seed, index, "AND(A,A) cardinality"),
                    a.cardinality(), a.and(a).cardinality());
            assertEquals(ctx(seed, index, "AND(A,A) size"), a.sizeInBits(),
                    a.and(a).sizeInBits());
            assertEquals(ctx(seed, index, "OR(A,A)"), a.cardinality(),
                    a.or(a).cardinality());
            assertEquals(ctx(seed, index, "XOR(A,A)"), 0,
                    a.xor(a).cardinality());
            assertEquals(ctx(seed, index, "ANDNOT(A,A)"), 0,
                    a.andNot(a).cardinality());

            assertTrue(ctx(seed, index, "static OR(A,A)"),
                    EWAHCompressedBitmap32.or(a, a).equals(a));
            assertEquals(ctx(seed, index, "static XOR(A,A)"), 0,
                    EWAHCompressedBitmap32.xor(a, a).cardinality());
            assertTrue(ctx(seed, index, "fast OR(A,A,A)"),
                    FastAggregation32.or(a, a, a).equals(a));
            assertEquals(ctx(seed, index, "fast XOR(A,A)"), 0,
                    FastAggregation32.xor(a, a).cardinality());

            final EWAHCompressedBitmap32 inPlaceOr = a.clone();
            inPlaceOr.orToContainer(a, inPlaceOr);
            assertEquals(ctx(seed, index, "OR alias cardinality"),
                    a.cardinality(), inPlaceOr.cardinality());
            assertTrue(ctx(seed, index, "OR alias content"),
                    inPlaceOr.equals(a));

            final EWAHCompressedBitmap32 inPlaceAndNot = a.clone();
            inPlaceAndNot.andNotToContainer(a, inPlaceAndNot);
            assertEquals(ctx(seed, index, "ANDNOT alias cardinality"), 0,
                    inPlaceAndNot.cardinality());
            index++;
        }
    }

    @Test
    public void wordBoundarySizesAreMaskedExactly() {
        final int[][] boundaries = {
                {WORD - 1, WORD - 1}, {WORD, WORD - 1},
                {WORD + 1, WORD}, {2 * WORD, 2 * WORD - 1},
                {2 * WORD + 1, 2 * WORD}
        };
        for (final int[] bnd : boundaries) {
            final int sizeX = bnd[0];
            final int sizeY = bnd[1];
            final EWAHCompressedBitmap32 x = new EWAHCompressedBitmap32();
            final EWAHCompressedBitmap32 y = new EWAHCompressedBitmap32();
            final BitSet bx = new BitSet(sizeX);
            final BitSet by = new BitSet(sizeY);
            x.set(0);
            y.set(0);
            bx.set(0);
            by.set(0);
            if (sizeX > WORD) {
                x.set(WORD);
                bx.set(WORD);
            }
            if (sizeY > WORD) {
                y.set(WORD);
                by.set(WORD);
            }
            x.set(sizeX - 1);
            y.set(sizeY - 1);
            bx.set(sizeX - 1);
            by.set(sizeY - 1);
            x.setSizeInBits(sizeX, false);
            y.setSizeInBits(sizeY, false);
            final int expectedSize = Math.max(x.sizeInBits(),
                    y.sizeInBits());
            for (int op = 0; op < 4; op++) {
                assertMatches(apply(x, y, op), op(bx, by, op), expectedSize,
                        "boundary " + sizeX + "/" + sizeY + " "
                                + opName(op));
            }
        }
    }

    @Test
    public void partialLastWordNeverExposesBitsAboveSize() {
        for (final int size : new int[]{WORD + 1, WORD + 2, 2 * WORD + 3}) {
            final EWAHCompressedBitmap32 b = new EWAHCompressedBitmap32();
            b.set(size - 1);
            b.setSizeInBits(size, false);
            assertEquals("size " + size, size, b.sizeInBits());
            assertEquals("cardinality " + size, 1, b.cardinality());
            final IntIterator it = b.intIterator();
            assertTrue(it.hasNext());
            assertEquals(size - 1, it.next());
            assertFalse(it.hasNext());
            final IntIterator rev = b.reverseIntIterator();
            assertTrue(rev.hasNext());
            assertEquals("reverse size " + size, size - 1, rev.next());
            assertFalse(rev.hasNext());
        }
    }

    @Test
    public void forwardAndReverseIterationMatchBitSet() {
        final long seed = SEED ^ 0x9e37L;
        final Random r = new Random(seed);
        int index = 0;
        for (final int size : BOUNDARY_SIZES) {
            for (int shape = 0; shape < 4; shape++) {
                final Pair p = generate(r, size, shape);
                assertMatches(p.ewah, p.bits, p.ewah.sizeInBits(),
                        ctx(seed, index, "iterate"));
                index++;
            }
        }
    }

    @Test
    public void notWithinDeclaredSizeMatchesBitSet() throws Exception {
        final long seed = SEED ^ 0x1234L;
        final Random r = new Random(seed);
        int index = 0;
        for (final int size : BOUNDARY_SIZES) {
            final Pair p = generate(r, size, 1);
            final EWAHCompressedBitmap32 n = p.ewah.clone();
            n.not();
            final int declared = p.ewah.sizeInBits();
            final BitSet expected = new BitSet(declared);
            expected.set(0, declared);
            expected.xor(p.bits);
            assertMatches(n, expected, declared, ctx(seed, index, "NOT"));
            index++;
        }
    }
}
