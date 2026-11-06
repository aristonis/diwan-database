---
name: diwan-report-issue
description: Prepare and file a bug report against Diwan Database. Use when a Diwan call fails in a way the docs do not explain, behaves differently from the docs, or the user asks to report an issue.
---

# Report a Diwan issue

## Security problems

If the problem is a vulnerability (injection, data exposure, a bypass of a check), do not file a
public issue and never run `gh issue create`. Tell the user to report it privately at
https://github.com/aristonis/diwan-database/security/advisories/new and stop.

## Steps

1. Reduce the failure to the smallest code that still fails, on an empty database.
2. Collect: Diwan version, module (core, sqlite, pgsql, eloquent, android), database and version,
   Java version or Android API level.
3. Copy the full exception with its cause chain, not only the top message.
4. Scrub it (see below), write the report to a file, show it to the user, and file it only after they approve.

## Example

```bash
gh issue create --repo aristonis/diwan-database \
  --title "paginate returns the wrong total with a group by" \
  --body-file issue.md
```

`issue.md`:

```markdown
**Diwan version:** 0.1.0
**Module:** diwan-database-sqlite
**Database:** SQLite 3.45
**Java / Android:** Java 17

**Code:**
    query.table("orders").groupBy("status").paginate(20, 1);

**Error (full cause chain):**
    ...

**Expected:** total equals the number of groups.
**Actual:** total equals the number of rows.
```

## Pitfalls

- Never include secrets, connection strings with passwords, tokens, or binding values that hold
  personal data. Replace them with placeholders.
- Do not paste the whole project. A minimal example is faster to fix.
- Check the docs page for the feature first. Some behavior is listed as a difference from Laravel.
- A report about the docs or a skill goes to aristonis/diwan-database-docs or the skills folder issues.
- The repository URL works once the project is public.

Docs: Report an issue on https://aristonis.org/open-source/diwan-database/docs/main/home
