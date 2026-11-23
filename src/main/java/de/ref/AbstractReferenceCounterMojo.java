package de.ref;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared base class holding common configuration parameters, export handling, baseline ratchet checks,
 * and unused-dependency enforcement for {@link ReferenceCounterMojo} and {@link ReferenceCounterAggregateMojo}.
 */
abstract class AbstractReferenceCounterMojo extends AbstractMojo {

    /**
     * The Maven project being analyzed. This is injected by Maven.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    /**
     * Whether to include dependencies with zero detected references in the report output.
     */
    @Parameter(property = "refcount.showUnused", defaultValue = "true")
    protected boolean showUnused = true;

    /**
     * Whether to analyze transitive dependencies in addition to directly declared dependencies.
     */
    @Parameter(property = "refcount.includeTransitive", defaultValue = "true")
    protected boolean includeTransitive = true;

    /**
     * Whether to also analyze compiled test classes ({@code target/test-classes}) and include
     * {@code test}-scoped dependencies. Requires test classes to be compiled (e.g. {@code mvn test-compile}).
     */
    @Parameter(property = "refcount.includeTestClasses", defaultValue = "false")
    protected boolean includeTestClasses = false;

    /**
     * Optional list of dependency exclusion patterns. Supported formats:
     * {@code groupId:artifactId}, {@code groupId:artifactId:version}, {@code groupId:*}, {@code *:artifactId}, or {@code *}.
     */
    @Parameter(property = "refcount.excludes")
    protected List<String> excludes = Collections.emptyList();

    /**
     * Whether to fail the build if any analyzed dependency has zero detected references.
     */
    @Parameter(property = "refcount.failOnUnused", defaultValue = "false")
    protected boolean failOnUnused = false;

    /**
     * Whether to print a per-class breakdown showing which project classes reference which dependency classes.
     */
    @Parameter(property = "refcount.showDetails", defaultValue = "false")
    protected boolean showDetails = false;

    /**
     * Optional threshold (&gt; 0) to highlight lightly used dependencies as refactoring candidates
     * and print their class-level usage details automatically.
     */
    @Parameter(property = "refcount.lowUsageThreshold", defaultValue = "0")
    protected int lowUsageThreshold = 0;

    /**
     * Output format for the analysis report. Supported values: {@code console}, {@code json}, {@code csv},
     * {@code markdown} (or {@code md}). Console output is always logged; non-console formats additionally
     * write a report file.
     */
    @Parameter(property = "refcount.outputFormat", defaultValue = "console")
    protected String outputFormat = "console";

    /**
     * Destination file for exported reports. Defaults to {@code target/ref-count-report.json},
     * {@code target/ref-count-report.csv}, or {@code target/ref-count-report.md} depending on
     * {@code outputFormat}. If set while {@code outputFormat} is {@code console}, JSON format is used.
     */
    @Parameter(property = "refcount.outputFile")
    protected File outputFile;

    /**
     * Optional baseline JSON file mapping {@code groupId:artifactId} to maximum allowed reference counts
     * for migration ratchet checks.
     */
    @Parameter(property = "refcount.baselineFile")
    protected File baselineFile;

    /**
     * When {@code true}, writes or overwrites {@code baselineFile} (or {@code ref-count-baseline.json} in the
     * project base directory) with the current reference counts instead of verifying against it.
     */
    @Parameter(property = "refcount.updateBaseline", defaultValue = "false")
    protected boolean updateBaseline = false;

    /**
     * Whether to fail the build if any dependency tracked in {@code baselineFile} increased its reference count.
     */
    @Parameter(property = "refcount.failOnIncrease", defaultValue = "true")
    protected boolean failOnIncrease = true;

    /**
     * Whether to skip execution of this plugin.
     */
    @Parameter(property = "refcount.skip", defaultValue = "false")
    protected boolean skip = false;

    protected AnalysisResult lastResult;

    protected void handleExport(AnalysisResult result, ReportExporter.Options options, String label) throws IOException {
        String format = (outputFormat == null) ? "console" : outputFormat.trim().toLowerCase(Locale.ROOT);
        if ("console".equals(format) && outputFile == null) {
            return;
        }
        String effectiveFormat = "console".equals(format) ? "json" : format;
        Path targetFile;
        if (outputFile != null) {
            targetFile = outputFile.toPath();
        } else {
            String buildDir = (project != null && project.getBuild() != null && project.getBuild().getDirectory() != null)
                    ? project.getBuild().getDirectory()
                    : "target";
            targetFile = Path.of(buildDir, ReportExporter.defaultFileNameForFormat(effectiveFormat));
        }
        ReportExporter.exportToFile(result, effectiveFormat, targetFile, options);
        getLog().info(label + " (" + effectiveFormat + ") written to: " + targetFile);
    }

