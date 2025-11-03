# Future Features (Roadmap)

We are considering the following enhancements for future versions:

- **Configurable Filters**: Allow users to exclude certain dependencies (e.g., logging frameworks, test libraries) from the analysis to create more focused reports.
- **Machine-Readable Reports**: In addition to logging to the console, provide an option to generate a report in a machine-readable format like JSON or XML for easier integration with other tools and dashboards.
- **Fail the Build**: Add an option to fail the build if a declared dependency has zero references, enabling a more proactive approach to managing dependency cruft.
- **Detailed Analysis**: Enhance the report to show *which classes* in your project are referencing a specific dependency, which would greatly simplify refactoring efforts.
