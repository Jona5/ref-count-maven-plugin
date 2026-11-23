package de.ref;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.objectweb.asm.ClassReader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Core bytecode analysis engine shared by {@link ReferenceCounterMojo} and {@link ReferenceCounterAggregateMojo}.
 */
final class ReferenceAnalyzer {

    private static final Pattern MULTI_RELEASE_PREFIX = Pattern.compile("^META-INF/versions/\\d+/");
    private static final String CLASS_SUFFIX = ".class";

    record Config(boolean includeTransitive, boolean includeTestClasses, List<String> excludes) {
        Config {
            excludes = excludes == null ? Collections.emptyList() : List.copyOf(excludes);
        }
    }

    AnalysisResult analyze(
            MavenProject project,
            Config config,
            Log log,
            Map<String, Artifact> classToArtifactMap,
            Map<Artifact, Integer> usageCounts
    ) throws IOException {
        classToArtifactMap.clear();
        usageCounts.clear();

        Collection<Artifact> artifacts = selectArtifacts(project, config);
        Set<String> directKeys = collectDirectArtifactKeys(project);

        Map<Artifact, ArtifactUsageStats> statsMap = new ConcurrentHashMap<>();
        for (Artifact artifact : artifacts) {
            usageCounts.put(artifact, 0);
            boolean direct = directKeys.contains(directKey(artifact));
            long sizeBytes = computeArtifactSize(artifact.getFile());
            statsMap.put(artifact, new ArtifactUsageStats(artifact, direct, sizeBytes));
        }

        Map<String, Set<String>> splitPackages = buildClassToArtifactMap(artifacts, classToArtifactMap, log);
        int analyzedClasses = analyzeProjectClasses(project, config.includeTestClasses(), classToArtifactMap, usageCounts, statsMap, log);

        List<ArtifactUsageStats> sortedStats = statsMap.values().stream()
                .sorted(Comparator.comparingInt(ArtifactUsageStats::getTotalReferences).reversed()
                        .thenComparing(ArtifactUsageStats::getCoordinates))
                .toList();

        String projectCoords = formatProjectCoordinates(project);
        return new AnalysisResult(projectCoords, sortedStats, splitPackages, analyzedClasses);
    }

    Collection<Artifact> selectArtifacts(MavenProject project, Config config) {
        Set<Artifact> sourceArtifacts = config.includeTransitive()
                ? project.getArtifacts()
                : project.getDependencyArtifacts();
        if (sourceArtifacts == null || sourceArtifacts.isEmpty()) {
            return Collections.emptyList();
        }

        return sourceArtifacts.stream()
                .filter(artifact -> config.includeTestClasses() || !Artifact.SCOPE_TEST.equals(artifact.getScope()))
                .filter(artifact -> !isExcluded(artifact, config.excludes()))
                .collect(Collectors.toList());
    }

