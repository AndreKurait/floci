import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Map;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;

public final class StartupAgent {
    private static final String INDEX = "io/quarkus/test/common/TestClassIndexer";
    private static final String APP = "io/quarkus/test/junit/AppMakerHelper";
    private static final String METRICS = "io/quarkus/builder/BuildMetrics";
    private static final Type RECORDER = Type.getType(StartupRecorder.class);
    private static final Map<String, String> METHODS = Map.of(
        INDEX + ".readIndex", "(Ljava/nio/file/Path;Ljava/lang/Class;)Lorg/jboss/jandex/Index;",
        INDEX + ".writeIndex", "(Lorg/jboss/jandex/Index;Ljava/nio/file/Path;Ljava/lang/Class;)V",
        INDEX + ".indexTestClasses", "(Ljava/nio/file/Path;)Lorg/jboss/jandex/Index;",
        INDEX + ".removeIndex", "(Ljava/lang/Class;)V",
        APP + ".prepare", "(Ljava/lang/Class;Lio/quarkus/bootstrap/app/CuratedApplication;Ljava/util/Optional;)Lio/quarkus/test/junit/PrepareResult;",
        APP + ".getStartupAction", "(Ljava/lang/Class;Lio/quarkus/bootstrap/app/CuratedApplication;Ljava/util/Optional;)Lio/quarkus/bootstrap/app/StartupAction;",
        METRICS + ".dumpTo", "(Ljava/nio/file/Path;)V");
    private static final Map<String, String> SUPPORTED_BYTES = Map.of(
        "io/quarkus/test/common/TestClassIndexer", "0f7e02598ec1ee5d25452fb703dad2e144395f75025078972b2a5b1e9e0f52d9",
        "io/quarkus/test/junit/AppMakerHelper", "e8640395f6553de82fd60dd1e3caad07bce16946a2040ef274cd709ac6b9f74f",
        "io/quarkus/builder/BuildMetrics", "368901b9378edd3b2a4b8256079c257221d79ac0e1ebcf88869d197bf655c42c");
    static void validateTarget(String name, byte[] bytes) throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!digest.equals(SUPPORTED_BYTES.get(name))) {
            throw new IllegalStateException("Unsupported Quarkus 3.40.1.1 diagnostic class: " + name + " sha256=" + digest);
        }
        Set<String> remaining = new HashSet<>();
        METHODS.forEach((key, descriptor) -> { if (key.startsWith(name + ".")) { remaining.add(key + descriptor); } });
        if (name.equals(INDEX)) { remaining.add(INDEX + ".indexPath(Ljava/nio/file/Path;)Ljava/nio/file/Path;"); }
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                remaining.remove(name + "." + method + descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!remaining.isEmpty()) { throw new IllegalStateException("Missing diagnostic methods: " + remaining); }
        StartupRecorder.emit("validated\t" + name + "\t" + digest);
    }
    public static void premain(String destination, Instrumentation instrumentation) throws Exception {
        StartupRecorder.open(destination);
        for (String name : SUPPORTED_BYTES.keySet()) {
            try (InputStream source = ClassLoader.getSystemResourceAsStream(name + ".class")) {
                if (source == null) { throw new IllegalStateException("Missing diagnostic framework class: " + name); }
                validateTarget(name, source.readAllBytes());
            }
        }
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefining, ProtectionDomain domain, byte[] bytes) {
                if (!INDEX.equals(name) && !APP.equals(name) && !METRICS.equals(name)) { return null; }
                try {
                    validateTarget(name, bytes);
                } catch (Exception unsupported) {
                    StartupRecorder.emit("unsupported-loader-class\t" + name + "\t" + loader + "\t" + unsupported.getMessage());
                    // Transformer exceptions are ignored by the JVM. Invalid bytes force class definition to fail.
                    return new byte[] {0};
                }
                StartupRecorder.emit("class\t" + name + "\t" + loader + "\tbytes=" + bytes.length);
                ClassReader reader = new ClassReader(bytes);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
                    @Override
                    protected ClassLoader getClassLoader() { return loader; }
                };
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor delegate = super.visitMethod(access, method, descriptor, signature, exceptions);
                        if (name.equals(INDEX) && method.equals("indexPath") && descriptor.equals("(Ljava/nio/file/Path;)Ljava/nio/file/Path;")) {
                            StartupRecorder.transformed(name + "." + method, loader, descriptor);
                            return new AdviceAdapter(Opcodes.ASM9, delegate, access, method, descriptor) {
                                @Override
                                protected void onMethodExit(int opcode) {
                                    if (opcode == ARETURN) { dup(); invokeStatic(RECORDER, new Method("resolved", "(Ljava/nio/file/Path;)V")); }
                                }
                            };
                        }
                        String key = name + "." + method;
                        if (!descriptor.equals(METHODS.get(key))) { return delegate; }
                        StartupRecorder.transformed(key, loader, descriptor);
                        return new AdviceAdapter(Opcodes.ASM9, delegate, access, method, descriptor) {
                            private final Label start = new Label();
                            private final Label end = new Label();
                            private final Label handler = new Label();
                            @Override
                            protected void onMethodEnter() {
                                push(key);
                                if (name.equals(METRICS) || method.equals("indexTestClasses")) { loadArg(0); } else { visitInsn(ACONST_NULL); }
                                if (name.equals(APP) || method.equals("removeIndex")) { loadArg(0); }
                                else if (method.equals("readIndex")) { loadArg(1); }
                                else if (method.equals("writeIndex")) { loadArg(2); }
                                else { visitInsn(ACONST_NULL); }
                                if (name.equals(APP)) { loadArg(2); } else { visitInsn(ACONST_NULL); }
                                invokeStatic(RECORDER, new Method("enter", "(Ljava/lang/String;Ljava/nio/file/Path;Ljava/lang/Object;Ljava/lang/Object;)V"));
                                visitLabel(start);
                            }
                            @Override
                            protected void onMethodExit(int opcode) {
                                if (opcode != ATHROW) { push(false); invokeStatic(RECORDER, new Method("exit", "(Z)V")); }
                            }
                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                visitLabel(end); visitTryCatchBlock(start, end, handler, null); visitLabel(handler);
                                push(true); invokeStatic(RECORDER, new Method("exit", "(Z)V"));
                                // Use the delegate so the rethrow does not trigger advice twice.
                                mv.visitInsn(ATHROW);
                                super.visitMaxs(maxStack, maxLocals);
                            }
                        };
                    }
                }, ClassReader.EXPAND_FRAMES);
                return writer.toByteArray();
            }
        });
    }
}
