package de.ref;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * An ASM {@link AnnotationVisitor} that counts references inside annotation values
 * (class literals, enum constants, nested annotations, and arrays).
 */
class ReferenceAnnotationVisitor extends AnnotationVisitor {
    private final ReferenceRecorder recorder;

    ReferenceAnnotationVisitor(AnnotationVisitor av, ReferenceRecorder recorder) {
        super(Opcodes.ASM9, av);
        this.recorder = recorder;
    }

    @Override
    public void visit(String name, Object value) {
        if (value instanceof Type type) {
            recorder.recordType(type);
        }
        super.visit(name, value);
    }

    @Override
    public void visitEnum(String name, String descriptor, String value) {
        recorder.recordDescriptor(descriptor);
        super.visitEnum(name, descriptor, value);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String name, String descriptor) {
        recorder.recordDescriptor(descriptor);
        AnnotationVisitor av = super.visitAnnotation(name, descriptor);
        return new ReferenceAnnotationVisitor(av, recorder);
    }

    @Override
    public AnnotationVisitor visitArray(String name) {
        AnnotationVisitor av = super.visitArray(name);
        return new ReferenceAnnotationVisitor(av, recorder);
    }
}
