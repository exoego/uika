package net.exoego.uika.cli;

/**
 * Minimal class-file parser.
 *
 * <p>Only the constant pool and the class/member headers are structured; attributes are
 * skipped by length, except that Code bodies are scanned for reference opcodes. One instance
 * is reused per thread: parsing fills its arrays in place and allocates nothing unless a
 * class outgrows them.
 */
final class ClassParser {
    /** A malformed class file. Stackless: it is a per-class warning, never a crash. */
    static final class FormatException extends Exception {
        private static final long serialVersionUID = 1L;

        FormatException(String message) {
            super(message, null, false, false);
        }
    }

    static final int TAG_UTF8 = 1;
    static final int TAG_CLASS = 7;
    static final int TAG_STRING = 8;
    static final int TAG_FIELDREF = 9;
    static final int TAG_METHODREF = 10;
    static final int TAG_INTERFACE_METHODREF = 11;
    static final int TAG_NAME_AND_TYPE = 12;

    // Plain byte arithmetic rather than a VarHandle view: C1 code, which the parser runs as
    // until C2 gets to it, calls through the VarHandle machinery per read and showed up in
    // profiles as Unsafe.getShortUnaligned frames; C2 compiles both to the same load.
    private static int be16(byte[] b, int at) {
        return (b[at] & 0xff) << 8 | (b[at + 1] & 0xff);
    }

    private static int be32(byte[] b, int at) {
        return (b[at] & 0xff) << 24 | (b[at + 1] & 0xff) << 16 | (b[at + 2] & 0xff) << 8 | (b[at + 3] & 0xff);
    }

