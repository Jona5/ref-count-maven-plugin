package de.ref;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.objectweb.asm.ClassReader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

/**
 * A Maven plugin that analyzes the project's bytecode to count references to classes from its declared dependencies.
 * This helps in identifying which dependencies are actively used and how frequently.
 */
@Mojo(name = "count-references", defaultPhase = LifecyclePhase.VERIFY, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
public class ReferenceCounterMojo extends AbstractMojo {

    /**
     * The Maven project being analyzed. This is injected by Maven.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * A map where the key is the internal class name (e.g., {@code org/apache/commons/lang3/StringUtils})
     * and the value is the {@link Artifact} it belongs to.
     */
    final Map<String, Artifact> classToArtifactMap = new HashMap<>();

    /**
     * A map to store the usage count of each dependency artifact. The key is the {@link Artifact} and the value
     * is the number of references found in the project's bytecode.
     * This map is thread-safe to allow for parallel analysis in the future.
     */
    final Map<Artifact, Integer> usageCounts = new ConcurrentHashMap<>();

    /**
     * Main execution method of the Mojo. It orchestrates the entire analysis process.
     *
     * @throws MojoExecutionException if an error occurs during the analysis.
     */
    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("Starting reference analysis...");

        try {
            // Step 1: Build an index of all classes from all dependency JARs.
            buildClassToArtifactMap();

            // Step 2: Initialize usage counters for all artifacts.
            for (var artifact : project.getArtifacts()) {
                usageCounts.put(artifact, 0);
            }

            // Step 3: Analyze the project's own compiled classes.
            analyzeProjectClasses();

            // Step 4: Print the results to the console.
            printResults();

        } catch (IOException e) {
            throw new MojoExecutionException("Error analyzing class files.", e);
        }
    }

    /**
     * Scans all dependency JARs and builds a map from internal class names to their source {@link Artifact}.
     * This map serves as a database for the analysis.
     *
     * @throws IOException if an error occurs while reading a JAR file.
     */
    void buildClassToArtifactMap() throws IOException {
        getLog().info("Analyzing dependencies...");
        var artifacts = project.getArtifacts();

        for (var artifact : artifacts) {
            File jarFile = artifact.getFile();
            if (jarFile != null && jarFile.exists() && !jarFile.isDirectory()) {
                try (var jar = new JarFile(jarFile)) {
                    var entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        var entry = entries.nextElement();
                        if (entry.getName().endsWith(".class")) {
                            String className = entry.getName().replace(".class", "").replace(File.separatorChar, '/');
                            classToArtifactMap.put(className, artifact);
                        }
                    }
                }
            }
        }
        getLog().info("Dependency index created with " + classToArtifactMap.size() + " classes.");
    }

    /**
     * Walks through the project's output directory (usually {@code target/classes}) and analyzes each
     * {@code .class} file found.
     *
     * @throws IOException if an error occurs while walking the directory tree.
     */
    void analyzeProjectClasses() throws IOException {
        Path classesDir = new File(project.getBuild().getOutputDirectory()).toPath();
        if (!Files.exists(classesDir)) {
            getLog().warn("No 'target/classes' directory found. Have you run 'mvn compile'?");
            return;
        }

        try (var stream = Files.walk(classesDir)) {
            stream.filter(path -> path.toString().endsWith(".class")).forEach(this::analyzeClassFile);
        }
    }

    /**
     * Analyzes a single {@code .class} file using the ASM library.
     *
     * @param classFile The path to the class file to analyze.
     */
    void analyzeClassFile(Path classFile) {
        try (var is = Files.newInputStream(classFile)) {
            var reader = new ClassReader(is);
            var visitor = new ReferenceClassVisitor(classToArtifactMap, usageCounts);
            reader.accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (IOException e) {
            getLog().warn("Could not read class file: " + classFile, e);
        }
    }

    /**
     * Prints the final analysis results to the Maven log.
     * It lists all used dependencies, sorted by the number of references in descending order.
     */
    void printResults() {
        getLog().info("--- Reference Analysis Results ---");
        usageCounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0) // Only show used libs
                .sorted(Map.Entry.<Artifact, Integer>comparingByValue().reversed()) // Sort by usage
                .forEach(entry -> {
                    Artifact artifact = entry.getKey();
                    String artifactName = String.format("%s:%s:%s", artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
                    getLog().info(String.format("  %s -> %d references", artifactName, entry.getValue()));
                });
        getLog().info("-------------------------------------");
    }

    // --- INNER CLASSES FOR ASM ANALYSIS ---
}
