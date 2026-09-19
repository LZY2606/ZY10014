# Testing notes

## What was added

Seeded property tests (no third-party property framework; only JUnit 4 and
`java.util.Random`) for both word widths:

- `src/test/java/com/googlecode/javaewah/PropertyEWAH64Test.java` (8 tests)
- `src/test/java/com/googlecode/javaewah/SerializationEWAH64Test.java` (6 tests)
- `src/test/java/com/googlecode/javaewah32/PropertyEWAH32Test.java` (8 tests)
- `src/test/java/com/googlecode/javaewah32/SerializationEWAH32Test.java` (6 tests)

The property tests generate sparse, dense, long-zero-run and long-one-run
bitmaps from fixed seeds and compare AND / OR / XOR / ANDNOT against a naive
boolean-array oracle. They also assert uncompressed export (`toList`,
`toArray`, `get(i)`, `ChunkIterator` run export) and forward/reverse set-bit
iteration, including explicit checks that no iterator yields a position
outside `sizeInBits`. Sizes land exactly on and one bit around word
boundaries (`k*W`, `k*W +/- 1`, `W-1`, `W+1`) for both 32- and 64-bit words.

The serialization tests cover `DataOutput`/`DataInput` round trips,
`ByteBuffer` views with a non-zero position (the caller buffer position must
not move), truncated `ByteBuffer` input for every cut length (failure is
required and the caller position must stay intact), two objects deserialized
back-to-back from one stream, corrupt `DataInput` records, and read-only
memory-mapped file views.

Aggregation tests include the same instance twice in the inputs
(`EWAHCompressedBitmap.or(a, a)`, `FastAggregation.xor(a, a)`, ...) and
output/input aliasing for every binary op (`a.orToContainer(b, a)`,
`a.orToContainer(b, b)`, including `FastAggregation.orToContainer(a, a, b)`).
The `*ToContainer` implementations and both `FastAggregation(32)`
implementations were fixed at the root cause to clone aliased inputs before
`container.clear()`; previously aliasing silently produced wrong results.

Every randomized assertion includes its seed in the failure message, so a
random failure prints the seed and can be reproduced deterministically.

## Commands

```
mvn -q -DskipTests package
mvn -q -Dtest='*Property*,*Serialization*' test
```

The filtered run reports the new class and test names (28 tests) and exits 0;
the whole suite (`mvn test`) passes as well. The property tests take well
under 8 seconds.

## Mutation check (temporary, reverted)

Change point: in
`src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java`,
`setSizeInBitsWithinLastWord(int)`, the final literal-word masking

```java
this.buffer.andLastWord((~0l) >>> (WORD_IN_BITS - usedBitsInLast));
```

was temporarily changed to

```java
this.buffer.andLastWord(~0l); // MUTATION: valid-bits mask disabled
```

i.e. the significant-bits mask of the last (partial) word was disabled.

Result with the mutation applied:

```
mvn -q -Dtest='*Property*,*Serialization*' test
...
expertLiteralLastWordValidBitsAreMasked(com.googlecode.javaewah.PropertyEWAH64Test) <<< FAILURE!
java.lang.AssertionError: expert literal size=1 cardinality seed=1898195705072106174 expected:<0> but was:<63>
Tests run: 28, Failures: 1, Errors: 0
```

Failing test: `PropertyEWAH64Test.expertLiteralLastWordValidBitsAreMasked`
(the unmasked dirty bits above the declared logical size leak through
`cardinality()` / `toArray()` / the iterators).

The mutation was reverted immediately after the check; the final source tree
contains no mutation (`grep MUTATION src/main` finds nothing).
