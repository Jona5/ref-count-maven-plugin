package de.ref;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceCounterMojoTest {

    private Map<String, Artifact> classToArtifactMap;
    private Map<Artifact, Integer> usageCounts;
    private ReferenceMethodVisitor methodVisitor;

    private Artifact commonsLangArtifact;
    private Artifact guavaArtifact;

    @BeforeEach
    void setUp() {
        commonsLangArtifact = createArtifact("org.apache.commons", "commons-lang3", "3.12.0", "compile");
        guavaArtifact = createArtifact("com.google.guava", "guava", "31.1-jre", "compile");

        classToArtifactMap = new HashMap<>();
        classToArtifactMap.put("org/apache/commons/lang3/StringUtils", commonsLangArtifact);
        classToArtifactMap.put("org/apache/commons/lang3/CustomException", commonsLangArtifact);
        classToArtifactMap.put("com/google/common/collect/Lists", guavaArtifact);
        classToArtifactMap.put("com/google/common/collect/Lists$InnerList", guavaArtifact);
        classToArtifactMap.put("com/google/common/annotations/Beta", guavaArtifact);
        classToArtifactMap.put("com/google/common/base/BaseHelper", guavaArtifact);
        classToArtifactMap.put("com/google/common/base/HelperInterface", guavaArtifact);

        usageCounts = new HashMap<>();
        usageCounts.put(commonsLangArtifact, 0);
        usageCounts.put(guavaArtifact, 0);

        methodVisitor = new ReferenceMethodVisitor(null, classToArtifactMap, usageCounts, new SystemStreamLog());
    }

    private static Artifact createArtifact(String groupId, String artifactId, String version, String scope) {
        return new DefaultArtifact(groupId, artifactId, version, scope, "jar", "", new DefaultArtifactHandler());
    }

    @Test
    @DisplayName("Method call to a mapped class should increment its artifact count")
    void testVisitMethodInsn_incrementsCount_forMappedClass() {
        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "org/apache/commons/lang3/StringUtils", "isEmpty", "(Ljava/lang/CharSequence;)Z", false);

        assertEquals(1, usageCounts.get(commonsLangArtifact));
        assertEquals(0, usageCounts.get(guavaArtifact));
    }

    @Test
    @DisplayName("Field access to a mapped class should increment its artifact count")
    void testVisitFieldInsn_incrementsCount_forMappedClass() {
        methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, "com/google/common/collect/Lists", "SOME_FIELD", "Ljava/lang/String;");

        assertEquals(1, usageCounts.get(guavaArtifact));
        assertEquals(0, usageCounts.get(commonsLangArtifact));
    }

    @Test
    @DisplayName("Type instruction for a mapped class and array types should increment its artifact count")
    void testVisitTypeInsn_incrementsCount_forMappedClassAndArrays() {
        methodVisitor.visitTypeInsn(Opcodes.NEW, "org/apache/commons/lang3/StringUtils");
        methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "[Lorg/apache/commons/lang3/StringUtils;");
        methodVisitor.visitMultiANewArrayInsn("[[Lcom/google/common/collect/Lists;", 2);

        assertEquals(2, usageCounts.get(commonsLangArtifact));
        assertEquals(1, usageCounts.get(guavaArtifact));
    }

    @Test
    @DisplayName("Class literal (LDC), try-catch exception, and invokedynamic method reference should be counted")
    void testAdvancedMethodInstructions() {
        methodVisitor.visitLdcInsn(Type.getObjectType("org/apache/commons/lang3/StringUtils"));
        methodVisitor.visitTryCatchBlock(new Label(), new Label(), new Label(), "org/apache/commons/lang3/CustomException");
        Handle bsm = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory", "()V", false);
        Handle target = new Handle(Opcodes.H_INVOKESTATIC, "com/google/common/collect/Lists", "newArrayList", "()Ljava/util/ArrayList;", false);
        methodVisitor.visitInvokeDynamicInsn("get", "()Ljava/util/function/Supplier;", bsm, target);

        assertEquals(2, usageCounts.get(commonsLangArtifact));
        assertEquals(1, usageCounts.get(guavaArtifact));
    }

    @Test
    @DisplayName("Reference to an unknown or Java stdlib class should not increment any count")
    void testUnknownAndStdLibClasses_doNotIncrement() {
        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "org/some/other/library/MyClass", "doSomething", "()V", false);
        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);

        assertTrue(usageCounts.values().stream().allMatch(count -> count == 0));
    }

    @Test
    @DisplayName("Multiple references should increment the count multiple times")
    void testMultipleReferences_incrementCorrectly() {
        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "org/apache/commons/lang3/StringUtils", "isEmpty", "()Z", false);
        methodVisitor.visitTypeInsn(Opcodes.NEW, "org/apache/commons/lang3/StringUtils");
        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "com/google/common/collect/Lists", "newArrayList", "()Ljava/util/ArrayList;", false);

        assertEquals(2, usageCounts.get(commonsLangArtifact));
        assertEquals(1, usageCounts.get(guavaArtifact));
    }

    @Test
    @DisplayName("ReferenceClassVisitor analyzes inheritance, annotations, fields, and method signatures from real bytecode")
    void testClassVisitor_analyzesBytecodeDeclarations() {
        byte[] bytecode = generateSampleClassBytecode();

        ClassReader reader = new ClassReader(bytecode);
        ReferenceClassVisitor classVisitor = new ReferenceClassVisitor(classToArtifactMap, usageCounts, new SystemStreamLog());
        reader.accept(classVisitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        // Guava: superclass (1) + interface (1) + class annotation (1) + field annotation (1) + method annotation (1) = 5
        assertEquals(5, usageCounts.get(guavaArtifact));
        // Commons Lang: field type (1) + annotation class value (1) + method param (1) + method return (1) + throws (1) = 5
        assertEquals(5, usageCounts.get(commonsLangArtifact));
    }

    @Test
    @DisplayName("ReferenceClassVisitor analyzes generic type arguments and nested inner classes via SignatureVisitor")
    void testClassVisitor_analyzesGenericSignatures() {
        ClassWriter cw = new ClassWriter(0);
        // class GenericHolder extends Object implements Comparable<StringUtils>
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                "com/example/GenericHolder",
                "Ljava/lang/Object;Ljava/lang/Comparable<Lorg/apache/commons/lang3/StringUtils;>;",
                "java/lang/Object",
                new String[]{"java/lang/Comparable"}
        );
        // private Map<Lists<String>.InnerList, StringUtils> entries;
        cw.visitField(
                Opcodes.ACC_PRIVATE,
                "entries",
                "Ljava/util/Map;",
                "Ljava/util/Map<Lcom/google/common/collect/Lists<Ljava/lang/String;>.InnerList;Lorg/apache/commons/lang3/StringUtils;>;",
                null
        ).visitEnd();

        // public Optional<StringUtils> find(List<BaseHelper> input)
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC,
                "find",
                "(Ljava/util/List;)Ljava/util/Optional;",
                "(Ljava/util/List<Lcom/google/common/base/BaseHelper;>;)Ljava/util/Optional<Lorg/apache/commons/lang3/StringUtils;>;",
                null
        );
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 2);
        mv.visitEnd();
        cw.visitEnd();

        ClassReader reader = new ClassReader(cw.toByteArray());
        ReferenceClassVisitor visitor = new ReferenceClassVisitor(classToArtifactMap, usageCounts, new SystemStreamLog());
        reader.accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        // commons-lang3: Comparable<StringUtils> (1) + Map value StringUtils (1) + Optional<StringUtils> (1) = 3
        assertEquals(3, usageCounts.get(commonsLangArtifact));
        // guava: Lists (1) + Lists$InnerList (1) + List<BaseHelper> (1) = 3
        assertEquals(3, usageCounts.get(guavaArtifact));
    }

    @Test
    @DisplayName("normalizeClassEntryName strips Multi-Release prefixes and ignores module/package-info")
    void testNormalizeClassEntryName() {
        assertEquals("com/example/Foo", ReferenceCounterMojo.normalizeClassEntryName("com/example/Foo.class"));
        assertEquals("com/example/Foo", ReferenceCounterMojo.normalizeClassEntryName("META-INF/versions/17/com/example/Foo.class"));
        assertEquals("com/example/Foo", ReferenceCounterMojo.normalizeClassEntryName("com\\example\\Foo.class"));
        assertNull(ReferenceCounterMojo.normalizeClassEntryName("module-info.class"));
        assertNull(ReferenceCounterMojo.normalizeClassEntryName("META-INF/versions/11/module-info.class"));
        assertNull(ReferenceCounterMojo.normalizeClassEntryName("com/example/package-info.class"));
        assertNull(ReferenceCounterMojo.normalizeClassEntryName("META-INF/MANIFEST.MF"));
    }

    @Test
    @DisplayName("Mojo indexes JARs (including Multi-Release) and reactor directories and tracks depth metrics end-to-end")
    void testMojoExecute_jarAndDirectoryArtifacts(@TempDir Path tempDir) throws Exception {
        // 1. Create a Multi-Release dependency JAR
        Path mrJar = tempDir.resolve("commons-lang3.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(mrJar))) {
            jos.putNextEntry(new JarEntry("META-INF/versions/17/org/apache/commons/lang3/StringUtils.class"));
            jos.write(new byte[64]);
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("module-info.class"));
            jos.write(new byte[0]);
            jos.closeEntry();
        }
        commonsLangArtifact.setFile(mrJar.toFile());

        // 2. Create a reactor sibling directory artifact (unused)
        Path siblingDir = tempDir.resolve("sibling-classes");
        Path siblingClass = siblingDir.resolve("com/example/Sibling.class");
        Files.createDirectories(siblingClass.getParent());
        Files.write(siblingClass, new byte[32]);
        Artifact siblingArtifact = createArtifact("com.example", "sibling-module", "1.0.0", "compile");
        siblingArtifact.setFile(siblingDir.toFile());

        // 3. Create project classes directory with compiled bytecode referencing StringUtils
        Path projectClassesDir = tempDir.resolve("target/classes");
        Files.createDirectories(projectClassesDir.resolve("com/example"));
        Files.write(projectClassesDir.resolve("com/example/Sample.class"), generateSimpleInvokerBytecode("com/example/Sample", "org/apache/commons/lang3/StringUtils"));
        // Also write a corrupt class file to verify resilience
        Files.write(projectClassesDir.resolve("com/example/Corrupt.class"), new byte[]{1, 2, 3});

        MavenProject project = createMavenProject("com.example", "demo", "1.0.0", tempDir, projectClassesDir, null,
                Set.of(commonsLangArtifact, siblingArtifact), Set.of(commonsLangArtifact));

        ReferenceCounterMojo mojo = new ReferenceCounterMojo();
        mojo.setProject(project);
        mojo.setLog(new SystemStreamLog());
        mojo.setShowDetails(true);
        mojo.setLowUsageThreshold(2);
        mojo.execute();

        assertEquals(1, mojo.usageCounts.get(commonsLangArtifact));
        assertEquals(0, mojo.usageCounts.get(siblingArtifact));
        assertTrue(mojo.classToArtifactMap.containsKey("com/example/Sibling"));
        assertFalse(mojo.classToArtifactMap.containsKey("module-info"));

        AnalysisResult result = mojo.getLastResult();
        assertNotNull(result);
        ArtifactUsageStats langStats = result.getStats().stream()
                .filter(s -> s.getArtifact().equals(commonsLangArtifact))
                .findFirst().orElseThrow();
        assertTrue(langStats.isDirect());
        assertTrue(langStats.getSizeBytes() > 0);
        assertEquals(1, langStats.getReferencedDependencyClassCount());
        assertEquals(1, langStats.getReferencingProjectClassCount());
        assertEquals(Set.of("org.apache.commons.lang3.StringUtils"),
                langStats.getProjectToDependencyClasses().get("com.example.Sample"));

        ArtifactUsageStats siblingStats = result.getStats().stream()
                .filter(s -> s.getArtifact().equals(siblingArtifact))
                .findFirst().orElseThrow();
        assertFalse(siblingStats.isDirect());

        // Verify failOnUnused throws when siblingArtifact has 0 references
        mojo.setFailOnUnused(true);
        MojoExecutionException ex = assertThrows(MojoExecutionException.class, mojo::execute);
        assertTrue(ex.getMessage().contains("com.example:sibling-module:1.0.0"));

        // Excluding the unused sibling makes failOnUnused pass
        mojo.setExcludes(List.of("com.example:*"));
        mojo.execute();
        assertFalse(mojo.usageCounts.containsKey(siblingArtifact));

        // includeTransitive=false restricts to direct dependencies only
        mojo.setExcludes(List.of());
        mojo.setIncludeTransitive(false);
        mojo.execute();
        assertEquals(1, mojo.usageCounts.size());
        assertTrue(mojo.usageCounts.containsKey(commonsLangArtifact));
    }

    @Test
    @DisplayName("Mojo detects split packages across duplicate classes in different dependencies")
    void testSplitPackageDetection(@TempDir Path tempDir) throws Exception {
        Path jar1 = createJarWithEntries(tempDir.resolve("lib-a.jar"), List.of("com/shared/Duplicate.class"));
        Path jar2 = createJarWithEntries(tempDir.resolve("lib-b.jar"), List.of("com/shared/Duplicate.class"));
        Artifact depA = createArtifact("com.example", "lib-a", "1.0.0", "compile");
        Artifact depB = createArtifact("com.example", "lib-b", "1.0.0", "compile");
        depA.setFile(jar1.toFile());
        depB.setFile(jar2.toFile());

        Path classesDir = tempDir.resolve("target/classes");
        Files.createDirectories(classesDir);

        MavenProject project = createMavenProject("com.example", "split-app", "1.0.0", tempDir, classesDir, null,
                Set.of(depA, depB), Set.of(depA, depB));

        ReferenceCounterMojo mojo = new ReferenceCounterMojo();
        mojo.setProject(project);
        mojo.setLog(new SystemStreamLog());
        mojo.execute();

        Map<String, Set<String>> splits = mojo.getLastResult().getSplitPackages();
        assertTrue(splits.containsKey("com.shared.Duplicate"));
        assertEquals(Set.of("com.example:lib-a:1.0.0", "com.example:lib-b:1.0.0"), splits.get("com.shared.Duplicate"));
    }

    @Test
    @DisplayName("ReportExporter writes valid JSON, CSV, and Markdown files")
    void testExportFormats(@TempDir Path tempDir) throws Exception {
        Path jar = createJarWithEntries(tempDir.resolve("commons.jar"), List.of("org/apache/commons/lang3/StringUtils.class"));
        commonsLangArtifact.setFile(jar.toFile());

        Path classesDir = tempDir.resolve("target/classes");
        Files.createDirectories(classesDir.resolve("com/example"));
        Files.write(classesDir.resolve("com/example/App.class"),
                generateSimpleInvokerBytecode("com/example/App", "org/apache/commons/lang3/StringUtils"));

        MavenProject project = createMavenProject("com.example", "export-app", "1.0.0", tempDir, classesDir, null,
                Set.of(commonsLangArtifact), Set.of(commonsLangArtifact));

        ReferenceCounterMojo mojo = new ReferenceCounterMojo();
        mojo.setProject(project);
        mojo.setLog(new SystemStreamLog());
        mojo.setShowDetails(true);

        // JSON
        Path jsonFile = tempDir.resolve("out/report.json");
        mojo.setOutputFormat("json");
        mojo.setOutputFile(jsonFile.toFile());
        mojo.execute();
        String json = Files.readString(jsonFile);
        assertTrue(json.contains("\"artifactId\": \"commons-lang3\""));
        assertTrue(json.contains("\"references\": 1"));
        assertTrue(json.contains("\"com.example.App\": [\"org.apache.commons.lang3.StringUtils\"]"));

        // CSV
        Path csvFile = tempDir.resolve("out/report.csv");
        mojo.setOutputFormat("csv");
        mojo.setOutputFile(csvFile.toFile());
        mojo.execute();
        String csv = Files.readString(csvFile);
        assertTrue(csv.startsWith("groupId,artifactId,version,scope,direct,sizeBytes,references,referencedDependencyClasses,referencingProjectClasses"));
        assertTrue(csv.contains("org.apache.commons,commons-lang3,3.12.0,compile,true,"));

        // Markdown
        Path mdFile = tempDir.resolve("out/report.md");
        mojo.setOutputFormat("markdown");
        mojo.setOutputFile(mdFile.toFile());
        mojo.execute();
        String md = Files.readString(mdFile);
        assertTrue(md.contains("## Reference Count Report (`com.example:export-app:1.0.0`)"));
        assertTrue(md.contains("| `org.apache.commons:commons-lang3:3.12.0` | compile | direct |"));
        assertTrue(md.contains("### Reference Details"));
    }

    @Test
    @DisplayName("Baseline ratchet writes baseline, allows equal/decreased counts, and fails on increase")
    void testBaselineRatchet(@TempDir Path tempDir) throws Exception {
        Path jar = createJarWithEntries(tempDir.resolve("commons.jar"), List.of("org/apache/commons/lang3/StringUtils.class"));
        commonsLangArtifact.setFile(jar.toFile());

        Path classesDir = tempDir.resolve("target/classes");
        Files.createDirectories(classesDir.resolve("com/example"));
        Files.write(classesDir.resolve("com/example/App.class"),
                generateSimpleInvokerBytecode("com/example/App", "org/apache/commons/lang3/StringUtils"));

        MavenProject project = createMavenProject("com.example", "ratchet-app", "1.0.0", tempDir, classesDir, null,
                Set.of(commonsLangArtifact), Set.of(commonsLangArtifact));
        Path baselinePath = tempDir.resolve("ref-count-baseline.json");

        // 1. Write baseline (count = 1)
        ReferenceCounterMojo mojo = new ReferenceCounterMojo();
        mojo.setProject(project);
        mojo.setLog(new SystemStreamLog());
        mojo.setBaselineFile(baselinePath.toFile());
        mojo.setUpdateBaseline(true);
        mojo.execute();
        assertTrue(Files.exists(baselinePath));
        assertTrue(Files.readString(baselinePath).contains("\"org.apache.commons:commons-lang3\": 1"));

        // 2. Verify with same count (passes)
        mojo.setUpdateBaseline(false);
        mojo.execute();

        // 3. Add a second class referencing StringUtils (count increases to 2 -> fails!)
        Files.write(classesDir.resolve("com/example/App2.class"),
                generateSimpleInvokerBytecode("com/example/App2", "org/apache/commons/lang3/StringUtils"));
        MojoExecutionException ex = assertThrows(MojoExecutionException.class, mojo::execute);
        assertTrue(ex.getMessage().contains("org.apache.commons:commons-lang3 (1 -> 2)"));

        // 4. With failOnIncrease=false it warns instead of failing
        mojo.setFailOnIncrease(false);
        mojo.execute();
    }

    @Test
    @DisplayName("Aggregate Mojo merges reference statistics across multiple reactor modules")
    void testAggregateMojo(@TempDir Path tempDir) throws Exception {
        Path jar = createJarWithEntries(tempDir.resolve("commons.jar"), List.of("org/apache/commons/lang3/StringUtils.class"));
        commonsLangArtifact.setFile(jar.toFile());

        Path modAClasses = tempDir.resolve("mod-a/target/classes");
        Files.createDirectories(modAClasses.resolve("com/example"));
        Files.write(modAClasses.resolve("com/example/ModA.class"),
                generateSimpleInvokerBytecode("com/example/ModA", "org/apache/commons/lang3/StringUtils"));

        Path modBClasses = tempDir.resolve("mod-b/target/classes");
        Files.createDirectories(modBClasses.resolve("com/example"));
        Files.write(modBClasses.resolve("com/example/ModB.class"),
                generateSimpleInvokerBytecode("com/example/ModB", "org/apache/commons/lang3/StringUtils"));

        MavenProject parent = createMavenProject("com.example", "parent", "1.0.0", tempDir, tempDir.resolve("target/classes"), null, Set.of(), Set.of());
        parent.setPackaging("pom");
        MavenProject modA = createMavenProject("com.example", "mod-a", "1.0.0", tempDir.resolve("mod-a"), modAClasses, null,
                Set.of(commonsLangArtifact), Set.of(commonsLangArtifact));
        MavenProject modB = createMavenProject("com.example", "mod-b", "1.0.0", tempDir.resolve("mod-b"), modBClasses, null,
                Set.of(commonsLangArtifact), Set.of(commonsLangArtifact));

        ReferenceCounterAggregateMojo aggregateMojo = new ReferenceCounterAggregateMojo();
        aggregateMojo.setProject(parent);
        aggregateMojo.setReactorProjects(List.of(parent, modA, modB));
        aggregateMojo.setLog(new SystemStreamLog());
        aggregateMojo.setShowDetails(true);
        Path mdOut = tempDir.resolve("target/agg-report.md");
        aggregateMojo.setOutputFormat("markdown");
        aggregateMojo.setOutputFile(mdOut.toFile());
        aggregateMojo.execute();

        AnalysisResult result = aggregateMojo.getLastResult();
        assertNotNull(result);
        assertEquals(1, result.getStats().size());
        ArtifactUsageStats stats = result.getStats().get(0);
        assertEquals(2, stats.getTotalReferences());
        assertEquals(2, stats.getReferencingProjectClassCount());
        assertEquals(Set.of("mod-a", "mod-b"), stats.getReferencingModules());
        assertTrue(Files.exists(mdOut));
    }

    @Test
    @DisplayName("Mojo skip parameter skips execution")
    void testMojoSkip(@TempDir Path tempDir) throws Exception {
        MavenProject project = createMavenProject("com.example", "skip-app", "1.0.0", tempDir,
                tempDir.resolve("nonexistent"), null, Set.of(commonsLangArtifact), Set.of(commonsLangArtifact));
        ReferenceCounterMojo mojo = new ReferenceCounterMojo();
        mojo.setProject(project);
        mojo.setSkip(true);
        mojo.execute();

        assertTrue(mojo.usageCounts.isEmpty());
        assertTrue(mojo.classToArtifactMap.isEmpty());
    }

    @Test
    @DisplayName("Mojo respects includeTestClasses for test-scoped artifacts and test output directory")
    void testMojoIncludeTestClasses(@TempDir Path tempDir) throws Exception {
        Path testDepDir = tempDir.resolve("test-dep");
        Path depClass = testDepDir.resolve("org/testlib/AssertHelper.class");
        Files.createDirectories(depClass.getParent());
        Files.write(depClass, new byte[0]);
        Artifact testArtifact = createArtifact("org.testlib", "test-lib", "1.0.0", Artifact.SCOPE_TEST);
        testArtifact.setFile(testDepDir.toFile());

        Path mainClasses = tempDir.resolve("target/classes");
        Path testClasses = tempDir.resolve("target/test-classes");
        Files.createDirectories(mainClasses);
        Files.createDirectories(testClasses.resolve("com/example"));
        Files.write(testClasses.resolve("com/example/SampleTest.class"),
                generateSimpleInvokerBytecode("com/example/SampleTest", "org/testlib/AssertHelper"));

        MavenProject project = createMavenProject("com.example", "test-app", "1.0.0", tempDir, mainClasses, testClasses,
                Set.of(testArtifact), Set.of(testArtifact));

        ReferenceCounterMojo mojo = new ReferenceCounterMojo();
        mojo.setProject(project);
        mojo.setIncludeTestClasses(false);
        mojo.execute();
        assertFalse(mojo.usageCounts.containsKey(testArtifact));

        mojo.setIncludeTestClasses(true);
        mojo.execute();
        assertEquals(1, mojo.usageCounts.get(testArtifact));
    }

    private static Path createJarWithEntries(Path jarPath, List<String> entries) throws Exception {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            for (String entry : entries) {
                jos.putNextEntry(new JarEntry(entry));
                jos.write(new byte[16]);
                jos.closeEntry();
            }
        }
        return jarPath;
    }

    private static MavenProject createMavenProject(
            String groupId,
            String artifactId,
            String version,
            Path baseDir,
            Path classesDir,
            Path testClassesDir,
            Set<Artifact> allArtifacts,
            Set<Artifact> directArtifacts
    ) {
        MavenProject project = new MavenProject();
        project.setGroupId(groupId);
        project.setArtifactId(artifactId);
        project.setVersion(version);
        project.setFile(baseDir.resolve("pom.xml").toFile());
        Build build = new Build();
        build.setDirectory(baseDir.resolve("target").toString());
        build.setOutputDirectory(classesDir.toString());
        if (testClassesDir != null) {
            build.setTestOutputDirectory(testClassesDir.toString());
        }
        project.setBuild(build);
        project.setArtifacts(allArtifacts);
        project.setDependencyArtifacts(directArtifacts);
        return project;
    }

    private static byte[] generateSampleClassBytecode() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                "com/example/AnalyzedClass",
                null,
                "com/google/common/base/BaseHelper",
                new String[]{"com/google/common/base/HelperInterface"}
        );

        AnnotationVisitor classAnn = cw.visitAnnotation("Lcom/google/common/annotations/Beta;", true);
        classAnn.visit("target", Type.getObjectType("org/apache/commons/lang3/StringUtils"));
        classAnn.visitEnd();

        FieldVisitor fv = cw.visitField(Opcodes.ACC_PRIVATE, "utils", "Lorg/apache/commons/lang3/StringUtils;", null, null);
        fv.visitAnnotation("Lcom/google/common/annotations/Beta;", true).visitEnd();
        fv.visitEnd();

        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC,
                "process",
                "(Lorg/apache/commons/lang3/StringUtils;)Lorg/apache/commons/lang3/StringUtils;",
                null,
                new String[]{"org/apache/commons/lang3/CustomException"}
        );
        mv.visitAnnotation("Lcom/google/common/annotations/Beta;", true).visitEnd();
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 2);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateSimpleInvokerBytecode(String ownerInternalName, String targetInternalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, ownerInternalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, targetInternalName, "noop", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
