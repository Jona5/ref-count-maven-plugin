package de.ref;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages baseline JSON files for migration ratchet checks: tracks reference counts per
 * {@code groupId:artifactId} so teams can prevent dependency usage from increasing over time.
 */
final class BaselineManager {

    private static final Pattern ENTRY_PATTERN = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\\d+)");

    record Delta(String dependencyKey, int baselineCount, int currentCount) {
    }

    record Evaluation(List<Delta> regressions, List<Delta> improvements) {
        boolean hasRegressions() {
            return !regressions.isEmpty();
        }
    }

    static void writeBaseline(Path baselineFile, List<ArtifactUsageStats> stats) throws IOException {
        Map<String, Integer> countsByGa = new TreeMap<>();
        for (ArtifactUsageStats stat : stats) {
            countsByGa.merge(stat.getGroupArtifactKey(), stat.getTotalReferences(), Integer::sum);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        int index = 0;
        int size = countsByGa.size();
        for (Map.Entry<String, Integer> entry : countsByGa.entrySet()) {
            sb.append("  \"").append(ReportExporter.escapeJson(entry.getKey())).append("\": ").append(entry.getValue());
            if (++index < size) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("}\n");

        Path parent = baselineFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(baselineFile, sb.toString(), StandardCharsets.UTF_8);
    }

    static Map<String, Integer> readBaseline(Path baselineFile) throws IOException {
        String content = Files.readString(baselineFile, StandardCharsets.UTF_8);
        Map<String, Integer> result = new TreeMap<>();
        Matcher matcher = ENTRY_PATTERN.matcher(content);
        while (matcher.find()) {
            String key = matcher.group(1);
            int value = Integer.parseInt(matcher.group(2));
            result.put(key, value);
        }
        return result;
    }

    static Evaluation evaluate(Map<String, Integer> baseline, List<ArtifactUsageStats> currentStats) {
        Map<String, Integer> currentByGa = new TreeMap<>();
        for (ArtifactUsageStats stat : currentStats) {
            currentByGa.merge(stat.getGroupArtifactKey(), stat.getTotalReferences(), Integer::sum);
        }

        List<Delta> regressions = new ArrayList<>();
        List<Delta> improvements = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : baseline.entrySet()) {
            String key = entry.getKey();
            int baselineCount = entry.getValue();
            int currentCount = currentByGa.getOrDefault(key, 0);
            if (currentCount > baselineCount) {
                regressions.add(new Delta(key, baselineCount, currentCount));
            } else if (currentCount < baselineCount) {
                improvements.add(new Delta(key, baselineCount, currentCount));
            }
        }

        return new Evaluation(List.copyOf(regressions), List.copyOf(improvements));
    }
}
