# How to Use

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