    private static final byte[] CODE = {'C', 'o', 'd', 'e'};
    private static final byte[] NEST_HOST = "NestHost".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] PERMITTED_SUBCLASSES =
            "PermittedSubclasses".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    byte[] bytes;
    private int end;
    private int pos;

    /**
     * Position of each entry's tag byte, 0 for the unusable slots (index 0 and the second
     * half of a Long/Double). 0 is never a real tag position because the magic sits there.
     */
    int[] cpPos = new int[1024];
    /** Slot count, which is constant_pool_count, or one more when a Long/Double ends the pool. */
    int cpLen;

    /** Offset just past the interface table: everything before it is pool and header. */
    int headerEnd;
    int access;
    int thisClass;
    int superClass;
    int[] interfaces = new int[16];
    int interfaceCount;
    /** (access, name index, descriptor index) triples. */
    int[] fields = new int[3 * 32];
    int fieldCount;
    int[] methods = new int[3 * 64];
    int methodCount;
    /** Reference instructions of every method in order: {@code opcode << 16 | cpIndex}. */
    int[] codeRefs = new int[512];
    int codeRefCount;
    /**
     * The Class, String, Fieldref, Methodref and InterfaceMethodref entries in pool order, as
     * {@code tag << 16 | index}: what the reference, edge and evidence sweeps look at, a third
     * of the pool, recorded as the pool is walked so none of them walks it again.
     */
    int[] tagged = new int[512];
    int taggedCount;
    /** Class index of the NestHost target, 0 when absent. */
    int nestHost;
    /** PermittedSubclasses class indexes; count -1 when the attribute is absent (unsealed). */
    int[] permitted = new int[8];
    int permittedCount;
    boolean sealingUnknown;

    /** Parses a complete class file. */
    void parse(byte[] classBytes, int length) throws FormatException {
        begin(classBytes);
        parseHeader(length, true);
        parseBody(length);
    }

    // Resume state of an incremental header parse.
    private int cpCount;
    private int cpNext;
    private boolean headerDone;

    /** Starts an incremental parse. {@code classBytes} may still be filling up. */
    void begin(byte[] classBytes) {
        bytes = classBytes;
        pos = 0;
        cpCount = -1;
        cpNext = 1;
        cpLen = 0;
        taggedCount = 0;
        headerDone = false;
    }

    private void addTagged(int tag, int index) {
        if (taggedCount == tagged.length) {
            tagged = java.util.Arrays.copyOf(tagged, tagged.length * 2);
        }
        tagged[taggedCount++] = tag << 16 | index;
    }

    /** Bytes the header is known to need so far: a lower bound that grows as parsing advances. */
    int headerNeed() {
        return headerNeed;
    }

    private int headerNeed;

    /** Constant-pool entries parsed so far, and how many the pool declares (-1 before it is read). */
    int cpParsed() {
        return cpNext;
    }

    int cpDeclared() {
        return cpCount;
    }

    /** Offset parsing has reached. */
    int position() {
        return pos;
    }

    /**
     * Parses the constant pool and the class header (through the interface table) out of the
     * first {@code available} bytes. Returns false when more bytes are needed, keeping its
     * place so the next call resumes; with {@code last} set, running out is a truncation
     * error instead, at the offset a one-shot parse would report.
     */
    boolean parseHeader(int available, boolean last) throws FormatException {
        if (headerDone) {
            return true;
        }
        end = available;
        if (cpCount < 0) {
            if (available < 10 && !last) {
                headerNeed = 10;
                return false;
            }
            if (u32() != 0xCAFEBABEL) {
                throw new FormatException("not a class file (bad magic)");
            }
            skip(4);
            cpCount = u16();
            if (cpPos.length < cpCount + 1) {
                cpPos = new int[Math.max(cpCount + 1, cpPos.length * 2)];
            }
            cpPos[0] = 0;
        }
        int[] cp = cpPos;
        byte[] b = bytes;
        int n = cpNext;
        while (n < cpCount) {
            int tagPos = pos;
            // An entry wholly in the buffer, which is nearly every one, is stepped over from
            // its size alone. The rest go through the reader, whose bounds checks give a
            // truncated file its error at the offset the goldens pin, or ask for more bytes.
            if (tagPos + 3 <= end) {
                int tag = b[tagPos] & 0xff;
                int size = entrySize(tag, tagPos);
                if (size > 0 && tagPos + size <= end) {
                    pos = tagPos + size;
                    if (tag == 5 || tag == 6) {
                        cp[n++] = tagPos;
                        tagPos = 0;
                    } else if (tag == 7 || tag == 8 || (tag >= 9 && tag <= 11)) {
                        addTagged(tag, n);
                    }
                    cp[n++] = tagPos;
                    continue;
                }
            }
            if (!last) {
                // Resumable: never consume a partial entry.
                if (tagPos + 3 > available) {
                    cpNext = n;
                    headerNeed = tagPos + 3;
                    return false;
                }
                int size = entrySize(b[tagPos] & 0xff, tagPos);
                if (size > 0 && tagPos + size > available) {
                    cpNext = n;
                    headerNeed = tagPos + size;
                    return false;
                }
            }
            int tag = u8();
            switch (tag) {
                case 1 -> skip(u16());
                case 7, 8 -> {
                    addTagged(tag, n);
                    skip(2);
                }
                case 16, 19, 20 -> skip(2);
                case 9, 10, 11 -> {
                    addTagged(tag, n);
                    skipTwoU16();
                }
                case 12 -> skipTwoU16();
                case 3, 4, 17, 18 -> skip(4);
                case 5, 6 -> {
                    skip(8);
                    cp[n++] = tagPos;
                    tagPos = 0;
                }
                case 15 -> skip(3);
                default -> throw new FormatException("unknown constant pool tag " + tag + " at offset " + pos);
            }
            cp[n++] = tagPos;
        }
        cpNext = n;
        cpLen = n;

        if (!last) {
            if (pos + 8 > available) {
                headerNeed = pos + 8;
                return false;
            }
            int count = be16(b, pos + 6);
            if (pos + 8 + 2 * count > available) {
                headerNeed = pos + 8 + 2 * count;
                return false;
            }
        }
        access = u16();
        thisClass = u16();
        superClass = u16();

        interfaceCount = u16();
        if (interfaces.length < interfaceCount) {
            interfaces = new int[interfaceCount];
        }
        for (int i = 0; i < interfaceCount; i++) {
            interfaces[i] = u16();
        }
        headerEnd = pos;
        headerDone = true;
        return true;
    }

    /** Whole size of the entry whose tag sits at {@code tagPos}; 0 for an unknown tag. Needs 3 readable bytes. */
    private int entrySize(int tag, int tagPos) {
        return switch (tag) {
            case 1 -> 3 + be16(bytes, tagPos + 1);
            case 7, 8, 16, 19, 20 -> 3;
            case 9, 10, 11, 12, 3, 4, 17, 18 -> 5;
            case 5, 6 -> 9;
            case 15 -> 4;
            default -> 0;
        };
    }

    /**
     * Parses members (scanning Code for reference opcodes) and the class attributes. Needs
     * the whole file, and {@link #parseHeader} to have completed.
     */
    void parseBody(int length) throws FormatException {
        end = length;
        pos = headerEnd;
        codeRefCount = 0;
        fieldCount = members(true);
        methodCount = members(false);

        // A truncated class-attribute table yields "no nest host" rather than a parse error,
        // but sealing degrades to unknown: reading it as unsealed on the OLD side would
        // manufacture a `class became sealed`.
        try {
            classAttributes();
        } catch (FormatException e) {
            nestHost = 0;
            permittedCount = -1;
            sealingUnknown = true;
        }
    }

    private int members(boolean isField) throws FormatException {
        int count = u16();
        int[] table = isField ? fields : methods;
        if (table.length < count * 3) {
            table = new int[count * 3];
            if (isField) {
                fields = table;
            } else {
                methods = table;
            }
        }
        for (int i = 0; i < count; i++) {
            table[i * 3] = u16();
            table[i * 3 + 1] = u16();
            table[i * 3 + 2] = u16();
            int attributes = u16();
            for (int a = 0; a < attributes; a++) {
                int nameIndex = u16();
                long length = u32();
                int body = pos;
                skip(length);
                if (utf8Equals(nameIndex, CODE)) {
                    // A field has no Code attribute; one that claims to is still validated,
                    // so a malformed body fails the class the same way on both member kinds.
                    scanCode(body, (int) length, !isField);
                }
            }
        }
        return count;
    }

    /** Offsets in these errors are relative to the attribute body, not the class file. */
    private void scanCode(int body, int length, boolean record) throws FormatException {
        if (length < 4) {
            throw new FormatException("truncated class file at offset 0");
        }
        if (length < 8) {
            throw new FormatException("truncated class file at offset 4");
        }
        long codeLength = be32(bytes, body + 4) & 0xffffffffL;
        if (8 + codeLength > length) {
            throw new FormatException("truncated class file at offset 8");
        }
        if (record) {
            scanInstructions(body + 8, (int) codeLength);
        }
    }

    private void scanInstructions(int start, int length) {
        byte[] b = bytes;
        long i = 0;
        while (i < length) {
            int at = start + (int) i;
            int op = b[at] & 0xff;
            if ((op >= 0xb2 && op <= 0xb9) || op == 0xbb) {
                if (i + 2 < length) {
                    addCodeRef(op << 16 | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff));
                }
                i += op == 0xb9 ? 5 : 3;
            } else if (op == 0xaa) {
                i = (i + 4) & ~3L;
                if (i + 12 > length) {
                    break;
                }
                int low = be32(b, start + (int) i + 4);
                int high = be32(b, start + (int) i + 8);
                long count = Math.max(0, saturating((long) saturating((long) high - low) + 1));
                i += 12 + count * 4;
            } else if (op == 0xab) {
                i = (i + 4) & ~3L;
                if (i + 8 > length) {
                    break;
                }
                long pairs = Math.max(0, be32(b, start + (int) i + 4));
                i += 8 + pairs * 8;
            } else if (op == 0xc4) {
                if (i + 1 >= length) {
                    break;
                }
                i += (b[at + 1] & 0xff) == 0x84 ? 6 : 4;
            } else {
                i += FIXED_LENGTH[op];
            }
        }
    }

    private static int saturating(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }

    private static final byte[] FIXED_LENGTH = new byte[256];

    static {
        java.util.Arrays.fill(FIXED_LENGTH, (byte) 1);
        int[] two = {0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3a, 0xa9, 0xbc};
        for (int op : two) {
            FIXED_LENGTH[op] = 2;
        }
        int[] three = {0x11, 0x13, 0x14, 0x84, 0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7};
        for (int op : three) {
            FIXED_LENGTH[op] = 3;
        }
        for (int op = 0x99; op <= 0xa8; op++) {
            FIXED_LENGTH[op] = 3;
        }
        FIXED_LENGTH[0xc5] = 4;
        // invokedynamic names a bootstrap call site, not a member, so it is skipped whole.
        FIXED_LENGTH[0xba] = 5;
        FIXED_LENGTH[0xc8] = 5;
        FIXED_LENGTH[0xc9] = 5;
    }

    private void addCodeRef(int ref) {
        if (codeRefCount == codeRefs.length) {
            codeRefs = java.util.Arrays.copyOf(codeRefs, codeRefs.length * 2);
        }
        codeRefs[codeRefCount++] = ref;
    }

    private void classAttributes() throws FormatException {
        nestHost = 0;
        permittedCount = -1;
        sealingUnknown = false;
        int count = u16();
        for (int a = 0; a < count; a++) {
            int nameIndex = u16();
            long length = u32();
            int body = pos;
            skip(length);
            if (length == 2 && utf8Equals(nameIndex, NEST_HOST)) {
                nestHost = be16(bytes, body);
            } else if (utf8Equals(nameIndex, PERMITTED_SUBCLASSES)) {
                readPermitted(body, (int) length);
            }
        }
    }

    /** A malformed body leaves sealing unknown rather than a truncated permits list. */
    private void readPermitted(int body, int length) {
        if (length < 2) {
            sealingUnknown = true;
            return;
        }
        int count = be16(bytes, body);
        if (length - 2 != count * 2) {
            sealingUnknown = true;
            return;
        }
        if (permitted.length < count) {
            permitted = new int[count];
        }
        for (int i = 0; i < count; i++) {
            permitted[i] = be16(bytes, body + 2 + i * 2);
        }
        permittedCount = count;
    }

    private boolean utf8Equals(int index, byte[] expected) {
        int at = index < cpLen ? cpPos[index] : 0;
        if (at == 0 || bytes[at] != TAG_UTF8) {
            return false;
        }
        int length = be16(bytes, at + 1);
        if (length != expected.length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (bytes[at + 3 + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    // ---- constant pool access ----

    /** The entry's tag, or 0 for an unusable or out-of-range slot. */
    int tag(int index) {
        int at = index < cpLen ? cpPos[index] : 0;
        return at == 0 ? 0 : bytes[at] & 0xff;
    }

    /** First u2 operand of the entry (Class name, String utf8, ref class, NameAndType name). */
    int operand1(int index) {
        return be16(bytes, cpPos[index] + 1);
    }

    /** Second u2 operand (ref name_and_type, NameAndType descriptor). */
    int operand2(int index) {
        return be16(bytes, cpPos[index] + 3);
    }

    /** Byte offset of a Utf8 entry's data. */
    int utf8Offset(int index) throws FormatException {
        if (tag(index) != TAG_UTF8) {
            throw new FormatException("constant pool #" + index + " is not Utf8");
        }
        return cpPos[index] + 3;
    }

    int utf8Length(int index) {
        return be16(bytes, cpPos[index] + 1);
    }

    /** The Utf8 index naming a Class entry. */
    int classNameIndex(int index) throws FormatException {
        if (tag(index) != TAG_CLASS) {
            throw new FormatException("constant pool #" + index + " is not Class");
        }
        return operand1(index);
    }

    void requireNameAndType(int index) throws FormatException {
        if (tag(index) != TAG_NAME_AND_TYPE) {
            throw new FormatException("constant pool #" + index + " is not NameAndType");
        }
    }

    /** Interns a Utf8 entry as UTF-8. */
    int internUtf8(Scratch scratch, int index) throws FormatException {
        int off = utf8Offset(index);
        return ModifiedUtf8.intern(scratch, bytes, off, utf8Length(index));
    }

    int internClassName(Scratch scratch, int index) throws FormatException {
        return internUtf8(scratch, classNameIndex(index));
    }

    /** Decodes a Utf8 entry. For cold paths and tests; the scan interns bytes instead. */
    String utf8(int index) throws FormatException {
        return Intern.str(internUtf8(null, index));
    }

    // ---- reader ----

    private int u8() throws FormatException {
        if (pos + 1 > end) {
            throw truncated();
        }
        return bytes[pos++] & 0xff;
    }

    private int u16() throws FormatException {
        if (pos + 2 > end) {
            throw truncated();
        }
        int value = be16(bytes, pos);
        pos += 2;
        return value;
    }

    private long u32() throws FormatException {
        if (pos + 4 > end) {
            throw truncated();
        }
        long value = be32(bytes, pos) & 0xffffffffL;
        pos += 4;
        return value;
    }

    private void skip(long n) throws FormatException {
        if (pos + n > end) {
            throw truncated();
        }
        pos += (int) n;
    }

    /** The Rust reader takes these as two u16, so a cut after the first one reports the second one's offset. */
    private void skipTwoU16() throws FormatException {
        if (pos + 4 > end) {
            if (pos + 2 <= end) {
                pos += 2;
            }
            throw truncated();
        }
        pos += 4;
    }

    private FormatException truncated() {
        return new FormatException("truncated class file at offset " + pos);
    }
}
