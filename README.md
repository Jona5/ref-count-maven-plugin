# Reference Count Maven Plugin

[![Maven Central](https://img.shields.io/maven-central/v/io.github.jona5/ref-count-maven-plugin)](https://central.sonatype.com/artifact/io.github.jona5/ref-count-maven-plugin)
[![CI](https://github.com/Jona5/ref-count-maven-plugin/actions/workflows/build.yml/badge.svg)](https://github.com/Jona5/ref-count-maven-plugin/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

This Maven plugin analyzes your project's compiled bytecode (including generics, annotations, lambdas, method references, inheritance, and fields) to measure **how heavily** each dependency is used, its **blast radius** (number of referencing project classes), **surface area** (number of used dependency classes), and **JAR size**.

For full documentation, configuration recipes, goal parameter references, and known limitations, visit the **[project website](https://jona5.github.io/ref-count-maven-plugin/)**.

## Quick Start

### Try It Without Modifying `pom.xml`

Run the plugin directly from the command line on any compiled Maven project:

<!-- x-release-please-start-version -->
```bash
mvn compile io.github.jona5:ref-count-maven-plugin:1.5.4-SNAPSHOT:count-references
```
<!-- x-release-please-end -->

### Add to `pom.xml`

Add the plugin to the `<build><plugins>` section of your `pom.xml`:

<!-- x-release-please-start-version -->
```xml
<plugin>
    <groupId>io.github.jona5</groupId>
    <artifactId>ref-count-maven-plugin</artifactId>
    <version>1.5.4-SNAPSHOT</version>
</plugin>
```
<!-- x-release-please-end -->

Then run for a single module or aggregate across a multi-module reactor:

```bash
mvn compile ref-count:count-references
mvn compile ref-count:aggregate
```

To run automatically during `mvn verify` (e.g. in CI with `failOnUnused` or `baselineFile`), add an `<execution>`:

<!-- x-release-please-start-version -->
```xml
<plugin>
    <groupId>io.github.jona5</groupId>
    <artifactId>ref-count-maven-plugin</artifactId>
    <version>1.5.4-SNAPSHOT</version>
    <executions>
        <execution>
            <goals>
                <goal>count-references</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```
<!-- x-release-please-end -->

## How Counting Works & Reading the Output

The plugin scans every `.class` file in `target/classes` (and optionally `target/test-classes`) using ASM and counts bytecode-level references to classes from your dependencies, including:

- Method invocations, field accesses, object allocations (`new`), casts, `instanceof`, and array types
- Superclasses, implemented interfaces, field types, method parameter/return types, and `throws` / `try-catch` exception types
- Generic type arguments and bounds (e.g. `List<MyDependencyType>`)
- Class, field, method, and parameter annotations (plus annotation class/enum values)
- Lambdas, method references (`StringUtils::isBlank`), and class literals (`StringUtils.class`)

### Sample Output

```text
[INFO] --- Reference Analysis Results ---
[INFO]   org.apache.commons:commons-lang3:3.12.0 -> 42 references [direct, 574 KB, 4 dep classes in 12 project classes]
[INFO]   com.google.guava:guava:33.0-jre -> 2 references [direct, 3.0 MB, 1 dep class in 1 project class, low usage]
[INFO]       com.example.App -> com.google.common.collect.Lists
[INFO]   org.example:transitive-lib:2.1.0 -> 3 references [transitive - undeclared!, 92 KB, 1 dep class in 2 project classes]
[INFO]   org.example:unused-lib:1.0.0 -> 0 references [direct, 48 KB]
[INFO] -------------------------------------
```

- **`direct` vs. `transitive - undeclared!`**: Highlights directly declared dependencies versus transitive dependencies referenced directly by your code without being declared in your `pom.xml`.
- **`X dep classes in Y project classes`**: Shows *surface area* (distinct dependency classes used) and *blast radius* (how many of your own classes depend on the library).
- **`low usage`**: Shown when `lowUsageThreshold > 0` and `0 < references <= lowUsageThreshold`, automatically printing the exact referencing classes to help prioritize refactoring.
- **Split-package warnings**: Logged automatically if the same class name exists in multiple dependencies.

## Configuration Options

All parameters apply to both `ref-count:count-references` and `ref-count:aggregate` (see also `mvn ref-count:help -Ddetail=true` or the [Goals reference](https://jona5.github.io/ref-count-maven-plugin/plugin-info.html)):

| Parameter | Property | Default | Description |
|---|---|---|---|
| `showUnused` | `refcount.showUnused` | `true` | Show dependencies with `0` references in the output. |
| `includeTransitive` | `refcount.includeTransitive` | `true` | Include transitive dependencies (`false` analyzes only directly declared dependencies). |
| `includeTestClasses` | `refcount.includeTestClasses` | `false` | Also scan `target/test-classes` and include `test`-scoped dependencies (run `mvn test-compile` first). |
| `excludes` | `refcount.excludes` | *(empty)* | Exclusion patterns (`groupId:artifactId`, `groupId:artifactId:version`, `groupId:*`, `*:artifactId`, `*`). |
| `showDetails` | `refcount.showDetails` | `false` | Print per-project-class to dependency-class reference details. |
| `lowUsageThreshold` | `refcount.lowUsageThreshold` | `0` | Highlight dependencies with `0 < references <= threshold` as low-usage refactoring candidates and show their details. |
| `outputFormat` | `refcount.outputFormat` | `console` | Export format: `console`, `json`, `csv`, or `markdown` (`md`). |
| `outputFile` | `refcount.outputFile` | `target/ref-count-report.{json,csv,md}` | Destination file for exported reports (defaults to JSON if set while `outputFormat=console`). |
| `baselineFile` | `refcount.baselineFile` | *(none)* | Baseline JSON file for migration ratchet checks (prevents references to tracked dependencies from increasing). |
| `updateBaseline` | `refcount.updateBaseline` | `false` | Generate or update `baselineFile` (`ref-count-baseline.json` by default) from current counts. |
| `failOnIncrease` | `refcount.failOnIncrease` | `true` | Fail the build if any dependency tracked in `baselineFile` exceeds its baseline count. |
| `failOnUnused` | `refcount.failOnUnused` | `false` | Fail the build if any analyzed dependency has `0` references. |
| `skip` | `refcount.skip` | `false` | Skip execution of the plugin. |

### Migration Ratchet (Baseline Mode)

When migrating away from a library (e.g. `com.google.guava:guava`), generate a baseline:

```bash
mvn compile ref-count:count-references -Drefcount.updateBaseline=true
```

This creates a version-agnostic `ref-count-baseline.json` (version upgrades via Dependabot won't break your baseline). You can commit the full file or keep only the libraries you want to ratchet down:

```json
{
  "com.google.guava:guava": 15,
  "org.apache.commons:commons-lang3": 42
}
```

Then enforce it in CI so references can only stay equal or decrease:

```bash
mvn compile ref-count:count-references -Drefcount.baselineFile=ref-count-baseline.json
```

### GitHub Actions Step Summary

Export a Markdown table directly into a GitHub Actions job summary:

```bash
mvn compile ref-count:count-references \
  -Drefcount.outputFormat=markdown \
  -Drefcount.outputFile="$GITHUB_STEP_SUMMARY"
```

## Building, Testing & Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for local build instructions, integration tests (`./mvnw verify -Prun-its`), and Conventional Commit PR guidelines.

## Releasing (Automated via Release Please)

This repository uses **[Release Please](https://github.com/googleapis/release-please)** and **[Conventional Commits](https://www.conventionalcommits.org/)** to automate versioning, changelog generation, GitHub Releases, Maven Central publishing, and GitHub Pages updates.

### How It Works

1. Open a Pull Request targeting `main` using a Conventional Commit PR title (`fix:`, `feat:`, `feat!:`, `chore:`, `docs:`, etc.).
2. Merge the PR (**Squash and merge** recommended).
3. `release-please` automatically opens or updates a **Release PR** that bumps `pom.xml`, updates `README.md`, and generates `CHANGELOG.md`.
4. Merging the **Release PR** automatically creates the `v<version>` tag and GitHub Release, runs `./mvnw -B verify -Prun-its`, signs and publishes artifacts to **Maven Central**, and deploys the updated documentation site to **GitHub Pages**.

### Repository Setup (One-Time)

Configure the following secrets under `Settings` > `Secrets and variables` > `Actions`:

- `SONATYPE_USERNAME`: Username for Sonatype Central Portal.
- `SONATYPE_TOKEN`: Token for Sonatype Central Portal.
- `GPG_PRIVATE_KEY`: Private GPG key in ASCII-armored format.
- `GPG_PASSPHRASE`: Passphrase for the GPG key.
- `RELEASE_PLEASE_TOKEN` *(optional)*: A PAT or GitHub App token so CI checks run automatically on Release PRs created by `release-please` (falls back to `GITHUB_TOKEN`).

Also enable **Allow GitHub Actions to create and approve pull requests** under `Settings` > `Actions` > `General` > `Workflow permissions`.

## License

This project is licensed under the terms of the [LICENSE](LICENSE) file.
