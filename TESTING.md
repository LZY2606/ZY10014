# Property & Serialization Tests (JavaEWAH, 64-bit and 32-bit)

These tests are dependency-free property tests. They use
`java.util.Random` with **fixed seeds** (printed in every failure
message as `seed=...`) and `java.util.BitSet` as an independent,
naive oracle. No third-party property-testing framework is required.

## How to run

Preparation (not counted in the demo):

```
mvn -q -DskipTests package
```

Acceptance (must be run from the repository root, exit code 0):

```
mvn -q -Dtest='*Property*,*Serialization*' test
```

Runtime of this selection: about 2 seconds (whole selection, measured
with the repository's 3-fork surefire configuration); well under the
8 second budget. The new class/test names are printed while running:

- `com.googlecode.javaewah.Property64Test` (6 tests)
- `com.googlecode.javaewah32.Property32Test` (6 tests)
- `com.googlecode.javaewah.SerializationProperty64Test` (8 tests)
- `com.googlecode.javaewah32.SerializationProperty32Test` (8 tests)

Total: **28** named tests.

## What is covered

Both the 64-bit (`EWAHCompressedBitmap`, 64-bit words) and the 32-bit
(`EWAHCompressedBitmap32`, 32-bit words) implementations are checked.

Property tests (`Property64Test` / `Property32Test`):

- `randomBinaryAggregationsMatchBitSet` - fixed-seed random sparse,
  dense, long-zero-run and long-one-run bitmaps compared against
  `java.util.BitSet` for **AND, OR, XOR, ANDNOT**.
- `aggregationsWithDuplicateAndAliasedInputs` - the very same instance
  passed twice (`AND/OR/XOR/ANDNOT(A,A)`, static `or`/`xor` varargs,
  `FastAggregation` with duplicates) and output containers that alias
  the input for the well-defined in-place cases (`orToContainer`,
  `andNotToContainer`).
- `wordBoundarySizesAreMaskedExactly` - bit counts exactly one below,
  at, and one above a word boundary and a two-word boundary, asserted
  independently with hand-built bitmaps.
- `partialLastWordNeverExposesBitsAboveSize` - a partial last word
  never leaks set positions `>= sizeInBits` via forward or reverse
  iteration.
- `forwardAndReverseIterationMatchBitSet` - forward `intIterator()`
  and reverse `reverseIntIterator()` are exact mirrors and match the
  oracle.
- `notWithinDeclaredSizeMatchesBitSet` - complement within the
  declared size is masked correctly (the last word cannot expose
  high bits past `sizeInBits`).

Uncompressed exports checked for every bitmap: point-wise `get(i)`,
`cardinality()`, `toArray()`, and `toList()` against the oracle.

Serialization tests (`SerializationProperty64Test` /
`SerializationProperty32Test`):

- `roundTripRandomBitmapsViaDataInput` - random bitmaps round-trip
  through `serialize`/`deserialize`; `serializedSizeInBytes()` is exact.
- `byteBufferWithNonZeroPositionIsAccepted` - the `ByteBuffer` view
  constructor reads a frame embedded at a **non-zero position** and
  never moves the caller's buffer position (the buffer stays
  recoverable). Sliced buffers are checked too.
- `truncatedByteBufferFailsWithoutMovingPosition` - truncated
  `ByteBuffer` input fails loudly (`IndexOutOfBoundsException`) and
  leaves the caller position untouched.
- `truncatedDataInputThrowsIOException` - truncated `DataInput`
  fails with `EOFException`, the contract of `DataInput`.
- `twoObjectsDeserializeConsecutively` - two objects serialized back
  to back deserialize independently on frame boundaries; a stream cut
  one byte into the next frame, and a stream ending exactly at the
  first boundary, both fail with `EOFException` rather than silently
  returning a stale/skipped object.
- `memoryMappedFileViewRoundTrips` - real `FileChannel.map`
  memory-mapped views equal the source, aggregate with themselves
  correctly, and iterate forward/reverse identically.
- `truncatedMemoryMappedViewFailsLoudly` - a truncated mapped file
  fails the view constructor instead of silently corrupting data.
- `serializationIsByteStable` - re-serializing a decoded bitmap yields
  identical bytes.

## Mutation check (performed, then reverted)

Mutation: temporarily disable the **valid-bit mask applied to the last
word during reverse iteration**. The last partial word is stored
reversed and must be shifted so its set bits align with their true bit
positions; removing that shift is exactly "breaking the last word's
valid-bit mask".

Change applied (identical one-line change in both variants):

- `src/main/java/com/googlecode/javaewah/ReverseIntIterator.java`,
  in `setRLW(...)`:
  `this.word = (this.word >>> (WORD_IN_BITS - usedBitsInLast));`
  changed to `this.word = this.word;`
- `src/main/java/com/googlecode/javaewah32/ReverseIntIterator32.java`,
  same one-line change.

Minimal deterministic symptom (both variants): a bitmap of
`WORD + 1` bits (65 / 33) with only its highest bit set iterates
forward as bit `WORD`, but the mutated reverse iterator reports a
wrong position (64-bit: bit `1` instead of `64`; 32-bit: bit `1`
instead of `32`).

With the mutation in place, the acceptance command fails
(`mvn ... test` exit code `1`); **12 of the 28** tests fail. The
failing test names are:

- `com.googlecode.javaewah.Property64Test`:
  - `forwardAndReverseIterationMatchBitSet`
  - `randomBinaryAggregationsMatchBitSet`
  - `partialLastWordNeverExposesBitsAboveSize`
  - `wordBoundarySizesAreMaskedExactly`
  - `notWithinDeclaredSizeMatchesBitSet`
- `com.googlecode.javaewah32.Property32Test`:
  - `forwardAndReverseIterationMatchBitSet`
  - `randomBinaryAggregationsMatchBitSet`
  - `partialLastWordNeverExposesBitsAboveSize`
  - `wordBoundarySizesAreMaskedExactly`
  - `notWithinDeclaredSizeMatchesBitSet`
- `com.googlecode.javaewah.SerializationProperty64Test`:
  - `memoryMappedFileViewRoundTrips`
- `com.googlecode.javaewah32.SerializationProperty32Test`:
  - `memoryMappedFileViewRoundTrips`

(The property tests fail on the forward/reverse mirror assertion;
the memory-mapped tests fail on their forward-vs-reverse iteration
check of the mapped view.)

The mutation was **reverted** after the check; the final tree contains
no mutation. `git diff -- src/main` is empty, and the acceptance
command exits `0`.
