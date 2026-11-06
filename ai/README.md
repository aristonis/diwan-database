# Diwan Database for AI agents

This folder holds files a coding agent reads so it writes correct Diwan Database code in your
project. Nothing here runs at build time, and nothing in it is needed at runtime.

| File | What it is |
|---|---|
| `AGENTS.md` | The rules file. Short: what Diwan is, the entry point per database, the must and never rules, and where the docs live. |
| `skills/<name>/SKILL.md` | One task per skill, in the Agent Skills format (YAML frontmatter with `name` and `description`, then steps). |

Skills:

| Skill | Use it when |
|---|---|
| `diwan-usage` | Setting up a connection, running SQL, using transactions. |
| `diwan-query-builder` | Reading or writing rows with `table(...)`. |
| `diwan-migrations` | Writing or running a migration, changing the schema. |
| `diwan-models-relations` | Defining models, relations or eager loading. |
| `diwan-android` | Running Diwan on Android. |
| `diwan-report-issue` | Filing a bug against Diwan. |

## Load them

1. Copy the rules into your project's agent file. Paste the body of `AGENTS.md` into your own
   `AGENTS.md` or `CLAUDE.md`, or reference this file from it.
2. Copy the skills you want into the folder your agent reads skills from. For Claude Code that is
   `.claude/skills/` in the project or `~/.claude/skills/` for every project:

```bash
cp -r ai/skills/* .claude/skills/
```

Other agents that support the Agent Skills format read the same folders from their own skills
directory. Each skill is one folder with one `SKILL.md`.

The guides at https://docs.aristonis.org/diwan-database/ stay the source of truth. If a skill and a
guide disagree, trust the guide and report the difference through the `diwan-report-issue` skill.
