# Query builder: user scenarios

## Normal flow

1. The developer gets a factory for the connection and calls `table("orders")`.
2. They add conditions, an order and a limit, and get rows back.
3. They insert an order and get its id, update it, and delete it.
4. They join orders to customers, keep customers that have a payment (`whereExists`), combine open
   and archived orders with a union sorted by date and limited, and copy last month's rows with
   `insertUsing`.

## Edge cases

| # | Situation | What happens | Test |
|---|---|---|---|
| E1 | A column compared to `null` with `=` or no operator | Matches rows where the column is null (`is null`) | `BuilderStateTest#nullValueBecomesIsNullAndNotEqualsBecomesIsNotNull` |
| E2 | A column compared to `null` with `>` or another comparison | Refused before anything runs | `BuilderStateTest#nullWithAComparisonOperatorIsRejected` |
| E3 | A word that is not an operator passed as the operator | Taken as the value, compared with `=` | `BuilderStateTest#unknownOperatorBecomesTheValueWithEquals`, `SQLiteQueryGrammarTest#anUnknownOperatorIsTheValue` |
| E4 | `whereIn` with an empty list | Matches nothing; `whereNotIn` with one matches everything | `QueryGrammarTest#inWheresAndTheirEmptyForms` |
| E5 | A nested group that adds no condition | No condition and no empty brackets | `BuilderStateTest#emptyNestedWhereAddsNothing` |
| E6 | Counting a query that has an order and a limit | Counts every matching row; the builder still works after | `BuilderExecutionTest#countDropsColumnsOrdersLimitAndOffsetOnlyOnAClone`, `SQLiteQueryIntegrationTest#countIgnoresOrderLimitAndOffset` |
| E7 | Inserting an empty list of rows | Nothing runs; reported as success | `BuilderExecutionTest#anEmptyInsertRunsNothing` |
| E8 | Inserting many rows where one has different fields | Refused before anything is written | `QueryGrammarTest#insertRowsWithDifferentKeysAreRejected` |
| E9 | Getting the new id on an older Android SQLite without `returning` | The id comes back through `last_insert_rowid()` on the same session | `SQLiteQueryIntegrationTest#insertGetIdFallsBackToLastInsertRowidOnAnOldSQLite`, `AndroidQueryTest#insertGetIdReturnsTheNewKeyOnTheAndroidSqliteVersion` |
| E10 | A table whose key is not an integer rowid, on SQLite before 3.35 | Needs SQLite 3.35 or later (`returning`) | `SQLiteQueryIntegrationTest#insertGetIdReturnsANonIntegerKeyWithReturning` |
| E11 | Update or delete with a limit | Changes at most that many rows on both databases | `SQLiteQueryIntegrationTest#updateIncrementAndDeleteRespectALimit`, `PostgresQueryIntegrationTest#updateIncrementAndDeleteWithLimitTouchAtMostThatMany` |
| E12 | Emptying a table | Rows gone; the next id starts again from 1 | `SQLiteQueryIntegrationTest#truncateEmptiesTheTableAndResetsIds`, `PostgresQueryIntegrationTest#truncateRestartsTheIdentity` |
| E13 | `toRawSql` with a value holding a quote or a question mark | Quote doubled; question marks inside text left alone | `QueryGrammarTest#rawSqlLeavesQuestionMarksInsideStringLiteralsAlone` |
| E14 | A raw expression used as a value | Written into the SQL as given, never bound | `BuilderStateTest#rawValuesAreNeverBound` |
| E15 | A money amount or an `Instant` written and read back | Same value on every database, no rounding, no time-zone drift | `SQLiteQueryIntegrationTest#moneyAndInstantsRoundTrip`, `PostgresQueryIntegrationTest#moneyAndInstantsRoundTrip`, `AndroidQueryTest#moneyAndInstantRoundTrip` |
| E16 | A date where on an instant column | Not supported (SQLite reads microseconds as days); use `whereInstantBetween` | `SQLiteQueryIntegrationTest#whereInstantBetweenIncludesTheStartAndExcludesTheEnd` |
| E17 | A copy of a query is changed | The original is unchanged | `BuilderStateTest#cloneSharesNoMutableStateWithTheOriginal`, `SQLiteQueryIntegrationTest#aCloneIsIndependent` |
| E18 | A sort direction other than `asc` or `desc` | Refused | `BuilderStateTest#invalidSortDirectionIsRejected` |
| E19 | A negative limit or offset | Limit ignored, offset 0 | `BuilderStateTest#negativeLimitIsIgnoredAndNegativeOffsetBecomesZero` |
| E20 | A table or column name containing a quote | Quoted safely; it cannot break out of the SQL | `QueryGrammarTest#embeddedQuotesInNamesAreDoubled`, `SQLiteQueryGrammarTest#injectedNamesStayInsideQuotes` |
| E21 | A user value or operator containing SQL text | Sent as a parameter; never runs as SQL | `BuilderStateTest#operatorInjectionIsBoundNotWritten` |
| E22 | The same builder used from two threads | Not supported; use one builder per thread or `clone()` | Javadoc of `Builder` |
| E23 | A subquery with its own values sits in select, from, join, where and order at once | Values come out in the same order as the placeholders | `BindingOrderTest#bindingsFollowTheMarksInEverySlot`, `SQLiteQueryIntegrationTest#theBindingOrderScenarioRunsForReal`, `PostgresJoinsUnionsIntegrationTest#theBindingOrderScenarioRuns` |
| E24 | A subquery is changed after it was attached | The outer query's SQL and values are unchanged | `SubqueryTest#mutatingASubAfterAttachChangesNothing`, `JoinClauseTest#changingAJoinClauseAfterAttachDoesNotChangeTheQuery` |
| E25 | A nested join condition group left empty | No empty brackets | `JoinClauseTest#nestedOnGroupsUseParenthesesAndAnEmptyGroupIsSkipped` |
| E26 | A join `where` with a `null` value | `is null`, nothing bound | `JoinClauseTest#joinWhereWithNullIsAnIsNullWithoutBinding` |
| E27 | Union parts each with their own order and limit on SQLite | Valid SQL (`select * from (...)` per part); runs | `SQLiteQueryGrammarTest#unionPartsKeepTheirOwnOrderAndLimitInsideTheWrapper`, `SQLiteQueryIntegrationTest#unionPartsWithTheirOwnOrderAndLimitRun`, `AndroidQueryTest#unionPartsAndTheUnionTakeTheirOwnOrderAndLimit` |
| E28 | `count()` on a union, or on a query with a joined subquery | Counts the combined rows; the builder still works after | `UnionTest#countOverAUnionUsesTheTempTableAndLeavesTheBuilderAlone`, `SubqueryWriteTest#countOverAJoinSubKeepsTheSubqueryBindingsFirst`, `SQLiteQueryIntegrationTest#countAndExistsOnAUnionSeeTheCombinedRows`, `AndroidQueryTest#countOverAUnionCountsTheCombinedRows` |
| E29 | Order, limit or offset called after a union | Applies to the whole union | `UnionTest#orderLimitAndOffsetAfterAUnionApplyToTheWholeUnion` |
| E30 | Update or delete on a joined query | Only the joined rows change; the builder is unchanged | `SQLiteQueryIntegrationTest#updateWithAJoinChangesOnlyTheJoinedRowsAndKeepsTheBuilder`, `PostgresJoinsUnionsIntegrationTest#updateAndDeleteWithAJoinChangeOnlyMatchingRows`, `AndroidQueryTest#updateWithAJoinChangesOnlyTheJoinedRows` |
| E31 | `insertOrIgnoreUsing` with rows that already exist | Duplicates skipped, the others inserted | `SQLiteQueryIntegrationTest#insertUsingCopiesAndInsertOrIgnoreUsingSkipsDuplicates`, `PostgresJoinsUnionsIntegrationTest#insertUsingAndInsertOrIgnoreUsingSkipDuplicates` |
| E32 | `whereRowValues` with different column and value counts | Refused before anything runs | `SubqueryTest#whereRowValuesRefusesMismatchedCountsAndUnknownOperators` |
| E33 | A missing subquery or join argument | Refused with a clear error; any other kind of value cannot be passed in Java | `JoinClauseTest#nullArgumentsAreRefused` |
| E34 | A right join on SQLite older than 3.39 (Android 9 to 13) | SQLite's own error passes through as `QueryException`; 3.39 and later run it | `AndroidQueryTest#rightJoinNeedsSqlite339AndOlderSqlitePassesItsErrorThrough` |
| E35 | An alias with `as` on a joined table or subquery, holding a quote | Quoted safely; it cannot break out of the SQL | `JoinClauseTest#joinSubAliasGetsThePrefixAndIsQuotedSafely`, `JoinClauseTest#aliasedJoinTable` |
| E36 | A copy of a joined and unioned query is changed | The original is unchanged | `SubqueryTest#cloneOfAJoinedUnionedQueryIsIndependent` |
| E37 | `exists()` on a union | Answers for the combined rows | `UnionTest#existsWrapsTheUnion`, `SQLiteQueryIntegrationTest#countAndExistsOnAUnionSeeTheCombinedRows` |
| E38 | Update or delete with a join on a SQLite `without rowid` table | Fails with SQLite's error; nothing changes | `SQLiteQueryIntegrationTest#aJoinedUpdateOnAWithoutRowidTableFailsWithSQLitesError` |
| E39 | A whole union ordered by a subquery | Refused by both databases (a union may only be ordered by a result column) | `SQLiteQueryIntegrationTest#orderingAUnionByASubqueryIsRefusedBySQLite` |
| E40 | Insert, update, delete or truncate on a query that reads from a subquery | Refused before anything runs: a write needs a table | `SubqueryWriteTest#everyWriteOnASubquerySourceIsRefusedBeforeAnythingRuns` |
| E41 | A chunk, each or lazy call on a query with no order | Refused with Laravel's message before anything runs | `ChunkQueriesTest#chunkWithoutAnOrderIsRefusedWithLaravelsMessage` |
| E42 | The chunk callback returns `false` part way | Reading stops and `chunk` returns `false` | `ChunkQueriesTest#chunkStopsWhenTheCallbackSaysSo`, `SQLiteChunkingTest#chunkStopsOnFalseAndLeavesTheBuilderUnchanged` |
| E43 | Rows are inserted or deleted while `chunk` pages by offset | Rows can be skipped or read twice; use `chunkById`, which pages by key | Documented limit |
| E44 | `chunkById` over a join with column `orders.id` and alias `id` | Pages correctly by the qualified key | `SQLiteChunkingTest#chunkByIdOverAJoinWithAQualifiedKeyAndAnAlias`, `ChunkQueriesTest#chunkByIdReadsTheAliasFromRowsOfAJoin` |
| E45 | `chunkById` where the last row has no key column | Refused with a clear error | `ChunkQueriesTest#chunkByIdFailsClearlyWhenTheKeyIsMissing`, `SQLiteChunkingTest#chunkByIdWithoutTheKeyInTheRowsFails` |
| E46 | A limit of 25 with chunks of 10 | Pages of 10, 10 and 5 | `ChunkQueriesTest#chunkHonorsTheUsersLimitAndOffset`, `SQLiteChunkingTest#chunkVisitsEveryRowOnceInOrderAndHonorsTheLimit` |
| E47 | The builder is used again after `chunk` | Same SQL as before | `ChunkQueriesTest#chunkLeavesTheBuilderAsItWas` |
| E48 | A cursor left early and closed | Connection given back, no transaction left open | `CursorConnectionTest#closingEarlyGivesTheSessionBack`, `PostgresCursorIntegrationTest#sessionIsBackInAutocommitAfterAnEarlyClose`, `AndroidCursorTest#cursorClosesOnExhaustionAndOnEarlyClose` |
| E49 | A cursor read to the end and never closed | Closes itself | `CursorConnectionTest#rootCursorStreamsRowsAndGivesTheSessionBackWhenExhausted`, `PostgresCursorIntegrationTest#sessionIsBackInAutocommitAfterExhaustion` |
| E50 | A cursor iterated a second time | Refused | `CursorConnectionTest#secondIterationIsRefused`, `AndroidCursorTest#cursorIteratesOnlyOnceAndRefusesRemove` |
| E51 | A cursor inside a transaction | Reads in that transaction and does not commit it; other statements wait for close | `PostgresCursorIntegrationTest#cursorInsideATransactionReadsInItAndDoesNotCommit`, `CursorConnectionTest#aCursorInATransactionBlocksOtherStatementsUntilClosed`, `SQLiteCursorTest#readsInsideATransactionWithoutEndingIt` |
| E52 | `paginate` asked for a page beyond the last | No rows, correct total | `PageQueriesTest#paginateBeyondTheLastPageGivesNoRowsAndTheTotal`, `SQLitePaginationTest#paginateGivesPagesTotalsAndAnEmptyPageBeyondTheEnd` |
| E53 | `paginate` on a grouped query or a union | Total is the number of groups or combined rows | `SQLitePaginationTest#paginateCountsGroupsAndUnionRows`, `PostgresCursorIntegrationTest#paginateCountsGroupedAndUnionQueriesOnPostgres` |
| E54 | `cursorPaginate` walked back to the start | Same rows as going forward | `SQLitePaginationTest#cursorPaginateWalksForwardAndBackWithTwoOrderColumns`, `PostgresCursorIntegrationTest#cursorPaginateGoesForwardAndBackWithATimestampInTheToken` |
| E55 | A changed or malformed cursor token | Refused with `IllegalArgumentException` | `CursorTokensTest#malformedTokensAreRefused`, `CursorTokensTest#randomTokensNeverEscapeAsAnotherException` |
| E56 | A null in a `cursorPaginate` order column | Rows with the null can be skipped, as in Laravel; order by non-null columns | Documented limit |
| E57 | Another connection writes to SQLite while a cursor is open | The open cursor holds a read lock, so the writer waits or gets busy until it closes | Documented limit |
