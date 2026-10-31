# 0002. Own driver interface under the connection

## Status

Accepted

## Context

Diwan Database must run on Java 17 desktop and server runtimes, inside Spring, and on Android 9 and
later. Android has no standard SQLite JDBC driver. Its database API is
`android.database.sqlite`, and the third-party JDBC driver for SQLite ships a second copy of
SQLite inside the app. Building the connection directly on `java.sql` would tie the whole library
to JDBC.

## Decision

The connection runs on a small driver interface of its own in
`org.aristonis.diwan.database.driver`: `Driver` opens a `DriverSession` (one physical
connection) and reports `DriverInfo` (database name, version, capabilities). No signature in that
package uses a JDBC type. Failures are a `DriverException` whose kinds (unique violation,
concurrency failure) the driver sets, because only the driver knows its database's codes. The
value contract for bindings and returned values is written once in the package documentation.

The JDBC adapter (`JdbcDriver` over a `JdbcConnectionProvider`) ships in the core. An Android
adapter can implement the same interface without JDBC.

## Consequences

- The core never needs JDBC; the only JDBC types it touches are the two builder entry points,
  `dataSource(DataSource)` and `connectionProvider(JdbcConnectionProvider)`.
- Adapter authors implement two methods on `Driver` and twelve on `DriverSession`, nothing more.
- Operations added later arrive as `default` methods, so existing drivers keep compiling.
- The core cannot use JDBC features directly; each one must be expressed through the driver
  interface first.

## Alternatives rejected

- JDBC as the only layer under the connection: excludes Android's built-in SQLite.
- One interface per platform with no shared contract: the transaction logic would be written once
  per platform.
