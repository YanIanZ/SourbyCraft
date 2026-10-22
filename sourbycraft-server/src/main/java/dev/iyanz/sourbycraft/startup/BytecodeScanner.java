package dev.iyanz.sourbycraft.startup;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads the class references out of a class file's constant pool, without loading the class.
 *
 * <p>Only the constant pool is parsed: every {@code CONSTANT_Class} entry names a type the class
 * refers to, which is enough to tell whether a plugin uses the legacy Bukkit scheduler, the Folia
 * region schedulers, or server internals. Method bodies, attributes and annotations are never
 * read, and nothing is defined in a class loader.</p>
 */
public final class BytecodeScanner {

    private static final int MAGIC = 0xCAFEBABE;
    /** No real class approaches this; a larger count means a corrupt or hostile file. */
    private static final int MAX_POOL = 65_535;

    private BytecodeScanner() {}

    /**
     * The internal names (slash-separated) of every class the class file references.
     *
     * @throws IOException when the stream is not a well-formed class file
     */
    public static Set<String> referencedClasses(final InputStream stream) throws IOException {
        final DataInputStream in = new DataInputStream(stream);
        if (in.readInt() != MAGIC) {
            throw new IOException("not a class file");
        }
        in.readUnsignedShort(); // minor
        in.readUnsignedShort(); // major
        final int count = in.readUnsignedShort();
        if (count == 0 || count > MAX_POOL) {
            throw new IOException("bad constant pool size " + count);
        }
        final String[] utf8 = new String[count];
        final int[] classNameIndex = new int[count];
        int classes = 0;
        for (int i = 1; i < count; i++) {
            final int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();                                  // Utf8
                case 7 -> classNameIndex[classes++] = in.readUnsignedShort();       // Class
                case 8, 16, 19, 20 -> in.readUnsignedShort();                       // String, MethodType, Module, Package
                case 3, 4 -> in.readInt();                                          // Integer, Float
                case 9, 10, 11, 12, 17, 18 -> in.readInt();                         // refs, NameAndType, Dynamic, InvokeDynamic
                case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); }       // MethodHandle
                case 5, 6 -> { in.readLong(); i++; }                                 // Long, Double take two slots
                default -> throw new IOException("unknown constant pool tag " + tag + " at " + i);
            }
        }
        final Set<String> out = new TreeSet<>();
        for (int c = 0; c < classes; c++) {
            final int index = classNameIndex[c];
            if (index <= 0 || index >= count || utf8[index] == null) {
                throw new IOException("class entry points at a non-UTF8 constant");
            }
            String name = utf8[index];
            // Array descriptors ("[Lorg/bukkit/World;") name their element type.
            if (name.startsWith("[")) {
                final int l = name.indexOf('L');
                if (l < 0 || !name.endsWith(";")) continue;
                name = name.substring(l + 1, name.length() - 1);
            }
            out.add(name);
        }
        return out;
    }
}
