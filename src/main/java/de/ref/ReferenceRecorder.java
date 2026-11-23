package de.ref;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.logging.Log;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Helper responsible for resolving ASM internal class names, descriptors, generic signatures,
 * and {@link Type}s against the dependency class index and incrementing the corresponding
 * artifact usage statistics.
 */
final class ReferenceRecorder {
    private final Map<String, Artifact> classMap;
    private final Map<Artifact, Integer> counts;
    private final Map<Artifact, ArtifactUsageStats> statsMap;
    private final Log log;
    private String currentProjectClass = "<unknown>";

    ReferenceRecorder(Map<String, Artifact> classMap, Map<Artifact, Integer> counts, Log log) {
        this(classMap, counts, null, log);
    }

    ReferenceRecorder(
            Map<String, Artifact> classMap,
            Map<Artifact, Integer> counts,
            Map<Artifact, ArtifactUsageStats> statsMap,
            Log log
    ) {
        this.classMap = classMap;
        this.counts = counts;
        this.statsMap = statsMap;
        this.log = log;
    }

    void setCurrentProjectClass(String projectClassName) {
        this.currentProjectClass = (projectClassName == null || projectClassName.isEmpty())
                ? "<unknown>"
                : projectClassName;
    }

    /**
     * Records a reference to an ASM internal class name (e.g. {@code org/apache/commons/lang3/StringUtils})
     * or an array internal name (e.g. {@code [Lorg/apache/commons/lang3/StringUtils;}).
     */
    void recordInternalName(String internalClassName) {
        if (internalClassName == null || internalClassName.isEmpty()) {
            return;
        }
        if (internalClassName.startsWith("[")) {
            recordType(Type.getType(internalClassName));
            return;
        }

        Artifact artifact = classMap.get(internalClassName);
        if (artifact != null) {
            counts.compute(artifact, (key, value) -> (value == null) ? 1 : value + 1);
            if (statsMap != null) {
                ArtifactUsageStats stats = statsMap.get(artifact);
                if (stats != null) {
                    stats.recordReference(currentProjectClass, internalClassName.replace('/', '.'));
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("    Found reference to dependency class: " + internalClassName + " (from " + artifact.getArtifactId() + ")");
            }
        }
    }

    /**
     * Records all class references contained in an ASM {@link Type} (objects, array element types,
     * and method parameter/return types).
     */
    void recordType(Type type) {
        if (type == null) {
            return;
        }
        switch (type.getSort()) {
            case Type.ARRAY -> recordType(type.getElementType());
            case Type.OBJECT -> recordInternalName(type.getInternalName());
            case Type.METHOD -> {
                recordType(type.getReturnType());
                for (Type argumentType : type.getArgumentTypes()) {
                    recordType(argumentType);
                }
            }
            default -> {
                // Primitive types and void cannot belong to external dependencies.
            }
        }
    }

    /**
     * Records all class references contained in a JVM field or method descriptor.
     */
    void recordDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) {
            return;
        }
        recordType(Type.getType(descriptor));
    }

    /**
     * Records all class references contained in a JVM generic signature (class, method, or field),
     * including type arguments such as {@code List<MyDependencyClass>} and type parameter bounds.
     */
    void recordSignature(String signature) {
        if (signature == null || signature.isEmpty()) {
            return;
        }
        try {
            new SignatureReader(signature).accept(new ReferenceSignatureVisitor());
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            if (log.isDebugEnabled()) {
                log.debug("    Could not parse generic signature '" + signature + "': " + e.getMessage());
            }
        }
    }

    private final class ReferenceSignatureVisitor extends SignatureVisitor {
        private final Deque<String> classNameStack = new ArrayDeque<>();

        ReferenceSignatureVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visitClassType(String name) {
            classNameStack.push(name);
            recordInternalName(name);
        }

        @Override
        public void visitInnerClassType(String name) {
            String outer = classNameStack.isEmpty() ? "" : classNameStack.pop();
            String full = outer.isEmpty() ? name : outer + "$" + name;
            classNameStack.push(full);
            recordInternalName(full);
        }

        @Override
        public void visitEnd() {
            if (!classNameStack.isEmpty()) {
                classNameStack.pop();
            }
        }
    }
}
