package de.ref;

import org.apache.maven.artifact.Artifact;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Map;

/**
 * {@link ReferenceClassVisitor} is an ASM {@link ClassVisitor} that serves as the entry point for analyzing a class.
 * It's responsible for delegating the analysis of individual method bodies to a {@link ReferenceMethodVisitor}.
 * This visitor primarily focuses on orchestrating the method-level analysis, as most of the reference counting
 * logic resides within the {@link ReferenceMethodVisitor}.
 * It also holds references to the global {@code classMap} and {@code counts} for shared data access.
 */
class ReferenceClassVisitor extends ClassVisitor {
    private final Map<String, Artifact> classMap;
    private final Map<Artifact, Integer> counts;

    ReferenceClassVisitor(Map<String, Artifact> classMap, Map<Artifact, Integer> counts) {
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

