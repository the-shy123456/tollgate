## What changed

<!--
One paragraph. What behaviour is different after this pull request, from the point of view of
somebody using the library?
-->

## Why

<!--
The reason, not the diff. If this fixes an issue, link it.
If this contradicts an existing ADR, say which one and update it.
-->

Fixes #

## How it was verified

<!--
Paste the test names that cover this, or the command you ran.
A pull request without a test needs a sentence explaining why a test is not possible.
-->

## Checklist

- [ ] Tests pass locally (`./mvnw verify`)
- [ ] New behaviour is covered by a test that failed before the change
- [ ] Public API changes are documented with javadoc
- [ ] `tollgate-core` still has zero third-party runtime dependencies
- [ ] No cache path can cross a tenant or a literal value
- [ ] `CHANGELOG.md` updated under `[Unreleased]`, if behaviour changed
- [ ] An ADR was added or amended if this changes a recorded design decision

## Screenshots or output

<!-- Optional. Terminal output from the example app is often the clearest evidence. -->
