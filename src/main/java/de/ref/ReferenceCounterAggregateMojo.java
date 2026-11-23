package de.ref;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aggregator goal that analyzes dependency bytecode references across all modules in a multi-module
 * Maven reactor and produces a consolidated summary report with per-module usage metrics.
 */
@Mojo(
        name = "aggregate",
        defaultPhase = LifecyclePhase.VERIFY,
        aggregator = true,
        requiresDependencyResolution = ResolutionScope.TEST,
        threadSafe = true
)
public class ReferenceCounterAggregateMojo extends AbstractReferenceCounterMojo {

    /**
     * The list of Maven projects in the current reactor build. Injected by Maven.
     */
    @Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
    private List<MavenProject> reactorProjects;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping aggregated reference analysis (refcount.skip=true).");
            return;
        }

        if (reactorProjects == null || reactorProjects.isEmpty()) {
            getLog().warn("No reactor projects found to aggregate.");
            return;
        }

        getLog().info("Starting aggregated reference analysis across " + reactorProjects.size() + " module(s)...");

        try {
            ReferenceAnalyzer analyzer = new ReferenceAnalyzer();
            ReferenceAnalyzer.Config config = new ReferenceAnalyzer.Config(includeTransitive, includeTestClasses, excludes);

            Map<String, ArtifactUsageStats> aggregatedByCoords = new LinkedHashMap<>();
            Map<String, Set<String>> aggregatedSplitPackages = new TreeMap<>();
            int totalAnalyzedClasses = 0;

            for (MavenProject module : reactorProjects) {
                if ("pom".equalsIgnoreCase(module.getPackaging())) {
                    continue;
                }
                getLog().info("Analyzing module: " + module.getArtifactId());
                Map<String, Artifact> moduleClassMap = new HashMap<>();
                Map<Artifact, Integer> moduleCounts = new ConcurrentHashMap<>();
                AnalysisResult moduleResult = analyzer.analyze(module, config, getLog(), moduleClassMap, moduleCounts);

                totalAnalyzedClasses += moduleResult.getAnalyzedClassCount();
                moduleResult.getSplitPackages().forEach((className, owners) ->
                        aggregatedSplitPackages.computeIfAbsent(className, k -> new TreeSet<>()).addAll(owners));

                for (ArtifactUsageStats moduleStat : moduleResult.getStats()) {
                    String coords = moduleStat.getCoordinates();
                    ArtifactUsageStats target = aggregatedByCoords.computeIfAbsent(
                            coords,
                            k -> new ArtifactUsageStats(moduleStat.getArtifact(), moduleStat.isDirect(), moduleStat.getSizeBytes())
                    );
                    target.mergeFrom(moduleStat, module.getArtifactId());
                }
            }

            List<ArtifactUsageStats> sortedStats = aggregatedByCoords.values().stream()
                    .sorted(Comparator.comparingInt(ArtifactUsageStats::getTotalReferences).reversed()
                            .thenComparing(ArtifactUsageStats::getCoordinates))
                    .toList();

            AnalysisResult aggregatedResult = new AnalysisResult(
                    ReferenceAnalyzer.formatProjectCoordinates(project),
                    sortedStats,
                    aggregatedSplitPackages,
                    totalAnalyzedClasses
            );
            this.lastResult = aggregatedResult;

            ReportExporter.Options reportOptions = new ReportExporter.Options(showUnused, showDetails, lowUsageThreshold, true);
            ReportExporter.printConsoleReport(aggregatedResult, reportOptions, getLog());

            handleExport(aggregatedResult, reportOptions, "Aggregated reference count report");
            handleBaseline(aggregatedResult);
            checkFailOnUnused(aggregatedResult);
        } catch (IOException | IllegalArgumentException e) {
            throw new MojoExecutionException("Error during aggregated reference analysis: " + e.getMessage(), e);
        }
    }

    void setReactorProjects(List<MavenProject> reactorProjects) {
        this.reactorProjects = reactorProjects;
    }
}
