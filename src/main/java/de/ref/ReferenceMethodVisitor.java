package de.ref;

import org.apache.maven.artifact.Artifact;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Map;


/**
 * A {@link MethodVisitor} that counts references to classes belonging to project dependencies.
 */
class ReferenceMethodVisitor extends MethodVisitor {
    private final Map<String, Artifact> classMap;
    private final Map<Artifact, Integer> counts;

    ReferenceMethodVisitor(MethodVisitor mv, Map<String, Artifact> classMap, Map<Artifact, Integer> counts) {
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
