package de.ref;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.logging.Log;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.TypePath;

import java.util.Map;

/**
 * {@link ReferenceClassVisitor} is an ASM {@link ClassVisitor} that analyzes a class file for
 * references to dependency classes across superclasses, interfaces, generic signatures,
 * annotations, fields, method signatures, and method bodies.
 */
class ReferenceClassVisitor extends ClassVisitor {
    private final ReferenceRecorder recorder;
    private final Log log;

    ReferenceClassVisitor(Map<String, Artifact> classMap, Map<Artifact, Integer> counts, Log log) {
        this(classMap, counts, null, log);
    }

    ReferenceClassVisitor(
            Map<String, Artifact> classMap,
            Map<Artifact, Integer> counts,
            Map<Artifact, ArtifactUsageStats> statsMap,
            Log log
    ) {
        super(Opcodes.ASM9);
        this.recorder = new ReferenceRecorder(classMap, counts, statsMap, log);
        this.log = log;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        recorder.setCurrentProjectClass(name != null ? name.replace('/', '.') : "<unknown>");
        if (signature != null) {
            recorder.recordSignature(signature);
        } else {
            recorder.recordInternalName(superName);
            if (interfaces != null) {
                for (String iface : interfaces) {
                    recorder.recordInternalName(iface);
                }
            }
        }
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        recorder.recordDescriptor(descriptor);
        AnnotationVisitor av = super.visitAnnotation(descriptor, visible);
        return new ReferenceAnnotationVisitor(av, recorder);
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
        recorder.recordDescriptor(descriptor);
        AnnotationVisitor av = super.visitTypeAnnotation(typeRef, typePath, descriptor, visible);
        return new ReferenceAnnotationVisitor(av, recorder);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
        if (signature != null) {
            recorder.recordSignature(signature);
        } else {
            recorder.recordDescriptor(descriptor);
        }
        FieldVisitor fv = super.visitField(access, name, descriptor, signature, value);
        return new FieldVisitor(Opcodes.ASM9, fv) {
            @Override
            public AnnotationVisitor visitAnnotation(String annDescriptor, boolean visible) {
                recorder.recordDescriptor(annDescriptor);
                AnnotationVisitor av = super.visitAnnotation(annDescriptor, visible);
                return new ReferenceAnnotationVisitor(av, recorder);
            }

            @Override
            public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String annDescriptor, boolean visible) {
                recorder.recordDescriptor(annDescriptor);
                AnnotationVisitor av = super.visitTypeAnnotation(typeRef, typePath, annDescriptor, visible);
                return new ReferenceAnnotationVisitor(av, recorder);
            }
        };
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        if (signature != null) {
            recorder.recordSignature(signature);
            // Non-generic throws clauses are not encoded in JVM method signatures (only '^' clauses are).
            if (exceptions != null && signature.indexOf('^') < 0) {
                for (String exception : exceptions) {
                    recorder.recordInternalName(exception);
                }
            }
        } else {
            recorder.recordDescriptor(descriptor);
            if (exceptions != null) {
                for (String exception : exceptions) {
                    recorder.recordInternalName(exception);
                }
            }
        }
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (log.isDebugEnabled()) {
            log.debug("  Analyzing method: " + name + descriptor);
        }
        return new ReferenceMethodVisitor(mv, recorder);
    }
}
