# Migrations: user scenarios

## Normal flow

1. The developer writes migrations and lists them.
2. On the first run the table is created; each migration is logged with a batch number.
3. The next run reads the list and skips what ran. A rollback reads the last batch and removes its
   entries.

## Edge cases

| # | Situation | What happens | Test |
|---|---|---|---|
| E1 | A migration has no down step | Undo does nothing for it | `MigrationTest#migrationWithOnlyUpHasLaravelDefaults` |
| E2 | A migration name is empty, too long or has hidden characters | Refused with a clear message | `MigrationEntryTest#refusesInvalidNamesAndBatches` |
| E3 | The table name is bad or looks like SQL | Refused before anything runs; the message does not repeat the name | `MigrationNamesTest#refusesTableNamesThatCouldCarrySql` |
| E4 | The app uses a table prefix | The table gets the prefix | `DatabaseMigrationRepositorySqliteTest#addsTheConnectionsTablePrefix` |
| E5 | First run, table is empty | Last batch is 0, next is 1, nothing to roll back | `DatabaseMigrationRepositorySqliteTest#emptyTableStartsAtBatchOne` |
| E6 | The same migration is logged twice | Duplicate-key error | `DatabaseMigrationRepositorySqliteTest#loggingTwiceIsAUniqueViolationAndDeletingAnAbsentNameIsANoOp` |
| E7 | An entry to remove is already gone | Nothing changes, no error | same test as E6 |
| E8 | Entries were logged out of order | Reads still come back in Laravel's order | `DatabaseMigrationRepositorySqliteTest#readsComeBackInLaravelsOrder` |
| E9 | The caller tries to change a returned list | Refused | `DatabaseMigrationRepositorySqliteTest#returnedListsCannotBeChanged` |
| E10 | The table is created, then removed | Exists reports yes, then no | `DatabaseMigrationRepositorySqliteTest#createsChecksAndDropsTheTable` |
| E11 | Reading before the table exists | A `QueryException`; the migrator checks existence first | `DatabaseMigrationRepositorySqliteTest#readingBeforeTheTableExistsIsAQueryException` |
| E12 | A database reports column names in upper case or numbers as text | Read correctly | `UpperCaseDriverTest#readsColumnsByPositionAndBatchesGivenAsText` |
| E13 | A database returns a value that cannot be read | A clear error instead of a wrong result | `UpperCaseDriverTest#unreadableValuesFailWithAClearError` |
| E14 | The database is not SQLite, PostgreSQL or MySQL | The table check is refused | `UpperCaseDriverTest#tableCheckIsRefusedOnDatabasesWithoutAKnownQuery` |
| E15 | Work runs in pretend mode | Nothing reaches the database; reads are empty | `DatabaseMigrationRepositorySqliteTest#pretendModeReachesNoDatabase` |
| E16 | Two processes migrate at the same time | Not handled yet | out of scope |
