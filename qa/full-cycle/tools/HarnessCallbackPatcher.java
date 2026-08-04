import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Repairs the stress-harness command callback so a Brigadier result of zero remains zero.
 *
 * <p>The original QA callback used {@code Math.max(1, result)} whenever command parsing and
 * invocation succeeded. That made a deliberately denied command look successful even though its
 * command body returned zero and performed no mutation.</p>
 */
public final class HarnessCallbackPatcher {
    private static final String CONTROLLER_ENTRY =
            "ru/kaiserroman/bannerokstress/ArmyFullCycleController.class";
    private static final String CALLBACK_NAME = "lambda$runAs$15";
    private static final String CALLBACK_DESCRIPTOR = "([IZI)V";

    private HarnessCallbackPatcher() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("usage: HarnessCallbackPatcher INPUT_JAR OUTPUT_JAR");
        }
        Path input = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path output = Path.of(arguments[1]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(input)) {
            throw new IOException("input harness jar does not exist: " + input);
        }
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = Files.createTempFile(parent, output.getFileName().toString(), ".tmp");
        boolean[] patched = {false};
        try {
            rewriteJar(input, temporary, patched);
            if (!patched[0]) {
                throw new IllegalStateException("stress-harness callback method was not found");
            }
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void rewriteJar(Path input, Path output, boolean[] patched) throws IOException {
        try (InputStream rawInput = Files.newInputStream(input);
                ZipInputStream zipInput = new ZipInputStream(rawInput);
                OutputStream rawOutput = Files.newOutputStream(output);
                ZipOutputStream zipOutput = new ZipOutputStream(rawOutput)) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zipInput.getNextEntry()) != null) {
                ZipEntry replacement = new ZipEntry(entry.getName());
                replacement.setTime(entry.getTime());
                zipOutput.putNextEntry(replacement);
                if (CONTROLLER_ENTRY.equals(entry.getName())) {
                    byte[] classBytes = zipInput.readAllBytes();
                    zipOutput.write(patchController(classBytes, patched));
                } else {
                    int read;
                    while ((read = zipInput.read(buffer)) >= 0) {
                        if (read > 0) {
                            zipOutput.write(buffer, 0, read);
                        }
                    }
                }
                zipOutput.closeEntry();
                zipInput.closeEntry();
            }
        }
    }

    private static byte[] patchController(byte[] original, boolean[] patched) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, 0);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                MethodVisitor output = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!CALLBACK_NAME.equals(name) || !CALLBACK_DESCRIPTOR.equals(descriptor)) {
                    return output;
                }
                patched[0] = true;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitEnd() {
                        output.visitCode();
                        // results[0] = result; preserve Brigadier's actual integer result.
                        output.visitVarInsn(Opcodes.ALOAD, 0);
                        output.visitInsn(Opcodes.ICONST_0);
                        output.visitVarInsn(Opcodes.ILOAD, 2);
                        output.visitInsn(Opcodes.IASTORE);
                        output.visitInsn(Opcodes.RETURN);
                        output.visitMaxs(3, 3);
                        output.visitEnd();
                    }
                };
            }
        };
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }
}
