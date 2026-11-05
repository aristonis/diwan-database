# Seeders

A seeder fills tables with starting or test data. A `DatabaseManager` runs a root seeder, and that
seeder can call others.

```java
public final class UserSeeder extends Seeder {
    @Override
    protected void run(SeedContext context) {
        context.query().table("users").insert(Map.of("name", "Ada"));
    }
}

public final class DatabaseSeeder extends Seeder {
    @Override
    protected void run(SeedContext context) {
        context.call(UserSeeder::new, PostSeeder::new);
    }
}

db.seed(DatabaseSeeder::new);
```

A seeder is created by a supplier, `UserSeeder::new`. Nothing looks a class up by name, so it works
under R8.

## The context

`SeedContext` is what a seeder receives.

| Method | Returns |
|---|---|
| `manager()` | the `DatabaseManager` that runs the seed |
| `connection()` | the connection the seed uses |
| `query()` | a `QueryFactory` on that connection |
| `schema()` | a `Schema` on that connection |
| `call(suppliers...)` | runs each seeder; returns the context |
| `callOnce(suppliers...)` | runs a seeder only if its class has not run in this seed |
| `callSilent(suppliers...)` | runs each seeder without telling the listener |

By default the connection is the manager's default connection. Choose another with
`SeedOptions.defaults().connection("cache")`.

## Calling once

`callOnce` skips a seeder whose class has already run during the same `seed` call. This is useful
for shared set-up that several seeders need:

```java
public final class PostSeeder extends Seeder {
    @Override
    protected void run(SeedContext context) {
        context.callOnce(UserSeeder::new);       // runs only if nobody called it yet
        // ...
    }
}
```

The record of called seeders belongs to one top-level `seed` call. The next `seed` call starts
empty, so a second run in the same process, such as the next test, runs every seeder again. `call`
and `callSilent` record the class too, so a later `callOnce` skips it.

## Cycles

A seeder that is reached again while it is still running would loop. The run throws
`IllegalStateException` that names the chain, for example `A -> B -> A`.

## Listening

A `SeederListener` is told when each seeder starts, finishes and fails. Every method has an empty
default, so implement the ones you want.

```java
SeedOptions options = SeedOptions.defaults().listener(new SeederListener() {
    @Override
    public void finished(Class<?> seeder, Duration elapsed) {
        log.info("{} done in {} ms", seeder.getSimpleName(), elapsed.toMillis());
    }
});
db.seed(DatabaseSeeder::new, options);
```

`started(Class)`, `finished(Class, Duration)` and `failed(Class, Throwable)` are the events. A
seeder called with `callSilent` produces none, and neither does a `callOnce` that was skipped.
`failed` is called before the exception continues to the caller.

## Seeding in a transaction

```java
db.seed(DatabaseSeeder::new, SeedOptions.defaults().inTransaction(true));
```

By default the seed is not wrapped in a transaction, as in Laravel, so rows written before a
failure stay. With `inTransaction(true)` the run is one transaction on the chosen connection. The
context's `connection()`, `query()` and `schema()` are bound to that transaction, and so is every
nested call, so a failure rolls back all of it. Use `context.query()` inside a seeder. A query
factory taken from the manager would use a different connection and sit outside the transaction.

## Models without events

Seeding many models can fire listeners you do not want, such as an audit log. Wrap the root
seeder:

```java
db.seed(ModelSeeding.withoutModelEvents(DatabaseSeeder::new));
```

`ModelSeeding.withoutModelEvents` returns a supplier of a seeder that runs the wrapped one inside
`Model.withoutEvents`. Listeners and observers are muted for every model on the seeding thread. The
models' own protected hooks still run. See [model events](../eloquent/model-events.md).

## Differences from Laravel

| Laravel | Here | Reason |
|---|---|---|
| `Seeder::$called` is static and never reset | the called record lasts for one `seed` call | a second run in the same process would skip seeders |
| seeders are resolved from the container by class name | seeders are created by a supplier | no container, no reflection |
| progress is written to the console | a `SeederListener` | no console in the library |
| `$this->call(...)` accepts class names or arrays | `call(suppliers...)` | typed |
| a cycle recurses until the stack fails | `IllegalStateException` naming the chain | clear failure |
| `WithoutModelEvents` trait | `ModelSeeding.withoutModelEvents(supplier)` | no traits |
| `db:seed` runs in a transaction only when asked | same: off by default, `inTransaction(true)` turns it on | same |

Laravel's static record is never cleared, which is why a second run in one process skips seeders.

## Not in this release

- A `db:seed` console command. It arrives with the migration command line.
- Seeder output on a console.
- Passing parameters to a seeder's `run` (`callWith`). Capture them in the supplier:
  `() -> new UserSeeder(count)`.

## More

- [Database manager and configuration](database-manager.md)
- [Decision record](../../adr/0024-database-manager-config-seeders.md)
