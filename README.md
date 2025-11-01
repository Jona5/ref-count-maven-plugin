# Reference Counting Maven Plugin

This Maven plugin analyzes your project's bytecode to count the number of references to classes from its declared dependencies. This helps identify which dependencies are actively used and how frequently.

## Requirements

- **Java 17+**: This plugin is built for Java 17. You must run Maven using a JDK of version 17 or higher.

## How to Use

To use the plugin, add it to the `<build>` section of your `pom.xml`:

```xml
<plugin>
    <groupId>de.refcount</groupId>
    <artifactId>ref-count</artifactId>
    <version>1.0-SNAPSHOT</version>
</plugin>
```

Then, run the plugin from your terminal:

```bash
mvn ref-count:count-references
```

The plugin will run during the `verify` phase and print a summary of the dependency usage to the console.

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

## CI

A GitHub Actions workflow is set up in `.github/workflows/build.yml`. It automatically builds and tests the project on every push and pull request to the `main` branch.

## License

This project is licensed under the terms of the LICENSE file.