    static boolean isExcluded(Artifact artifact, List<String> excludes) {
        if (excludes == null || excludes.isEmpty()) {
            return false;
        }
        String groupId = artifact.getGroupId();
        String artifactId = artifact.getArtifactId();
        String ga = groupId + ":" + artifactId;
        String gav = ReferenceCounterMojo.formatArtifactName(artifact);

        for (String rawPattern : excludes) {
            if (rawPattern == null) {
                continue;
            }
            String pattern = rawPattern.trim();
            if (pattern.isEmpty()) {
                continue;
            }
            if ("*".equals(pattern)
                    || "*:*".equals(pattern)
                    || pattern.equals(ga)
                    || pattern.equals(gav)
                    || pattern.equals(groupId + ":*")
                    || pattern.equals("*:" + artifactId)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> collectDirectArtifactKeys(MavenProject project) {
        Set<Artifact> directArtifacts = project.getDependencyArtifacts();
        if (directArtifacts == null || directArtifacts.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> keys = new HashSet<>();
        for (Artifact artifact : directArtifacts) {
            keys.add(directKey(artifact));
        }
        return keys;
    }

    private static String directKey(Artifact artifact) {
        String classifier = artifact.getClassifier() != null ? artifact.getClassifier() : "";
        String type = artifact.getType() != null ? artifact.getType() : "jar";
        return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + type + ":" + classifier;
    }

    private Map<String, Set<String>> buildClassToArtifactMap(
            Collection<Artifact> artifacts,
            Map<String, Artifact> classToArtifactMap,
            Log log
    ) throws IOException {
        log.info("Analyzing dependencies...");
        Map<String, Set<String>> splitPackages = new TreeMap<>();

        for (Artifact artifact : artifacts) {
            File artifactFile = artifact.getFile();
            if (artifactFile == null || !artifactFile.exists()) {
                continue;
            }
            if (log.isDebugEnabled()) {
                log.debug("Indexing dependency: " + ReferenceCounterMojo.formatArtifactName(artifact));
            }
            if (artifactFile.isDirectory()) {
                indexDirectory(artifactFile.toPath(), artifact, classToArtifactMap, splitPackages, log);
            } else {
                indexJar(artifactFile, artifact, classToArtifactMap, splitPackages, log);
            }
        }

        log.info("Dependency index created with " + classToArtifactMap.size() + " classes.");
        return splitPackages;
    }

    private void indexJar(
            File jarFile,
            Artifact artifact,
            Map<String, Artifact> classToArtifactMap,
            Map<String, Set<String>> splitPackages,
            Log log
    ) throws IOException {
        try (var jar = new JarFile(jarFile)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    registerClassEntry(entry.getName(), artifact, classToArtifactMap, splitPackages, log);
                }
            }
        }
    }

    private void indexDirectory(
            Path classesDir,
            Artifact artifact,
            Map<String, Artifact> classToArtifactMap,
            Map<String, Set<String>> splitPackages,
            Log log
    ) throws IOException {
        try (var stream = Files.walk(classesDir)) {
            stream.filter(Files::isRegularFile)
                    .forEach(file -> {
                        String relativePath = classesDir.relativize(file).toString();
                        registerClassEntry(relativePath, artifact, classToArtifactMap, splitPackages, log);
                    });
        }
    }

    private void registerClassEntry(
            String entryName,
            Artifact artifact,
            Map<String, Artifact> classToArtifactMap,
            Map<String, Set<String>> splitPackages,
            Log log
    ) {
        String className = normalizeClassEntryName(entryName);
        if (className == null) {
            return;
        }
        Artifact existing = classToArtifactMap.putIfAbsent(className, artifact);
        if (existing != null && !existing.equals(artifact)) {
            String dotClassName = className.replace('/', '.');
            Set<String> owners = splitPackages.computeIfAbsent(dotClassName, k -> new TreeSet<>());
            owners.add(ReferenceCounterMojo.formatArtifactName(existing));
            owners.add(ReferenceCounterMojo.formatArtifactName(artifact));
        } else if (existing == null && log.isDebugEnabled()) {
            log.debug("  Mapped class: " + className + " -> " + artifact.getArtifactId());
        }
    }

    static String normalizeClassEntryName(String entryName) {
        if (entryName == null) {
            return null;
        }
        String normalized = entryName.replace('\\', '/');
        if (!normalized.endsWith(CLASS_SUFFIX)) {
            return null;
        }
        normalized = MULTI_RELEASE_PREFIX.matcher(normalized).replaceFirst("");
        if (normalized.equals("module-info.class")
                || normalized.endsWith("/module-info.class")
                || normalized.equals("package-info.class")
                || normalized.endsWith("/package-info.class")) {
            return null;
        }
        return normalized.substring(0, normalized.length() - CLASS_SUFFIX.length());
    }

    private int analyzeProjectClasses(
            MavenProject project,
            boolean includeTestClasses,
            Map<String, Artifact> classToArtifactMap,
            Map<Artifact, Integer> usageCounts,
            Map<Artifact, ArtifactUsageStats> statsMap,
            Log log
    ) throws IOException {
        List<Path> classFiles = new ArrayList<>();
        if (project.getBuild() != null && project.getBuild().getOutputDirectory() != null) {
            collectClassFiles(Path.of(project.getBuild().getOutputDirectory()), true, classFiles, log);
        }
        if (includeTestClasses && project.getBuild() != null && project.getBuild().getTestOutputDirectory() != null) {
            collectClassFiles(Path.of(project.getBuild().getTestOutputDirectory()), false, classFiles, log);
        }

        // classToArtifactMap is read-only from here; usageCounts and statsMap are thread-safe.
        Map<String, Artifact> readOnlyClassMap = Collections.unmodifiableMap(new HashMap<>(classToArtifactMap));
        classFiles.parallelStream().forEach(classFile -> analyzeClassFile(classFile, readOnlyClassMap, usageCounts, statsMap, log));
        return classFiles.size();
    }

    private void collectClassFiles(Path classesDir, boolean warnIfMissing, List<Path> target, Log log) throws IOException {
        if (!Files.exists(classesDir)) {
            if (warnIfMissing) {
                log.warn("No '" + classesDir + "' directory found. Have you run 'mvn compile'?");
            }
            return;
        }
        try (var stream = Files.walk(classesDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(CLASS_SUFFIX))
                    .forEach(target::add);
        }
    }

    static void analyzeClassFile(
            Path classFile,
            Map<String, Artifact> classToArtifactMap,
            Map<Artifact, Integer> usageCounts,
            Map<Artifact, ArtifactUsageStats> statsMap,
            Log log
    ) {
        if (log.isDebugEnabled()) {
            log.debug("Analyzing project class file: " + classFile);
        }
        try (var is = Files.newInputStream(classFile)) {
            var reader = new ClassReader(is);
            var visitor = new ReferenceClassVisitor(classToArtifactMap, usageCounts, statsMap, log);
            reader.accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException e) {
            log.warn("Could not analyze class file: " + classFile + " (" + e.getMessage() + ")", e);
        }
    }

    static long computeArtifactSize(File file) {
        if (file == null || !file.exists()) {
            return -1L;
        }
        if (file.isFile()) {
            return file.length();
        }
        try (var stream = Files.walk(file.toPath())) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            return -1L;
        }
    }

    static String formatProjectCoordinates(MavenProject project) {
        if (project == null || project.getGroupId() == null) {
            return "unknown";
        }
        return String.format("%s:%s:%s", project.getGroupId(), project.getArtifactId(), project.getVersion());
    }
}
