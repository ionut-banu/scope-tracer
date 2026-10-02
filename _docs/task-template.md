Do not hard-wrap this. GitHub turns a single newline in an issue body
into a line break, so wrapped prose renders as ragged short lines. One
paragraph per line, one list item per line, however long. See "Writing
for GitHub" in `_docs/process.md`.

## Goal

One or two sentences on what should be true when this is done.

## Spec

The section of `_docs/spec.md` this implements, and anything in it that this task deliberately does not cover yet.

## Acceptance criteria

- [ ] A statement you can check by running something
- [ ] One line per case, including the awkward ones
- [ ] What the trace and the report show when a task fails, and when its sibling is cancelled
- [ ] What happens with the agent as well as with `TracedScope`, or why only one applies
- [ ] What happens when an event is missing from the recording
- [ ] Whether a recording made by the last release still parses

## Tests

- New tests this adds, and which suite: core/analyzer/agent unit, agent IT, stress, plugin `:model`
- Expected effect on the test count (it never goes down)

## Compatibility

- Events or fields added, and the `docs/jfr-events.md` rows that change
- Changes to `TraceModelJson`'s output, and the matching plugin `:model` change
- Agent arguments added or changed
- "None" is a valid answer; leaving the section out is not

## Out of scope

- Something that does not belong in this task, moved to #TASK-NUMBER

## Constraints

- Modules and files this should stay inside
- Libraries to use
- Guidelines to follow
