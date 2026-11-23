# Contributing to Reference Count Maven Plugin

Thank you for your interest in contributing!

## Prerequisites

- **JDK 17+** (bytecode target is Java 17)
- No global Maven installation is required — use the bundled Maven Wrapper (`./mvnw`)

## Building & Testing Locally

Run unit tests:

```bash
./mvnw clean test
```

Run unit tests **and** Maven Invoker integration tests (`src/it/`):

```bash
./mvnw clean verify -Prun-its
```

Generate and verify the Maven site (including plugin goal documentation):

```bash
./mvnw site
```

## Pull Request & Commit Conventions

This repository uses **Release Please** and enforces **[Conventional Commits](https://www.conventionalcommits.org/)** on pull request titles in CI. Please prefix your PR title appropriately:

| Prefix | Meaning | Triggers Release? |
|---|---|---|
| `feat:` | New user-facing feature | Minor (`x.Y.0`) |
| `fix:` | Bug fix | Patch (`x.y.Z`) |
| `perf:` | Performance improvement | Patch (`x.y.Z`) |
| `feat!:` / `BREAKING CHANGE:` | Breaking change | Major (`X.0.0`) |
| `docs:` | Documentation only | No |
| `test:` | Adding or updating tests | No |
| `refactor:` | Code refactoring without behavior change | No |
| `ci:` / `chore:` | CI workflows, build tooling, housekeeping | No |

## Guidelines

- Keep runtime dependencies minimal (the plugin intentionally depends only on `org.ow2.asm:asm` at runtime).
- Add unit tests in `src/test/java` and, for new Mojo parameters or goals, an integration test under `src/it/`.
- Document new Mojo parameters with Javadoc on `AbstractReferenceCounterMojo` (or the Mojo class) so the generated `plugin-info.html` and `ref-count:help` stay complete.
