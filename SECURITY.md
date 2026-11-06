# Security policy

## Reporting a vulnerability

Report a security problem privately. Do not open a public issue, a public discussion or a pull
request for it, and do not post it anywhere else before it is fixed.

Use GitHub private vulnerability reporting for the repository:
<https://github.com/aristonis/diwan-database/security/advisories/new>

Include:

- The Diwan version and module (core, sqlite, pgsql, eloquent or android).
- The database and its version, and the Java version or Android API level.
- What an attacker can do, and the conditions that make it possible.
- The smallest code that shows the problem, on an empty database.
- The full error, with its cause chain, if there is one.

Leave out real secrets, connection strings with passwords and personal data. Use placeholders.

## What to expect

A maintainer reads each report and replies in the advisory thread. We confirm whether the report
is a vulnerability, work on a fix with you in private, and publish an advisory with the fix. We
credit the reporter unless you ask us not to. We do not promise a date, because it depends on the
size of the problem.

## Supported versions

The latest release receives security fixes.
