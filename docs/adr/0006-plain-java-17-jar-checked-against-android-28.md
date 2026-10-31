# 0006. Plain Java 17 JAR checked against Android 28

## Status

Accepted

## Context

The library targets Java 17 and later desktop and server runtimes and Android 9 (API 28) and
later. A multi-release JAR could add Java 21 code for newer JDKs, but Android never loads a
multi-release overlay and older Android build tools reject one. Compiling to Java 17 bytecode does
not stop code from calling JDK methods that Android 9 lacks, such as `List.of`, `Stream.toList`,
`String.isBlank` or `Optional.isEmpty`; such a call compiles and fails only on a device.

## Decision

1. Build one plain JAR with Java 17 bytecode (`--release 17`), no multi-release overlay.
2. Run the test suite on JDK 17, 21 and 25 against that JAR.
3. Check the main code with Animal Sniffer against the Android API 28 signature; any call to a
   method missing there fails the build.
4. Keep records and sealed types out of the public API, so Android builds on older tools work.
5. Add no runtime dependency.

## Consequences

- One artifact for every platform; newer JDKs run the same bytecode they were tested with.
- Main code avoids convenient JDK methods (for example `trim().isEmpty()` instead of `isBlank()`),
  and the build catches every slip.
- The JDBC adapter reads dates and times through their text form, because the `java.time` getters
  of `java.sql` types are missing on Android 9.

## Alternatives rejected

- A multi-release JAR with a Java 21 overlay: ignored on Android and rejected by older Android
  tools.
- Relying on review to spot Android-missing methods: misses are found only on a device.
