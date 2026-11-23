package de.ref;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Analyzes the project's compiled bytecode to count references to classes from its dependencies.
 * Provides usage depth metrics (dependency classes used, referencing project classes, JAR size),
 * direct vs. transitive classification, machine-readable exports (JSON, CSV, Markdown),
 * split-package detection, and baseline ratchet checks for dependency migrations.
 */
@Mojo(
        name = "count-references",
        defaultPhase = LifecyclePhase.VERIFY,
        requiresDependencyResolution = ResolutionScope.TEST,
        threadSafe = true
)
public class ReferenceCounterMojo extends AbstractReferenceCounterMojo {

    /**
     * Map from internal class name to its source {@link Artifact}.
     */
    final Map<String, Artifact> classToArtifactMap = new HashMap<>();

    /**
     * Map from {@link Artifact} to total reference count found in project bytecode.
     */
    final Map<Artifact, Integer> usageCounts = new ConcurrentHashMap<>();

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping reference analysis (refcount.skip=true).");
            return;
        }

        getLog().info("Starting reference analysis...");

        try {
            ReferenceAnalyzer analyzer = new ReferenceAnalyzer();
            ReferenceAnalyzer.Config config = new ReferenceAnalyzer.Config(includeTransitive, includeTestClasses, excludes);
            AnalysisResult result = analyzer.analyze(project, config, getLog(), classToArtifactMap, usageCounts);
            this.lastResult = result;

            ReportExporter.Options reportOptions = new ReportExporter.Options(showUnused, showDetails, lowUsageThreshold, false);
            ReportExporter.printConsoleReport(result, reportOptions, getLog());

            handleExport(result, reportOptions, "Reference count report");
            handleBaseline(result);
            checkFailOnUnused(result);
        } catch (IOException | IllegalArgumentException e) {
            throw new MojoExecutionException("Error analyzing class files: " + e.getMessage(), e);
        }
    }

    static String normalizeClassEntryName(String entryName) {
        return ReferenceAnalyzer.normalizeClassEntryName(entryName);
    }

    static String formatArtifactName(Artifact artifact) {
        return String.format("%s:%s:%s", artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
    }
}
