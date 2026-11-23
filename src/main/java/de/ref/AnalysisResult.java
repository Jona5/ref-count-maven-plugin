package de.ref;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable container for the outcome of a reference counting analysis run.
 */
final class AnalysisResult {
    private final String projectCoordinates;
    private final List<ArtifactUsageStats> stats;
    private final Map<String, Set<String>> splitPackages;
    private final int analyzedClassCount;

    AnalysisResult(
            String projectCoordinates,
            List<ArtifactUsageStats> stats,
            Map<String, Set<String>> splitPackages,
            int analyzedClassCount
    ) {
        this.projectCoordinates = projectCoordinates;
        this.stats = List.copyOf(stats);
        this.splitPackages = Collections.unmodifiableMap(splitPackages);
        this.analyzedClassCount = analyzedClassCount;
    }

    String getProjectCoordinates() {
        return projectCoordinates;
    }

    List<ArtifactUsageStats> getStats() {
        return stats;
    }

    Map<String, Set<String>> getSplitPackages() {
        return splitPackages;
    }

    int getAnalyzedClassCount() {
        return analyzedClassCount;
    }
}