    protected void handleBaseline(AnalysisResult result) throws IOException, MojoExecutionException {
        Path resolvedBaseline = resolveBaselinePath();
        if (resolvedBaseline == null) {
            return;
        }

        if (updateBaseline) {
            BaselineManager.writeBaseline(resolvedBaseline, result.getStats());
            getLog().info("Updated reference count baseline at: " + resolvedBaseline);
            return;
        }

        if (!Files.exists(resolvedBaseline)) {
            getLog().warn("Baseline file not found: " + resolvedBaseline + ". Run with -Drefcount.updateBaseline=true to create it.");
            return;
        }

        Map<String, Integer> baseline = BaselineManager.readBaseline(resolvedBaseline);
        BaselineManager.Evaluation evaluation = BaselineManager.evaluate(baseline, result.getStats());

        for (BaselineManager.Delta improvement : evaluation.improvements()) {
            getLog().info(String.format("Baseline improved: %s (%d -> %d references)",
                    improvement.dependencyKey(), improvement.baselineCount(), improvement.currentCount()));
        }

        if (evaluation.hasRegressions()) {
            StringBuilder details = new StringBuilder();
            for (BaselineManager.Delta regression : evaluation.regressions()) {
                String msg = String.format("%s (%d -> %d)",
                        regression.dependencyKey(), regression.baselineCount(), regression.currentCount());
                getLog().warn("Baseline regression: " + msg);
                if (!details.isEmpty()) {
                    details.append(", ");
                }
                details.append(msg);
            }
            if (failOnIncrease) {
                throw new MojoExecutionException("Reference count increased above baseline for: " + details);
            }
        }
    }

    private Path resolveBaselinePath() {
        if (baselineFile != null) {
            return baselineFile.toPath();
        }
        if (updateBaseline) {
            File baseDir = (project != null && project.getBasedir() != null) ? project.getBasedir() : new File(".");
            return baseDir.toPath().resolve("ref-count-baseline.json");
        }
        return null;
    }

    protected void checkFailOnUnused(AnalysisResult result) throws MojoExecutionException {
        if (!failOnUnused) {
            return;
        }
        List<String> unused = result.getStats().stream()
                .filter(stat -> stat.getTotalReferences() == 0)
                .map(ArtifactUsageStats::getCoordinates)
                .sorted()
                .toList();

        if (!unused.isEmpty()) {
            throw new MojoExecutionException("Found " + unused.size() + " unused dependency/dependencies: " + String.join(", ", unused));
        }
    }

    AnalysisResult getLastResult() {
        return lastResult;
    }

    // Package-private setters for testing
    void setProject(MavenProject project) {
        this.project = project;
    }

    void setShowUnused(boolean showUnused) {
        this.showUnused = showUnused;
    }

    void setIncludeTransitive(boolean includeTransitive) {
        this.includeTransitive = includeTransitive;
    }

    void setIncludeTestClasses(boolean includeTestClasses) {
        this.includeTestClasses = includeTestClasses;
    }

    void setExcludes(List<String> excludes) {
        this.excludes = excludes;
    }

    void setFailOnUnused(boolean failOnUnused) {
        this.failOnUnused = failOnUnused;
    }

    void setShowDetails(boolean showDetails) {
        this.showDetails = showDetails;
    }

    void setLowUsageThreshold(int lowUsageThreshold) {
        this.lowUsageThreshold = lowUsageThreshold;
    }

    void setOutputFormat(String outputFormat) {
        this.outputFormat = outputFormat;
    }

    void setOutputFile(File outputFile) {
        this.outputFile = outputFile;
    }

    void setBaselineFile(File baselineFile) {
        this.baselineFile = baselineFile;
    }

    void setUpdateBaseline(boolean updateBaseline) {
        this.updateBaseline = updateBaseline;
    }

    void setFailOnIncrease(boolean failOnIncrease) {
        this.failOnIncrease = failOnIncrease;
    }

    void setSkip(boolean skip) {
        this.skip = skip;
    }
}
