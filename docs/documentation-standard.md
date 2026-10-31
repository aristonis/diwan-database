# Documentation standard

How documentation is written in this project. Two parts: Javadoc in the code, Markdown in `docs/`.

## The dividing line

An accepted requirements spec defines the observable behavior and acceptance target before an
implementation exists. Javadoc binds callers and implementers to the detailed contract of the API
that implements those requirements. Design documents explain the chosen structure and ADRs preserve
decisions; neither is another copy of the API contract.

Javadoc sits beside the signature so its parameter, return, error, ordering, mutability,
nullability, and concurrency rules change with the code. Those details belong in Javadoc, not in a
design or overview document. A requirements spec stays at behavior level and does not prescribe a
Java signature.

If Javadoc violates an accepted requirement, the implementation and its Javadoc are defective. The
requirement controls the intended behavior until the requirement itself is changed. Callers may
still rely only on the contract the current Javadoc actually publishes until the defect is fixed.

## Part A: Javadoc

Follows Oracle's Javadoc conventions and the JDK tag style.

A1. Every public type and public member has a docblock. This library is a set of contracts that
other people implement, so the docblock is the deliverable.

A2. The first sentence is a one-line summary in third person, ending with a period. "Returns the
composed code." Not "Return the code" and not "This method returns the code."

A3. `@param`, `@return` and `@throws` are complete on every public method. `@throws` covers
unchecked exceptions too. Every exception in this library is unchecked, so omitting them hides the
contract entirely.

A4. State the facts a caller cannot guess: whether null is accepted, whether a returned collection
is mutable, whether order is guaranteed, whether the type is safe to share across threads.

A5. Use the three JDK tags for what they are for.

- `@apiNote` advises the caller.
- `@implSpec` states what an implementer must guarantee. This is the important one for
  the driver and connection-provider interfaces, which other people implement.
- `@implNote` records a detail of one implementation and binds nobody.

A6. Every package has a `package-info.java` saying what belongs in it and what does not. A rule like
"this package holds framework types only" is enforceable guidance for the next person opening the
folder, so it lives in the package, not only in a design doc.

A7. The docblock carries the contract. In-body `//` comments carry the reasoning, placed above the
decision they explain, with the why written out. A comment that restates the line below it is
deleted.

A8. `{@code}` for code and `{@link}` for references. `<p>` between paragraphs. No other raw HTML.

A9. No `@author`, no `@version`, no dates. Git already knows.

A10. Javadoc builds clean under `-Xdoclint:all`. An unenforced standard is a preference.

A11. English only. Skip the docblock on plain getters and on private helpers whose names already say
it. A padded restatement is worse than nothing.

A12. This standard outranks any general-purpose comment-cleaning rule, skill or command. Those are
written for languages where documentation is optional and where a comment earns its place only by
explaining why. Here the docblock is the deliverable and the build refuses to publish without it, so
the generic advice is right about prose and wrong about structure. Rules A13 to A16 name the four
places they collide. Applying the generic rule instead of the project rule breaks the build in two of
the four, and destroys deliberate work in the other two.

A13. A block tag restates the signature on purpose, and that is its job. `@param name the connection
name` says nothing the reader could not guess, and A3 requires it anyway: the contract is published
as a whole or it is not published. A rule that deletes comments for restating the code stops at the
docblock boundary. It governs the summary sentence and the in-body narrative, never the tags.

A14. The summary sentence is required even when the method name already says it. A2 gives its shape;
A1 requires it to exist. An obvious summary is the price of a contract that can be read without the
source beside it.

A15. `@param` and `@return` are noun phrases, not sentences. `@return the normalized code`, not
`@return This method returns the normalized code` and not `@return Returns the normalized code`. A
style rule that converts passive or subjectless fragments into full active sentences does not apply
to a block tag.

