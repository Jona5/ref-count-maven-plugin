package de.ref;

import org.apache.maven.plugin.logging.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Formats and exports {@link AnalysisResult} to console logs, JSON, CSV, or GitHub-Flavored Markdown.
 */
final class ReportExporter {

    private static final int MAX_SPLIT_PACKAGE_WARNINGS = 10;

    record Options(boolean showUnused, boolean showDetails, int lowUsageThreshold, boolean aggregated) {
    }

    static void printConsoleReport(AnalysisResult result, Options options, Log log) {
        log.info("--- Reference Analysis Results ---");
        List<ArtifactUsageStats> visible = filterVisible(result.getStats(), options.showUnused());

        for (ArtifactUsageStats stat : visible) {
            int count = stat.getTotalReferences();
            String unit = (count == 1) ? "reference" : "references";
            String metadata = buildConsoleMetadata(stat, options);
            log.info(String.format("  %s -> %d %s %s", stat.getCoordinates(), count, unit, metadata));

            boolean printDetailForItem = options.showDetails()
                    || (options.lowUsageThreshold() > 0 && count > 0 && count <= options.lowUsageThreshold());
            if (printDetailForItem && count > 0) {
                stat.getProjectToDependencyClasses().forEach((projectClass, depClasses) ->
                        log.info("      " + projectClass + " -> " + String.join(", ", depClasses)));
            }
        }
        log.info("-------------------------------------");

        logSplitPackageWarnings(result.getSplitPackages(), log);
    }

