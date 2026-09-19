/*
 * Property tests for the 64-bit EWAHCompressedBitmap.
 *
 * These tests use only java.util.Random with fixed seeds and
 * java.util.BitSet as an independent oracle: no third-party property
 * testing framework is required. Every randomised assertion reports the
 * seed used, so a failure can be reproduced deterministically.
 */
package com.googlecode.javaewah;

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

@SuppressWarnings("javadoc")
public final class Property64Test {

    static final long SEED = 83472611L;
    static final int WORD = EWAHCompressedBitmap.WORD_IN_BITS; // 64

    /** A bitmap together with its uncompressed java.util.BitSet oracle. */
    static final class Pair {
        final EWAHCompressedBitmap ewah;
        final BitSet bits;

        Pair(final EWAHCompressedBitmap ewah, final BitSet bits) {
            this.ewah = ewah;
            this.bits = bits;
        }
    }

    /* -------------------------------------------------------------- */
    /* Generation: sparse, dense, long zero runs and long one runs    */
    /* -------------------------------------------------------------- */

    static Pair generate(final Random r, final int size, final int shape) {
        final EWAHCompressedBitmap b = new EWAHCompressedBitmap();
        final BitSet s = new BitSet(size);
        int i = 0;
        while (i < size) {
            final int block = 1 + r.nextInt(160);
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

    /* -------------------------------------------------------------- */
    /* Assertions                                                     */
    /* -------------------------------------------------------------- */

    static String ctx(final long seed, final int index, final String op) {
        return "seed=" + seed + " case=" + index + " op=" + op;
    }

    /**
     * Full equality against an uncompressed BitSet oracle.
     *
     * @param expectedSize the exact sizeInBits the bitmap must report.
     *                     Aggregations always report the maximum input
     *                     size; a freshly constructed bitmap reports its
     *                     own tracked size.
     */
    static void assertMatches(final EWAHCompressedBitmap actual,
                              final BitSet expected, final int expectedSize,
                              final String ctx) {
        assertEquals(ctx + " sizeInBits", expectedSize,
                actual.sizeInBits());
        assertEquals(ctx + " cardinality", expected.cardinality(),
                actual.cardinality());

        // point-wise probing (uncompressed export through get())
        for (int i = 0; i < expectedSize; i++) {
            if (expected.get(i) != actual.get(i)) {
                fail(ctx + " mismatch at bit " + i + " expected "
                        + expected.get(i) + " got " + actual.get(i));
            }
        }

        // forward iteration must agree
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

        // reverse iteration must be the exact mirror of forward iteration
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

        // uncompressed exports
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

    static EWAHCompressedBitmap apply(final EWAHCompressedBitmap x,
                                      final EWAHCompressedBitmap y,
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

    /* -------------------------------------------------------------- */
    /* Tests                                                          */
    /* -------------------------------------------------------------- */

    static final int[] BOUNDARY_SIZES = {
            1, WORD - 1, WORD, WORD + 1, WORD + 2,
            2 * WORD - 1, 2 * WORD, 2 * WORD + 1,
            3 * WORD - 1, 3 * WORD, 3 * WORD + 1
    };

    /**
     * AND/OR/XOR/ANDNOT over sparse, dense, long zero and long one run
     * bitmaps, checked against java.util.BitSet.
     */
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
                    final EWAHCompressedBitmap got =
                            apply(x.ewah, y.ewah, op);
                    assertMatches(got, op(x.bits, y.bits, op), expectedSize,
                            ctx(seed, index, opName(op)));
                }
                index++;
            }
        }
    }

    static String opName(final int op) {
        return new String[]{"AND", "OR", "XOR", "ANDNOT"}[op];
    }

    /**
     * Same bitmap twice and output aliased to an input must follow the
     * boolean semantics (A op A).
     */
    @Test
    public void aggregationsWithDuplicateAndAliasedInputs()
            throws CloneNotSupportedException {
        final long seed = SEED ^ 0x5555L;
        final List<Pair> p = corpus(seed,
                new int[]{WORD + 5, 2 * WORD + 3});
        int index = 0;
        for (final Pair x : p) {
            final EWAHCompressedBitmap a = x.ewah;
            assertEquals(ctx(seed, index, "AND(A,A) cardinality"),
                    a.cardinality(), a.and(a).cardinality());
            assertEquals(ctx(seed, index, "AND(A,A) size"),
                    a.sizeInBits(), a.and(a).sizeInBits());
            assertEquals(ctx(seed, index, "OR(A,A)"), a.cardinality(),
                    a.or(a).cardinality());
            assertEquals(ctx(seed, index, "XOR(A,A)"), 0,
                    a.xor(a).cardinality());
            assertTrue(ctx(seed, index, "ANDNOT(A,A) empty"),
                    a.andNot(a).cardinality() == 0);

            // static varargs aggregation seeing the very same instance twice
            assertTrue(ctx(seed, index, "static OR(A,A)"),
                    EWAHCompressedBitmap.or(a, a).equals(a));
            assertEquals(ctx(seed, index, "static XOR(A,A)"), 0,
                    EWAHCompressedBitmap.xor(a, a).cardinality());
            assertTrue(ctx(seed, index, "fast OR(A,A,A)"),
                    FastAggregation.or(a, a, a).equals(a));
            assertEquals(ctx(seed, index, "fast XOR(A,A)"), 0,
                    FastAggregation.xor(a, a).cardinality());

            // output container aliased to the input: in-place OR is
            // documented to overwrite the container and must yield A|A=A.
            final EWAHCompressedBitmap inPlaceOr = a.clone();
            inPlaceOr.orToContainer(a, inPlaceOr);
            assertEquals(ctx(seed, index, "OR alias cardinality"),
                    a.cardinality(), inPlaceOr.cardinality());
            assertTrue(ctx(seed, index, "OR alias content"),
                    inPlaceOr.equals(a));

            // in-place ANDNOT of a bitmap with itself is well defined and
            // yields the empty bitmap even when the container aliases a.
            final EWAHCompressedBitmap inPlaceAndNot = a.clone();
            inPlaceAndNot.andNotToContainer(a, inPlaceAndNot);
            assertEquals(ctx(seed, index, "ANDNOT alias cardinality"), 0,
                    inPlaceAndNot.cardinality());
            index++;
        }
    }

    /**
     * Bit counts exactly one below, at and one above a 64-bit word
     * boundary are asserted independently with hand-built bitmaps.
     */
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
            final EWAHCompressedBitmap x = new EWAHCompressedBitmap();
            final EWAHCompressedBitmap y = new EWAHCompressedBitmap();
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

    /** Explicit single-bit-away-from-boundary masking regression. */
    @Test
    public void partialLastWordNeverExposesBitsAboveSize() {
        // 65 bits with only bit 64 set: no phantom bit 65+ may ever appear.
        for (final int size : new int[]{WORD + 1, WORD + 2, 2 * WORD + 3}) {
            final EWAHCompressedBitmap b = new EWAHCompressedBitmap();
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

    /** Forward and reverse iteration over a mixed running/literal bitmap. */
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

    /** Complement within the declared size must also be masked. */
    @Test
    public void notWithinDeclaredSizeMatchesBitSet() throws Exception {
        final long seed = SEED ^ 0x1234L;
        final Random r = new Random(seed);
        int index = 0;
        for (final int size : BOUNDARY_SIZES) {
            final Pair p = generate(r, size, 1);
            final EWAHCompressedBitmap n = p.ewah.clone();
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
