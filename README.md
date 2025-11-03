# Reference Counting Maven Plugin

This Maven plugin analyzes your project's bytecode to count the number of references to classes from its declared dependencies. This helps identify which dependencies are actively used and how frequently.

For detailed documentation, including usage instructions, known limitations, and the project roadmap, please visit the **[full project website](https://jona5.github.io/ref-count-maven-plugin/)**.

## Quick Start

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

## Building from Source

To build the plugin from source, clone the repository and run:

```bash
mvn clean install
```

## License

This project is licensed under the terms of the LICENSE file.
