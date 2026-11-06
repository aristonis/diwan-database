# Contributing

Thanks for helping. Bug reports, test cases and fixes are all welcome.

## Before you start

- **Security problems:** never open a public issue. Report them privately, as described in
  [SECURITY.md](SECURITY.md).
- **Bigger changes:** open an issue first, so we can agree on the approach before you write code.
- **Behaviour:** Diwan follows Laravel's `illuminate/database` where that reads well in Java. If you
  think Diwan should differ from Laravel, say why in the issue.

## Build and test

You need JDK 17 or later.

```sh
./gradlew check
```

This compiles every module, runs the tests on JDK 17, 21 and 25, checks the core against the
Android 28 API, and builds the Javadoc with warnings as errors.

## Pull requests

- Write the failing test first, then the fix.
- Keep one change per pull request.
- Public classes and methods need Javadoc that follows the
  [documentation standard](docs/documentation-standard.md).
- Use [Conventional Commits](https://www.conventionalcommits.org/): `type(scope): subject`, for
  example `fix(query): quote cursor aliases`.
- Add a line to [CHANGELOG.md](CHANGELOG.md) for anything a user can see.

## Licence

By contributing, you agree that your work is released under the [MIT licence](LICENSE).
