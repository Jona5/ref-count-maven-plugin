package de.ref;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.logging.Log;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;

import java.util.Map;

/**
 * A {@link MethodVisitor} that counts references to classes belonging to project dependencies
 * across method instructions, annotations, exception handlers, class literals, and lambdas/method references.
 */
class ReferenceMethodVisitor extends MethodVisitor {
    private final ReferenceRecorder recorder;

    ReferenceMethodVisitor(MethodVisitor mv, Map<String, Artifact> classMap, Map<Artifact, Integer> counts, Log log) {
        this(mv, new ReferenceRecorder(classMap, counts, log));
    }

    ReferenceMethodVisitor(MethodVisitor mv, ReferenceRecorder recorder) {
        super(Opcodes.ASM9, mv);
        this.recorder = recorder;
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        recorder.recordDescriptor(descriptor);
        return new ReferenceAnnotationVisitor(super.visitAnnotation(descriptor, visible), recorder);
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
        recorder.recordDescriptor(descriptor);
        return new ReferenceAnnotationVisitor(super.visitTypeAnnotation(typeRef, typePath, descriptor, visible), recorder);
    }

    @Override
    public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
        recorder.recordDescriptor(descriptor);
        return new ReferenceAnnotationVisitor(super.visitParameterAnnotation(parameter, descriptor, visible), recorder);
    }

    /**
     * Called when a method is invoked (e.g. {@code StringUtils.isEmpty(...)}).
     */
    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        recorder.recordInternalName(owner);
        recorder.recordDescriptor(descriptor);
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }

    /**
     * Called when a field is accessed (e.g. {@code System.out}).
     */
    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        recorder.recordInternalName(owner);
        recorder.recordDescriptor(descriptor);
        super.visitFieldInsn(opcode, owner, name, descriptor);
    }

    /**
     * Called for type operations (e.g. {@code NEW}, {@code ANEWARRAY}, {@code CHECKCAST}, {@code INSTANCEOF}).
     */
    @Override
    public void visitTypeInsn(int opcode, String type) {
        recorder.recordInternalName(type);
        super.visitTypeInsn(opcode, type);
    }

    /**
     * Called for multidimensional array allocations.
     */
    @Override
    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
        recorder.recordDescriptor(descriptor);
        super.visitMultiANewArrayInsn(descriptor, numDimensions);
    }

    /**
     * Called for {@code try/catch} blocks to capture referenced exception types.
     */
    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
        recorder.recordInternalName(type);
        super.visitTryCatchBlock(start, end, handler, type);
    }

    /**
     * Called for constant loads, including class literals such as {@code StringUtils.class}.
     */
    @Override
    public void visitLdcInsn(Object value) {
        if (value instanceof Type type) {
            recorder.recordType(type);
        }
        super.visitLdcInsn(value);
    }

    /**
     * Called for {@code invokedynamic} instructions, including Java lambdas and method references
     * such as {@code StringUtils::isEmpty}.
     */
    @Override
    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
        recorder.recordDescriptor(descriptor);
        if (bootstrapMethodHandle != null) {
            recorder.recordInternalName(bootstrapMethodHandle.getOwner());
        }
        if (bootstrapMethodArguments != null) {
            for (Object arg : bootstrapMethodArguments) {
                if (arg instanceof Handle handle) {
                    recorder.recordInternalName(handle.getOwner());
                    recorder.recordDescriptor(handle.getDesc());
                } else if (arg instanceof Type type) {
                    recorder.recordType(type);
                }
            }
        }
        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
    }

    /**
     * Delegates reference counting for a single internal class name.
     */
    void countReference(String internalClassName) {
        recorder.recordInternalName(internalClassName);
    }
}
