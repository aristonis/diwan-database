# Migrator: user scenarios

## Normal flow

1. On first start the app runs its migrations; the table is created and every migration lands in
   batch 1.
2. A later release adds two migrations; they run in batch 2.
3. A bad release is rolled back: batch 2 is undone.
4. Before a deploy, the pipeline runs pretend and status.

## Edge cases

| # | Situation | What happens | Test |
|---|---|---|---|
| E1 | Nothing is pending | Nothing happens, no batch used | `MigratorSqliteTest#runsPendingMigrationsInNameOrderInOneBatchAndOnlyOnce` |
| E2 | The migrations table does not exist yet | Created first; pretend does not create it | same test as E1, `MigratorSqliteTest#pretendListsTheSqlAndChangesNothing` |
| E3 | A migration says it should not run | Skipped, not recorded, stays pending | `MigratorSqliteTest#stepGivesEachMigrationItsOwnBatchSkippedOnesIncluded` |
| E4 | The second of three migrations fails | First kept, second undone and not recorded, third not run, error names the second | `MigratorSqliteTest#aFailingMigrationIsUndoneAndStopsTheRun` |
| E5 | A migration without a transaction fails halfway | What it did stays; it is not recorded | `MigratorSqliteTest#aMigrationWithoutATransactionKeepsWhatRanBeforeItFailed` |
| E6 | Recording a migration fails after its change | The change is undone with it | `MigratorSqliteTest#aFailedRecordUndoesTheChangeItBelongsTo` |
| E7 | Recording fails after another connection committed | Error says the record failed; the change stays | `MigratorSqliteTest#aRecordThatFailsAfterAnotherConnectionCommittedIsReportedAsSuch` |
| E8 | Two migrations share a name, a name is invalid, a connection is unknown | Refused before anything runs | `MigratorBuildTest#refusesBadListsBeforeAnythingRuns` |
| E9 | Called inside a transaction the app opened | Refused | `MigratorBuildTest#refusesToRunInsideAnOpenTransaction` |
| E10 | Older code rolls back a batch holding migrations it does not have | Refused, nothing undone, the error lists the names; status shows them as missing | `MigratorSqliteTest#olderCodeCannotRollBackANewerBatch` |
| E10b | Same, with `allowMissingOnRollback()` | Known ones undone; unknown records reported as not found and kept | `MigratorSqliteTest#entriesWithoutAMigrationAreReportedAndKept` |
| E11 | Rolling back with nothing ran, or no table | Nothing happens | `MigratorSqliteTest#rollbackWithoutATableOrWithoutDownDoesNothingHarmful` |
| E12 | A migration without a down step is rolled back | Its record is removed | same test as E11 |
| E13 | Rollback by steps reaching into an older batch | Exactly that many, newest first | `MigratorSqliteTest#rollsBackTheLastBatchByStepsByBatchAndAll` |
| E14 | A down step fails | Error says so; the record stays | `MigratorSqliteTest#downFailuresAreReportedWithTheirDirection` |
| E15 | Pretend on an empty database, or a migration that throws in pretend | SQL listed, database untouched; a throw is reported like a real run | `MigratorSqliteTest#pretendListsTheSqlAndChangesNothing`, `MigratorSqliteTest#aMigrationThatThrowsInPretendIsReportedLikeARealRun` |
| E16 | A migration on a named connection | Runs there; its record goes to the default connection | `MigratorSqliteTest#aNamedConnectionRunsItsMigrationAndTheRecordStaysOnTheDefault` |
| E17 | A lock is plugged in and the run fails | The lock is released anyway | `MigratorSqliteTest#theLockIsHeldAroundEveryChangingCallAndReleasedOnFailure` |
| E18 | Two processes migrate at once without a lock | Not prevented; documented | out of scope |
