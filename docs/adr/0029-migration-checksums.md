# 0029. Migration checksums

## Status

Accepted. Extends the table in [ADR 0007](0007-migrations-table-without-a-schema-builder.md).

## Context

A migration that was edited after it ran has not changed the database it already changed. The code
and the schema drift apart, and the next fresh database differs from the live one. Flyway and
Liquibase store a checksum per migration and refuse to run when it changes. Laravel does not.

A Java migration is code, not a file of SQL. Hashing the compiled class would look like the obvious
way to detect an edit, but the bytes differ between javac and ecj, between compiler versions and
after R8 or ProGuard shrinks an Android app, so an unchanged migration would be refused.

## Decision

1. The checksum is opt-in. `Migration.checksum()` returns an empty `Optional` by default. A
   developer returns a version label to bump, or a hash of a canonical description of the change
   through `MigrationChecksum.of(String)`.
2. `MigrationChecksum.of` is SHA-256 in lowercase hex of the text after CRLF and CR become LF and
   the text is put in Unicode form NFC. A file saved with Windows line endings or a different
   accent encoding hashes the same. It uses `MessageDigest` and `Normalizer`, which Android has;
   hex is built by hand because `HexFormat` needs Java 17 on the runtime and the library is checked
   against Android API 28.
3. The table gets a nullable `checksum varchar(64)` column, appended last. Readers select it last
   and read by position.
4. Null rules. A null on either side is never checked. A migration that declares nothing never
   blocks. A recorded null is a migration that was not protected when it ran.
5. Validation. Inside the locked run, and in `pretendRun`, every migration in the migrator's list
   that already ran and has both a recorded and a declared checksum is compared. Every difference is
   collected and the run stops with one `MigrationChecksumException` before any migration runs. The
   message names each migration, its recorded and current checksum, and the two ways out.
6. Adoption. On a locked run, a ran migration whose recorded checksum is null and whose declared
   checksum is present gets the declared one written. A migration that starts declaring a checksum
   later is protected from then on without a manual step.
7. Repair and clear. `Migrator.repairChecksums(List<Migration>)` rewrites the recorded checksum of
   every listed migration that ran and declares one. `Migrator.clearChecksums()` sets every recorded
   checksum to null. Both run under the migration lock and fire no migration events.
8. No runtime DDL. The library never alters the migrations table at runtime. If the `checksum`
   column is missing from a hand-built table, the first call that needs it fails with a message
   naming the statement to add it, `alter table <table> add column checksum varchar(64)`.
9. `MigrationRepository` gains `log(name, batch, checksum)` in place of the two-argument method,
   and the default methods `checksums()` and `setChecksum(name, checksum)`, which throw
   `UnsupportedOperationException`, so a repository written elsewhere keeps compiling.

## Consequences

- A migration is protected only when its author says so; nothing is silently refused after a
  compiler upgrade.
- Fixing an intended edit is one call, and a mistaken edit is caught before it reaches production.
- A reader that never writes, such as `status`, never touches the checksum column and never alters
  the table.
- The check needs one more read of the migrations table per run.

## Alternatives rejected

- Hash the bytecode: unstable across compilers and shrinkers.
- Require a checksum on every migration: forces busywork on migrations that never change and breaks
  every existing migration class.
- Add the column automatically on first use: DDL inside a read path, racy between processes, and
  a different failure mode on each database. A one-line manual statement is clearer for a
  hand-built table.
- Store MD5 with a version prefix, as Liquibase does: nothing to migrate between hash versions yet,
  and 64 hex characters cover SHA-256 exactly.