A16. An em dash is allowed and is used heavily in this project's Javadoc. The argued reasoning this
codebase prefers leans on it as a connective, and rewriting it away flattens the argument into a list
of facts. This rule exists because a general prose rule treats the em dash as an error; here it is
not one. Markdown under `docs/` is written plainer and uses none, which is a register difference and
not a contradiction.

A17. Javadoc that argues against a superseded design stays. A paragraph that says an earlier version
of a decision was wrong, and why, is the most valuable kind of comment in a codebase that changes its
own shape as it grows. A general rule against writing that narrates a change is aimed at
documentation which should read as though the current state were always the state. A contract other
people implement is not that: the rejected alternative is part of what a reader needs.

## Part B: project docs

B1. Six kinds of document, six homes.

| Kind | Path | Lifetime |
|---|---|---|
| Requirements spec | `docs/requirements/<area>.md` | Accepted before implementation starts. A later change edits the spec before the code. |
| System architecture | `docs/architecture/system-design.md`, `docs/architecture/<area>-design.md` | Accepted before implementation starts. A later change edits the design before the code. |
| Decision record | `docs/adr/NNNN-slug.md` | The decision never changes once accepted. A new ADR supersedes it. |
| Design spec | `docs/design/<area>.md` | Living. Kept current with HEAD. |
| Overview, glossary | `docs/*.md` | Living. |
| Behavior | `docs/behavior/<feature>/README.md`, `user-stories.md`, `user-scenarios.md` | Living. What one feature does now, in plain language, with the test behind each edge case. |

`README.md` and `CHANGELOG.md` stay at the repository root. A behavior folder also opens with a
`README.md`, the one name B8's kebab-case rule does not cover. The changelog follows Keep a Changelog.

B2. A design spec uses these sections in this order: purpose and scope, rules, contracts, naming,
decisions, migration, open questions. Same shape for every area, so a reader knows where to look.
A system architecture uses architecture sections and diagrams instead, because it connects
several design areas rather than documenting one area's detailed contract.

B3. An ADR uses the Nygard sections: status, context, decision, consequences, alternatives rejected.
One page. Write an ADR when the decision was contested or would cost something to reverse, not for
every choice.

B4. Number the rules and the decisions (R1, D1). A numbered item can be cited in review, in a commit
message, or from an `@apiNote`. Unnumbered prose cannot be pointed at. A number belongs to the
document that defines it. Another document citing it names that document and says what the item is.

B5. A document carries no status line. Whether a document is a draft or accepted is workflow
state, kept outside the repository; the text in `docs/` is always the current description of the
system. The one exception is an ADR, whose Status section is part of the ADR format (B3) and is how
a reader finds the decision that superseded it.

B6. Diagrams are Mermaid inside the Markdown, each under a heading that names it. No exported
images.

B7. Documentation is updated before the code changes, not after.

B8. Filenames are kebab-case. English only. Wrap prose at 100 columns so diffs stay readable. Table
rows and code blocks are exempt, since neither can be wrapped without breaking.

B9. A requirements spec states observable behavior and acceptance before an implementation exists.
It does not prescribe Java signatures or repeat their detailed preconditions once those contracts
exist. A system architecture may fix the intended interface and type shape before implementation.
After implementation, design and overview documents name those types instead of copying their full
caller contract. Javadoc remains binding to callers, and a mismatch between it and an accepted
requirement is a defect to reconcile.

B10. A document stands alone. A reader holding only this repository can follow every sentence. The
document never cites an identifier defined outside the repository, such as a planning decision
number, a milestone, an audit finding, a sprint or a workflow stage, and never names a path outside
it. Where such an identifier carried meaning, write the meaning out: the decision, the reason for
it, the defect it fixed.

B11. A document describes the system, not the process that produced it. No status, freeze, gate,
sign-off, review round, milestone or revision history; an ADR's Status section (B5) is the only
lifecycle marker. A change is merged into the text as the current state; an alternative that was
dropped stays only as reasoning, written as a rejected alternative with its reason.