    private static String buildConsoleMetadata(ArtifactUsageStats stat, Options options) {
        StringBuilder sb = new StringBuilder("[");
        int count = stat.getTotalReferences();
        if (stat.isDirect()) {
            sb.append("direct");
        } else {
            sb.append(count > 0 ? "transitive - undeclared!" : "transitive");
        }
        if (stat.getSizeBytes() >= 0) {
            sb.append(", ").append(ArtifactUsageStats.formatSize(stat.getSizeBytes()));
        }
        if (count > 0) {
            int depClasses = stat.getReferencedDependencyClassCount();
            int projClasses = stat.getReferencingProjectClassCount();
            sb.append(", ")
                    .append(depClasses).append(depClasses == 1 ? " dep class" : " dep classes")
                    .append(" in ")
                    .append(projClasses).append(projClasses == 1 ? " project class" : " project classes");
            if (options.aggregated() && !stat.getReferencingModules().isEmpty()) {
                int modules = stat.getReferencingModules().size();
                sb.append(" across ").append(modules).append(modules == 1 ? " module" : " modules");
            }
            if (options.lowUsageThreshold() > 0 && count <= options.lowUsageThreshold()) {
                sb.append(", low usage");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private static void logSplitPackageWarnings(Map<String, Set<String>> splitPackages, Log log) {
        if (splitPackages.isEmpty()) {
            return;
        }
        log.warn("Detected " + splitPackages.size() + " duplicate class name(s) across multiple dependencies (split packages / shading):");
        int shown = 0;
        for (Map.Entry<String, Set<String>> entry : splitPackages.entrySet()) {
            if (shown++ >= MAX_SPLIT_PACKAGE_WARNINGS) {
                log.warn("  ... and " + (splitPackages.size() - MAX_SPLIT_PACKAGE_WARNINGS) + " more (run with -X for details).");
                break;
            }
            log.warn("  " + entry.getKey() + " -> " + String.join(", ", entry.getValue()));
        }
    }

    static void exportToFile(AnalysisResult result, String format, Path outputFile, Options options) throws IOException {
        String normalizedFormat = format == null ? "console" : format.trim().toLowerCase(Locale.ROOT);
        String content = switch (normalizedFormat) {
            case "json" -> toJson(result, options);
            case "csv" -> toCsv(result, options);
            case "markdown", "md" -> toMarkdown(result, options);
            default -> throw new IllegalArgumentException(
                    "Unsupported outputFormat '" + format + "'. Supported values: console, json, csv, markdown.");
        };

        Path parent = outputFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(outputFile, content, StandardCharsets.UTF_8);
    }

    static String defaultFileNameForFormat(String format) {
        String normalized = format.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "json" -> "ref-count-report.json";
            case "csv" -> "ref-count-report.csv";
            case "markdown", "md" -> "ref-count-report.md";
            default -> "ref-count-report.txt";
        };
    }

    static String toJson(AnalysisResult result, Options options) {
        List<ArtifactUsageStats> visible = filterVisible(result.getStats(), options.showUnused());
        long usedCount = result.getStats().stream().filter(s -> s.getTotalReferences() > 0).count();
        long unusedCount = result.getStats().size() - usedCount;
        long usedTransitiveCount = result.getStats().stream().filter(s -> !s.isDirect() && s.getTotalReferences() > 0).count();

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"project\": \"").append(escapeJson(result.getProjectCoordinates())).append("\",\n");
        sb.append("  \"summary\": {\n");
        sb.append("    \"analyzedClasses\": ").append(result.getAnalyzedClassCount()).append(",\n");
        sb.append("    \"totalDependencies\": ").append(result.getStats().size()).append(",\n");
        sb.append("    \"usedDependencies\": ").append(usedCount).append(",\n");
        sb.append("    \"unusedDependencies\": ").append(unusedCount).append(",\n");
        sb.append("    \"usedTransitiveDependencies\": ").append(usedTransitiveCount).append(",\n");
        sb.append("    \"duplicateClassConflicts\": ").append(result.getSplitPackages().size()).append("\n");
        sb.append("  },\n");
        sb.append("  \"dependencies\": [\n");

        for (int i = 0; i < visible.size(); i++) {
            ArtifactUsageStats stat = visible.get(i);
            sb.append("    {\n");
            sb.append("      \"groupId\": \"").append(escapeJson(stat.getArtifact().getGroupId())).append("\",\n");
            sb.append("      \"artifactId\": \"").append(escapeJson(stat.getArtifact().getArtifactId())).append("\",\n");
            sb.append("      \"version\": \"").append(escapeJson(stat.getArtifact().getVersion())).append("\",\n");
            sb.append("      \"scope\": \"").append(escapeJson(stat.getScope())).append("\",\n");
            sb.append("      \"direct\": ").append(stat.isDirect()).append(",\n");
            sb.append("      \"sizeBytes\": ").append(stat.getSizeBytes()).append(",\n");
            sb.append("      \"references\": ").append(stat.getTotalReferences()).append(",\n");
            sb.append("      \"referencedDependencyClasses\": ").append(stat.getReferencedDependencyClassCount()).append(",\n");
            sb.append("      \"referencingProjectClasses\": ").append(stat.getReferencingProjectClassCount());

            if (options.aggregated()) {
                sb.append(",\n      \"modules\": [");
                int m = 0;
                Set<String> modules = stat.getReferencingModules();
                for (String module : modules) {
                    if (m++ > 0) sb.append(", ");
                    sb.append("\"").append(escapeJson(module)).append("\"");
                }
                sb.append("]");
            }

            if (options.showDetails()) {
                sb.append(",\n      \"details\": {");
                Map<String, Set<String>> details = stat.getProjectToDependencyClasses();
                if (!details.isEmpty()) {
                    sb.append("\n");
                    int d = 0;
                    for (Map.Entry<String, Set<String>> entry : details.entrySet()) {
                        sb.append("        \"").append(escapeJson(entry.getKey())).append("\": [");
                        int c = 0;
                        for (String depClass : entry.getValue()) {
                            if (c++ > 0) sb.append(", ");
                            sb.append("\"").append(escapeJson(depClass)).append("\"");
                        }
                        sb.append("]");
                        if (++d < details.size()) sb.append(",");
                        sb.append("\n");
                    }
                    sb.append("      }");
                } else {
                    sb.append("}");
                }
            }

            sb.append("\n    }");
            if (i + 1 < visible.size()) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    static String toCsv(AnalysisResult result, Options options) {
        List<ArtifactUsageStats> visible = filterVisible(result.getStats(), options.showUnused());
        StringBuilder sb = new StringBuilder();
        sb.append("groupId,artifactId,version,scope,direct,sizeBytes,references,referencedDependencyClasses,referencingProjectClasses");
        if (options.aggregated()) {
            sb.append(",modules");
        }
        sb.append("\n");

        for (ArtifactUsageStats stat : visible) {
            sb.append(escapeCsv(stat.getArtifact().getGroupId())).append(',')
                    .append(escapeCsv(stat.getArtifact().getArtifactId())).append(',')
                    .append(escapeCsv(stat.getArtifact().getVersion())).append(',')
                    .append(escapeCsv(stat.getScope())).append(',')
                    .append(stat.isDirect()).append(',')
                    .append(stat.getSizeBytes()).append(',')
                    .append(stat.getTotalReferences()).append(',')
                    .append(stat.getReferencedDependencyClassCount()).append(',')
                    .append(stat.getReferencingProjectClassCount());
            if (options.aggregated()) {
                sb.append(',').append(escapeCsv(String.join(";", stat.getReferencingModules())));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static String toMarkdown(AnalysisResult result, Options options) {
        List<ArtifactUsageStats> visible = filterVisible(result.getStats(), options.showUnused());
        StringBuilder sb = new StringBuilder();
        sb.append("## Reference Count Report (`").append(result.getProjectCoordinates()).append("`)\n\n");
        sb.append("| Dependency | Scope | Type | Size | References | Dep Classes | Project Classes | Status |\n");
        sb.append("|---|---|---|---:|---:|---:|---:|---|\n");

        for (ArtifactUsageStats stat : visible) {
            int count = stat.getTotalReferences();
            String type = stat.isDirect() ? "direct" : "transitive";
            String status = determineStatus(stat, options.lowUsageThreshold());
            sb.append("| `").append(stat.getCoordinates()).append("` | ")
                    .append(stat.getScope()).append(" | ")
                    .append(type).append(" | ")
                    .append(ArtifactUsageStats.formatSize(stat.getSizeBytes())).append(" | ")
                    .append(count).append(" | ")
                    .append(stat.getReferencedDependencyClassCount()).append(" | ")
                    .append(stat.getReferencingProjectClassCount()).append(" | ")
                    .append(status).append(" |\n");
        }

        if (options.showDetails()) {
            boolean headerWritten = false;
            for (ArtifactUsageStats stat : visible) {
                if (stat.getTotalReferences() == 0) {
                    continue;
                }
                if (!headerWritten) {
                    sb.append("\n### Reference Details\n");
                    headerWritten = true;
                }
                sb.append("\n#### `").append(stat.getCoordinates()).append("`\n");
                stat.getProjectToDependencyClasses().forEach((projectClass, depClasses) ->
                        sb.append("- `").append(projectClass).append("` -> `")
                                .append(String.join("`, `", depClasses)).append("`\n"));
            }
        }
        return sb.toString();
    }

    private static String determineStatus(ArtifactUsageStats stat, int lowUsageThreshold) {
        int count = stat.getTotalReferences();
        if (count == 0) {
            return "unused";
        }
        if (!stat.isDirect()) {
            return "undeclared transitive";
        }
        if (lowUsageThreshold > 0 && count <= lowUsageThreshold) {
            return "low usage";
        }
        return "used";
    }

    private static List<ArtifactUsageStats> filterVisible(List<ArtifactUsageStats> stats, boolean showUnused) {
        return stats.stream()
                .filter(stat -> showUnused || stat.getTotalReferences() > 0)
                .toList();
    }

    static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
