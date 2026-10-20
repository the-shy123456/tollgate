# Contributing to Tollgate

Thanks for considering a contribution. This document covers the mechanics and the two rules that
are not open for discussion.

---

## Two non-negotiable rules

Everything else is a matter of taste. These two are not.

### 1. `tollgate-core` has zero third-party runtime dependencies

The JDK, and nothing else. Not Jackson, not Guava, not Caffeine, not SLF4J, not even a logging
facade. If you need a capability, implement it against the JDK or put it behind an interface that
a downstream module implements.

The reason is in [ADR-0001](docs/design-decisions.md#adr-0001): a governance library that changes
an application's dependency graph gets rejected at the architecture review, and a rejected
library helps nobody.

The one exception is test scope, where JUnit and AssertJ are used freely.

### 2. No cache hit may cross a tenant or a literal value

A cache hit that serves one tenant's answer to another tenant is a data breach. A cache hit that
serves `订单 67890`'s answer to a question about `订单 12345` is a data breach with extra steps.

Any change touching `InMemorySemanticCache`, `QueryCanonicalizer` or `LiteralGuard` must come with
a test proving the boundary still holds. Reviewers will ask for it.

---

## Getting set up

**Requirements:** JDK 17 or newer. Maven is not required — the wrapper is checked in.

```bash
git clone https://github.com/the-shy123456/tollgate.git
cd tollgate
./mvnw verify
```

`verify` compiles, runs the full test suite and packages the modules. On Windows, use `mvnw.cmd`.

**Run the demo:**

```bash
./mvnw -pl tollgate-example -am package -DskipTests
java -jar tollgate-example/target/tollgate-example-0.1.0.jar
```

It runs against `SimulatedProvider`, so no API key and no network access are needed.

---

## What we look for in a pull request

**A test that fails before your change and passes after it.** For a bug fix, that is the test that
reproduces the bug. For a feature, it is the test that describes the behaviour. A pull request
that adds behaviour without a test is a pull request that will regress.

**A reason in the commit message.** Not "fix bug" but "reserve quota before the provider call, so
concurrent callers cannot exceed the budget". The diff shows what changed; the message should say
why.

**Small and focused.** One concern per pull request. If you find yourself writing "also, while I
was in here", open a second pull request.

**Javadoc on anything public.** The public API is the product. A public method without a javadoc
comment explaining what it guarantees is incomplete, even if the signature is obvious to you.

---

## Code style

There is no formatter configuration to fight with. The conventions are:

- Four spaces, no tabs.
- 120 column soft limit.
- `final` fields, immutable objects, builders for anything with more than four parameters.
- Prefer records for value types that carry data and no behaviour.
- No wildcard imports.
- Comments explain *why*, never *what*. If the code needs a comment to explain what it does,
  rename something instead.

The one stylistic rule that reviewers enforce strictly: **a comment must add information the code
does not already carry.** `// increment the counter` above `counter++` will be removed.

---

## Test conventions

- JUnit 5 with AssertJ.
- Test names are sentences: `neverServesOneTenantsAnswerToAnother`.
- `@DisplayName` states the guarantee in plain language.
- Use the injectable clocks (`LongSupplier`) rather than `Thread.sleep`. A test that sleeps is a
  test that flakes.
- Prefer testing observable behaviour over implementation details. If a refactor breaks your test
  without changing behaviour, the test was testing the wrong thing.

Current suite: 99 tests, all passing. Adding tests is always welcome; reducing coverage is not.

---

## Commit messages

Conventional Commits, loosely enforced:

```
feat(cache): gate similarity matching on literal fingerprints
fix(quota): reset rejected counter when the window rolls over
docs(adr): record the routing weight reversal
refactor(route): extract candidate filtering into the base class
test(cache): cover tenant isolation under concurrent puts
```

The scope is the package or module name.

---

## Reporting a bug

Include:

1. The version (or commit) you are on.
2. A minimal reproduction — ideally a test that fails.
3. What you expected and what happened.
4. Your JDK vendor and version, if the problem smells like a platform issue.

If the bug is a cache returning a wrong answer, say so in the title. Those are treated as
security issues and triaged first.

---

## Proposing a design change

Open an issue with the `design` label before writing code, especially for anything that touches:

- the request pipeline order,
- the cache key composition,
- the routing score,
- the public API surface.

These four are load-bearing. A pull request that changes one of them without prior discussion is
likely to be asked to start over as a discussion, which wastes your time.

Design decisions are recorded in [docs/design-decisions.md](docs/design-decisions.md). If your
change supersedes an existing ADR, the pull request should update that ADR rather than silently
contradicting it.

---

## Security issues

Do not open a public issue. See [SECURITY.md](SECURITY.md).

---

## License

By contributing you agree that your contribution is licensed under the Apache License 2.0, as
described in section 5 of that license. No separate CLA is required.
