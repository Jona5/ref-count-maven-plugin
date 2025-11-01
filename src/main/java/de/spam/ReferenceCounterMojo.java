package de.spam;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

/**
 * Counts the references in the bytecode to classes from declared dependencies.
 */
@Mojo(name = "count-references", defaultPhase = LifecyclePhase.VERIFY, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
public class ReferenceCounterMojo extends AbstractMojo {

    /**
     * The Maven project being analyzed.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    // Map<Class name (org/foo/Bar), Artifact (commons-lang3.jar)>
    final Map<String, Artifact> classToArtifactMap = new HashMap<>();

    // Map<Artifact (commons-lang3.jar), Counter>
    final Map<Artifact, Integer> usageCounts = new ConcurrentHashMap<>();

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("Starting reference analysis...");

        try {
            // Step 1: Create the "database" of all classes from all JARs
            buildClassToArtifactMap();

            // Step 2: Initialize the counters
            for (var artifact : project.getArtifacts()) {
                usageCounts.put(artifact, 0);
            }

            // Step 3: Analyze the project's own bytecode
            analyzeProjectClasses();

            // Step 4: Output the results
            printResults();

        } catch (IOException e) {
            throw new MojoExecutionException("Error analyzing class files.", e);
        }
    }

    /**
     * Builds a map that maps classes to their source JARs.
     */
    void buildClassToArtifactMap() throws IOException {
        getLog().info("Analyzing dependencies...");
        var artifacts = project.getArtifacts();

        for (var artifact : artifacts) {
            var jarFile = artifact.getFile();
            if (jarFile != null && jarFile.exists() && !jarFile.isDirectory()) {
                try (var jar = new JarFile(jarFile)) {
                    var entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        var entry = entries.nextElement();
                        if (entry.getName().endsWith(".class")) {
                            // Converts "org/apache/commons/lang3/StringUtils.class"
                            // to "org/apache/commons/lang3/StringUtils"
                            var className = entry.getName().replace(".class", "").replace(File.separatorChar, '/');
                            classToArtifactMap.put(className, artifact);
                        }
                    }
                }
            }
        }
        getLog().info("Dependency index created with " + classToArtifactMap.size() + " classes.");
    }

    /**
     * Goes through the 'target/classes' directory and analyzes each .class file.
     */
    void analyzeProjectClasses() throws IOException {
        var classesDir = new File(project.getBuild().getOutputDirectory()).toPath();
        if (!Files.exists(classesDir)) {
            getLog().warn("No 'target/classes' directory found. Did you run 'mvn compile'?");
            return;
        }

        try (var stream = Files.walk(classesDir)) {
            stream.filter(path -> path.toString().endsWith(".class")).forEach(this::analyzeClassFile);
        }
    }

    /**
     * Uses ASM to analyze a single .class file.
     */
    void analyzeClassFile(Path classFile) {
        try (var is = Files.newInputStream(classFile)) {
            var reader = new ClassReader(is);
            // We pass our maps to the visitor so it can increment the counters
            var visitor = new ReferenceClassVisitor(classToArtifactMap, usageCounts);
            reader.accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (IOException e) {
            getLog().warn("Could not read class: " + classFile, e);
        }
    }

    /**
     * Prints the counted references to the console.
     */
    void printResults() {
        getLog().info("--- Reference Analysis Results ---");
        usageCounts.entrySet().stream().filter(entry -> entry.getValue() > 0) // Only show used libs
                .sorted(Map.Entry.<Artifact, Integer>comparingByValue().reversed()) // Sort by usage
                .forEach(entry -> {
                    var artifact = entry.getKey();
                    var artifactName = String.format("%s:%s:%s", artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
                    getLog().info(String.format("  %s -> %d references", artifactName, entry.getValue()));
                });
        getLog().info("-------------------------------------");
    }

    // --- INNER CLASSES FOR ASM ANALYSIS ---

    /**
     * This visitor is the entry point for a class.
     * It delegates the analysis of method bodies to the MethodVisitor.
     */
    static class ReferenceClassVisitor extends ClassVisitor {
        private final Map<String, Artifact> classMap;
        private final Map<Artifact, Integer> counts;

        public ReferenceClassVisitor(Map<String, Artifact> classMap, Map<Artifact, Integer> counts) {
            super(Opcodes.ASM9);
            this.classMap = classMap;
            this.counts = counts;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            var mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            // Each method gets its own visitor
            return new ReferenceMethodVisitor(mv, classMap, counts);
        }

        // Fields, superclasses, etc. could also be analyzed here,
        // but the MethodVisitor catches most of it.
    }

    /**
     * This visitor looks at the *content* of a method (the bytecode).
     */
    static class ReferenceMethodVisitor extends MethodVisitor {
        private final Map<String, Artifact> classMap;
        private final Map<Artifact, Integer> counts;

        public ReferenceMethodVisitor(MethodVisitor mv, Map<String, Artifact> classMap, Map<Artifact, Integer> counts) {
            super(Opcodes.ASM9, mv);
            this.classMap = classMap;
            this.counts = counts;
        }

        /**
         * Called when a method is invoked.
         * e.g. StringUtils.isEmpty(...)
         */
        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            countReference(owner); // e.g. "org/apache/commons/lang3/StringUtils"
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        /**
         * Called when a field is accessed.
         * e.g. System.out
         */
        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            countReference(owner); // e.g. "java/lang/System"
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        /**
         * Called for type operations (e.g. NEW, ANEWARRAY, INSTANCEOF).
         */
        @Override
        public void visitTypeInsn(int opcode, String type) {
            countReference(type); // e.g. "org/example/MyObject"
            super.visitTypeInsn(opcode, type);
        }

        /**
         * The core logic: Checks if a class belongs to a dependency
         * and increments the counter.
         */
        void countReference(String internalClassName) {
            // Ignore/simplify arrays (e.g. [Ljava/lang/String;)
            if (internalClassName == null || internalClassName.startsWith("[")) {
                return;
            }

            var artifact = classMap.get(internalClassName);
            if (artifact != null) {
                // Atomically increment the counter
                counts.compute(artifact, (key, value) -> (value == null) ? 1 : value + 1);
            }
        }
    }
}