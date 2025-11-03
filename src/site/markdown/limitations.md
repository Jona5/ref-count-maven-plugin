# Known Limitations (Caveats)

As a static analysis tool, this plugin has certain limitations:

- **Reflection**: References created dynamically at runtime via `Class.forName()` or other reflection mechanisms cannot be detected. A library might be reported as unused even if it is essential for the application to function.
- **Runtime Frameworks**: Frameworks that rely heavily on proxies or runtime code generation (e.g., Spring, Hibernate) can obscure the true usage of dependencies. The analysis of the bytecode in `target/classes` may not provide a complete picture in such cases.
- **Transitive Dependencies**: The current version analyzes all dependencies, including transitive ones. This can make the report verbose. A future enhancement could focus the analysis on directly declared dependencies.
