package de.ref;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceCounterMojoTest {

    private Map<Artifact, Integer> usageCounts;
    private ReferenceMethodVisitor methodVisitor;

    private Artifact commonsLangArtifact;
    private Artifact guavaArtifact;

    @BeforeEach
    void setUp() {
        // 1. Prepare the test data with real artifact objects
        commonsLangArtifact = new DefaultArtifact("org.apache.commons", "commons-lang3", "3.12.0", "compile", "jar", "", new DefaultArtifactHandler());
        guavaArtifact = new DefaultArtifact("com.google.guava", "guava", "31.1-jre", "compile", "jar", "", new DefaultArtifactHandler());

        Map<String, Artifact> classToArtifactMap = new HashMap<>();
        classToArtifactMap.put("org/apache/commons/lang3/StringUtils", commonsLangArtifact);
        classToArtifactMap.put("com/google/common/collect/Lists", guavaArtifact);

        usageCounts = new HashMap<>();
        usageCounts.put(commonsLangArtifact, 0);
        usageCounts.put(guavaArtifact, 0);

        // 2. Instantiate the class under test (the visitor)
        methodVisitor = new ReferenceMethodVisitor(null, classToArtifactMap, usageCounts);
    }

    @Test
    @DisplayName("Method call to a mapped class should increment its artifact count")
    void testVisitMethodInsn_incrementsCount_forMappedClass() {
        // 3. Trigger the action
        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "org/apache/commons/lang3/StringUtils", "isEmpty", "(Ljava/lang/CharSequence;)Z", false);

        // 4. Assert the result
        assertEquals(1, usageCounts.get(commonsLangArtifact));
        assertEquals(0, usageCounts.get(guavaArtifact)); // Ensure other counts are unaffected
    }

    @Test
    @DisplayName("Field access to a mapped class should increment its artifact count")
    void testVisitFieldInsn_incrementsCount_forMappedClass() {
        methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, "com/google/common/collect/Lists", "SOME_FIELD", "Ljava/lang/String;");

        assertEquals(1, usageCounts.get(guavaArtifact));
        assertEquals(0, usageCounts.get(commonsLangArtifact));
    }

    @Test
    @DisplayName("Type instruction for a mapped class should increment its artifact count")
    void testVisitTypeInsn_incrementsCount_forMappedClass() {
        methodVisitor.visitTypeInsn(Opcodes.NEW, "org/apache/commons/lang3/StringUtils");

        assertEquals(1, usageCounts.get(commonsLangArtifact));
        assertEquals(0, usageCounts.get(guavaArtifact));
    }

    @Test
    @DisplayName("Reference to an unknown class should not increment any count")
    void testVisitMethodInsn_doesNotIncrement_forUnknownClass() {
        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "org/some/other/library/MyClass", "doSomething", "()V", false);

        assertTrue(usageCounts.values().stream().allMatch(count -> count == 0));
    }

    @Test
    @DisplayName("Reference to a Java standard library class should not increment any count")
    void testVisitMethodInsn_doesNotIncrement_forJavaStdLibClass() {
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
}
