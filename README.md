# Reference Counting Maven Plugin

This Maven plugin analyzes your project's bytecode to count the number of references to classes from its declared dependencies. This helps identify which dependencies are actively used and how frequently.

## Motivation

Over time, Maven projects can accumulate a large number of dependencies. Some of these may have been added for a single, specific feature and are now rarely used, while others might have been replaced by newer libraries but were never removed from the `pom.xml`. This leads to "dependency cruft," which increases build times, enlarges artifact sizes, and expands the potential surface for security vulnerabilities.

While tools like `mvn dependency:analyze` are excellent for finding *unused* declared dependencies, they don't provide insight into *how heavily* a dependency is being used. This plugin aims to fill that gap by answering the question: "Which of our declared dependencies are we actually using, and how often?"

The output helps you make informed decisions about:
- Identifying core vs. peripheral dependencies.
- Refactoring code to remove dependencies that are only lightly used.
- Justifying the removal of a dependency that is no longer referenced at all.

## Requirements

- **Java 17+**: This plugin is built for Java 17. You must run Maven using a JDK of version 17 or higher.

## How to Use

To use the plugin, add it to the `<build>` section of your `pom.xml`:

```xml
<plugin>
    <groupId>io.github.jona5</groupId>
    <artifactId>ref-count-maven-plugin</artifactId>
    <version>{{project.version}}</version>
</plugin>
```

Then, run the plugin from your terminal:

```bash
mvn ref-count:count-references
```

The plugin will run and print a summary of the dependency usage to the console.

### Sample Output

```
[INFO] --- Reference Analysis Results ---
[INFO]   org.apache.commons:commons-lang3:3.12.0 -> 42 references
[INFO]   com.google.guava:guava:31.1-jre -> 15 references
[INFO] -------------------------------------
```

## How It Works

The plugin works in two main steps:

1.  **Dependency Indexing**: It first scans all the JAR files of your project's dependencies and builds an in-memory map of every class to its source artifact (e.g., `org.apache.commons.lang3.StringUtils` -> `commons-lang3.jar`).
2.  **Bytecode Analysis**: It then uses the [ASM library](https://asm.ow2.io/) to inspect the bytecode of your project's own compiled classes (`target/classes`). It looks for method calls, field accesses, and type instructions that reference classes from the dependency index.

Finally, it aggregates the counts and prints a report, sorted by the most-used artifacts.

## Building from Source

To build the plugin from source, clone the repository and run:

```bash
mvn clean install
```

## Testing

This project uses the `maven-invoker-plugin` to run integration tests. The tests are located in the `src/it` directory.

To run the integration tests, execute the following command:

```bash
mvn clean verify -Dgpg.skip=true
```

This command activates the `run-its` profile, which builds the plugin, runs it against a sample project, and verifies the output.

## CI

A GitHub Actions workflow is set up in `.github/workflows/build.yml`. It automatically builds and tests the project on every push and pull request to the `main` branch.

## License

This project is licensed under the terms of the LICENSE file.
