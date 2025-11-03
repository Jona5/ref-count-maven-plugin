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

## Testing

This project uses the `maven-invoker-plugin` to run integration tests. The tests are located in the `src/it` directory.

To run the integration tests, execute the following command:

```bash
mvn verify -Prun-its
```

This command activates the `run-its` profile, which builds the plugin, runs it against a sample project, and verifies the output.

## Releasing a New Version

This project uses GitHub Actions to automate the release process. Any maintainer with write access to the repository can trigger a release.

### Prerequisites (One-Time Setup)

The repository owner must configure the following secrets in `Settings` > `Secrets and variables` > `Actions`:

- `SONATYPE_USERNAME`: Username for Sonatype OSSRH (Maven Central).
- `SONATYPE_TOKEN`: Password or API token for Sonatype OSSRH.
- `GPG_PRIVATE_KEY`: The private GPG key in ASCII-armored format.
- `GPG_PASSPHRASE`: The passphrase for the GPG key.

### Release Steps

1.  **Update Version**: Manually update the version in `pom.xml` from `x.y.z-SNAPSHOT` to the final release version `x.y.z`.

2.  **Commit Changes**: Commit the version change with a clear message.

    ```bash
    git commit -am "Release version x.y.z"
    ```

3.  **Create Git Tag**: Create a new Git tag for the release. The tag must start with `v`.

    ```bash
    git tag -a vx.y.z -m "Version x.y.z"
    ```

4.  **Push to GitHub**: Push the commit and the new tag to the repository.

    ```bash
    git push && git push --tags
    ```

Once the tag is pushed, the `release.yml` workflow will automatically start. It will build the project, sign the artifacts, and publish them to Maven Central.

## CI

A GitHub Actions workflow is set up in `.github/workflows/build.yml`. It automatically builds and tests the project on every push and pull request to the `main` branch.

## License

This project is licensed under the terms of the LICENSE file.
