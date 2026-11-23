package de.ref;

import org.apache.maven.artifact.Artifact;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe usage statistics collected for a single dependency artifact.
 */
final class ArtifactUsageStats {
    private final Artifact artifact;
    private boolean direct;
    private final long sizeBytes;
    private final AtomicInteger totalReferences = new AtomicInteger(0);
    private final Set<String> referencedDependencyClasses = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Set<String>> projectToDependencyClasses = new ConcurrentHashMap<>();
    private final Set<String> referencingModules = ConcurrentHashMap.newKeySet();

    ArtifactUsageStats(Artifact artifact, boolean direct, long sizeBytes) {
        this.artifact = artifact;
        this.direct = direct;
        this.sizeBytes = sizeBytes;
    }

    void recordReference(String projectClassName, String dependencyClassName) {
        totalReferences.incrementAndGet();
        if (dependencyClassName != null && !dependencyClassName.isEmpty()) {
            referencedDependencyClasses.add(dependencyClassName);
            String sourceClass = (projectClassName == null || projectClassName.isEmpty()) ? "<unknown>" : projectClassName;
            projectToDependencyClasses
                    .computeIfAbsent(sourceClass, k -> ConcurrentHashMap.newKeySet())
                    .add(dependencyClassName);
        }
    }

    void mergeFrom(ArtifactUsageStats other, String moduleName) {
        if (other.direct) {
            this.direct = true;
        }
        int otherRefs = other.getTotalReferences();
        if (otherRefs > 0) {
            totalReferences.addAndGet(otherRefs);
            referencedDependencyClasses.addAll(other.referencedDependencyClasses);
            if (moduleName != null && !moduleName.isEmpty()) {
                referencingModules.add(moduleName);
            }
            other.projectToDependencyClasses.forEach((projectClass, depClasses) -> {
                String qualifiedProjectClass = (moduleName == null || moduleName.isEmpty())
                        ? projectClass
                        : moduleName + ":" + projectClass;
                projectToDependencyClasses
                        .computeIfAbsent(qualifiedProjectClass, k -> ConcurrentHashMap.newKeySet())
                        .addAll(depClasses);
            });
        }
    }

    Artifact getArtifact() {
        return artifact;
    }

    boolean isDirect() {
        return direct;
    }

    long getSizeBytes() {
        return sizeBytes;
    }

    int getTotalReferences() {
        return totalReferences.get();
    }

    int getReferencedDependencyClassCount() {
        return referencedDependencyClasses.size();
    }

    int getReferencingProjectClassCount() {
        return projectToDependencyClasses.size();
    }

    Set<String> getReferencedDependencyClasses() {
        return Collections.unmodifiableSet(new TreeSet<>(referencedDependencyClasses));
    }

    Map<String, Set<String>> getProjectToDependencyClasses() {
        Map<String, Set<String>> sorted = new TreeMap<>();
        projectToDependencyClasses.forEach((projectClass, depClasses) -> sorted.put(projectClass, new TreeSet<>(depClasses)));
        return Collections.unmodifiableMap(sorted);
    }

    Set<String> getReferencingModules() {
        return Collections.unmodifiableSet(new TreeSet<>(referencingModules));
    }

    String getCoordinates() {
        return String.format("%s:%s:%s", artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
    }

    String getGroupArtifactKey() {
        return artifact.getGroupId() + ":" + artifact.getArtifactId();
    }

    String getScope() {
        return artifact.getScope() != null ? artifact.getScope() : "compile";
    }

    static String formatSize(long bytes) {
        if (bytes < 0) {
            return "unknown";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            return String.format(java.util.Locale.ROOT, "%.0f KB", kb);
        }
        double mb = kb / 1024.0;
        return String.format(java.util.Locale.ROOT, "%.1f MB", mb);
    }
}
