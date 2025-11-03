# Welcome to the Reference Count Maven Plugin

This Maven plugin analyzes your project's bytecode to count the number of references to classes from its declared dependencies. This helps identify which dependencies are actively used and how frequently.

## Motivation

Over time, Maven projects can accumulate a large number of dependencies. Some of these may have been added for a single, specific feature and are now rarely used, while others might have been replaced by newer libraries but were never removed from the `pom.xml`. This leads to "dependency cruft," which increases build times, enlarges artifact sizes, and expands the potential surface for security vulnerabilities.

While tools like `mvn dependency:analyze` are excellent for finding *unused* declared dependencies, they don't provide insight into *how heavily* a dependency is being used. This plugin aims to fill that gap by answering the question: "Which of our declared dependencies are we actually using, and how often?"

The output helps you make informed decisions about:
- Identifying core vs. peripheral dependencies.
- Refactoring code to remove dependencies that are only lightly used.
- Justifying the removal of a dependency that is no longer referenced at all.

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

### Sample Output

```
[INFO] --- Reference Analysis Results ---
[INFO]   org.apache.commons:commons-lang3:3.12.0 -> 42 references
[INFO]   com.google.guava:guava:31.1-jre -> 15 references
[INFO] -------------------------------------
```
