package dev.iyanz.sourbycraft.startup;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds minimal, valid class files that reference chosen classes, and jars of them. */
final class ClassFiles {

    private ClassFiles() {}

    /** A class named {@code name} extending Object whose constant pool also references {@code refs}. */
    static byte[] classReferencing(final String name, final String... refs) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0xCAFEBABE);
        out.writeShort(0);
        out.writeShort(65);
        final String[] all = new String[refs.length + 2];
        all[0] = name;
        all[1] = "java/lang/Object";
        System.arraycopy(refs, 0, all, 2, refs.length);
        // Each class: a Utf8 then a Class pointing at it; plus one Long to exercise the two-slot rule.
        out.writeShort(1 + all.length * 2 + 2);
        int index = 1;
        final int[] classIndex = new int[all.length];
        for (int i = 0; i < all.length; i++) {
            out.writeByte(1);
            out.writeUTF(all[i]);
            out.writeByte(7);
            out.writeShort(index);
            classIndex[i] = index + 1;
            index += 2;
        }
        out.writeByte(5);
        out.writeLong(42L);
        out.writeShort(0x0021);          // public super
        out.writeShort(classIndex[0]);   // this
        out.writeShort(classIndex[1]);   // super
        out.writeShort(0);               // interfaces
        out.writeShort(0);               // fields
        out.writeShort(0);               // methods
        out.writeShort(0);               // attributes
        return bytes.toByteArray();
    }

    static void jar(final Path file, final Map<String, byte[]> entries) throws IOException {
        try (OutputStream os = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(os)) {
            for (final Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
    }
}
