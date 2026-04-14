# Antigravity Agent Instructions

## Before Starting Any Task
1. Read `Project_Context.md` in full before writing a single line of code.
2. Read `Latest_Updates.md` to understand what has changed from the original architecture.
3. Read the current phase's `Phase_N_Architecture.md` as the primary implementation guide.

## Scope Rules
- Work **only** within the current phase. Do not implement features belonging to future phases.
- If a future-phase class or method is referenced, create a minimal stub (empty method body) and leave a `// TODO: Phase N` comment.

## Build Verification
- After completing each numbered step, run: `mvn clean compile`
- Do not proceed to the next step until the build passes with zero errors.
- For UI steps, also launch the app and confirm the window appears without exceptions.

## Logging Changes
- After every file you create or modify, update `Latest_Updates.md` immediately.
- Follow the exact template format already in that file — no new sections, no reformatting.

## Tech Stack Rules
- Never change a dependency version in `pom.xml`.
- Never substitute an alternative library for one already specified.
- Never add a dependency not listed in the current phase architecture without flagging it first.

## Error Handling
- Fix errors in place within the existing file structure.
- Do not reorganize packages or rename files to resolve an error.
- If an error cannot be resolved without restructuring, stop and flag it clearly in `Latest_Updates.md` under "Known Issues."

## Ambiguity Protocol
- If any instruction is ambiguous, stop immediately.
- Do not guess or make assumptions.
- Document the ambiguity in `Latest_Updates.md` and wait for clarification.
