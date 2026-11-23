# Known Limitations (Caveats)

As a static bytecode analysis tool, this plugin has certain limitations:

- **Compilation Prerequisite**: The plugin inspects compiled `.class` files in `target/classes` (and `target/test-classes` when `includeTestClasses=true`). Always run `mvn compile` (or `mvn test-compile` when analyzing tests) before invoking `ref-count:count-references` or `ref-count:aggregate`.
- **Reflection**: References created dynamically at runtime via `Class.forName()` or other reflection mechanisms cannot be detected. A library might be reported as unused even if it is essential at runtime.
- **Runtime Frameworks & ServiceLoaders**: Frameworks relying on runtime proxies, SPI / `ServiceLoader` discovery, or runtime-only dependencies (e.g., JDBC drivers, SLF4J bindings) may report `0` bytecode references. Use `<excludes>` to ignore known runtime-only dependencies.
- **Compile-Time Constant Inlining**: Primitive constants and `String` constants (`static final`) are inlined by `javac` into the calling bytecode and leave no class reference to the declaring dependency.
- **Source-Retention Annotations**: Annotations with `@Retention(RetentionPolicy.SOURCE)` (such as Lombok annotations) are discarded by `javac` and do not appear in compiled `.class` files.
- **Split Packages & Shaded JARs**: If two dependencies contain the exact same class name, the plugin logs a split-package warning and attributes references to the first indexed dependency.
