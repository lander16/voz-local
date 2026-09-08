# VozLocal contributor workflow

These instructions apply to the repository except where a more specific `AGENTS.md`
exists. The vendored whisper.cpp subtree has its own instructions.

## Sources of truth

- Use GitHub Issues for executable work: scope, checklists, dependencies, progress,
  blockers, decisions, and retained validation evidence.
- Keep `Issues-Implementation-Plan.md` and `Performance-Implementation-Plan.md` as
  architectural roadmaps. Update them when architecture, priority, dependencies, or
  acceptance criteria change; do not duplicate an issue's day-to-day history there.
- Put durable, user-relevant findings in `README.md` or `Performance.md`. Link the
  supporting issue or retained artifact when practical.

## Starting and tracking work

1. Read the relevant issue and its acceptance criteria before editing code.
2. Confirm the issue is not a duplicate and identify dependencies or blockers.
3. Keep the implementation within that issue's scope. Record material scope changes
   or newly discovered work in GitHub instead of silently expanding the change.
4. Add concise progress comments with completed work, checks run, measurements, and
   remaining gaps. Report unavailable measurements as unavailable, never as zero.
5. Close an issue only when its acceptance criteria are met. Use `Refs #N` for partial
   work and `Closes #N` only when the commit completes the issue.

## Evidence and privacy

- Measurements must record enough provenance to reproduce them: app commit/build,
  device and OS, model and checksum, settings, audio identity/hash, run order, power,
  charging, and thermal state when relevant.
- Keep raw private recordings, private transcripts, signing material, and bank-app
  data out of Git and GitHub. Checked-in corpus audio must have verifiable permission
  to redistribute; otherwise retain only non-sensitive metadata and hashes.
- Treat planning prompts and estimated durations as test design, not as a measured or
  licensed audio corpus.
- Never automate or inspect a banking app during validation. Ask the user to perform
  bank-app compatibility checks and report only the result.

## Implementation and validation

- Prefer focused, reversible commits. Keep workflow/documentation changes separate
  from functional changes when they can be reviewed independently.
- Preserve unrelated user changes and vendored code unless the issue requires them.
- Add automated tests for behavior changes and run the narrowest relevant checks,
  followed by the appropriate project-level check before pushing.
- For Pixel performance runs, prevent competing benchmarks/downloads, note Battery
  Saver and charging state, capture thermal state, alternate candidate order, and
  separate cold from warm measurements.
- Do not promote a performance result from a single run or an unverified corpus.

## Finishing work

1. Review the diff and test evidence.
2. Commit with a focused message and an issue reference.
3. Push the validated commit to the requested branch.
4. Update the GitHub issue with the commit, exact checks, retained evidence, and what
   remains. Update roadmap or durable documentation only when warranted above.
