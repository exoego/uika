package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Ports the `classfile.rs` tests, plus tests of the incremental parse the Rust parser does not have. */
class ClassParserTest {
    private static ClassParser parse(byte[] bytes) throws ClassParser.FormatException {
        ClassParser p = new ClassParser();
        p.parse(bytes, bytes.length);
        return p;
    }

    /** class a/B extends java/lang/Object { int f; void m() {} } */
    private static byte[] handCraftedClass() {
        ClassFileBytes b = ClassFileBytes.header(52, 11); // entries 1..10
        b.utf8("a/B"); // #1
        b.utf8("java/lang/Object"); // #2
        b.classRef(1); // #3
        b.classRef(2); // #4
        b.utf8("f"); // #5
        b.utf8("I"); // #6
        b.utf8("m"); // #7
        b.utf8("()V"); // #8
        b.u8(5).u64(42); // #9 Long (consumes 2 slots -> #10 is absent)
        b.u16(0x0021); // access: public super
        b.u16(3); // this_class -> #3
        b.u16(4); // super_class -> #4
        b.u16(0); // interfaces
        b.u16(1); // fields_count
        b.u16(0x0002).u16(5).u16(6).u16(0); // private f:I, no attrs
        b.u16(1); // methods_count
        b.u16(0x0001).u16(7).u16(8); // public m()V
        b.u16(1); // attrs: 1 entry (verify it is skipped)
        b.u16(8); // attribute_name_index (arbitrary)
        b.u32(3); // len 3
        b.raw(0xDE, 0xAD, 0xBE);
        b.u16(0); // class attrs
        return b.toByteArray();
    }

    /** Hand-craft a minimal class file and verify the round trip. */
    @Test
    void parsesHandCraftedClass() throws Exception {
        ClassParser rc = parse(handCraftedClass());
        assertEquals("a/B", Intern.str(rc.internClassName(null, rc.thisClass)));
        assertEquals("java/lang/Object", Intern.str(rc.internClassName(null, rc.superClass)));
        assertEquals(0, rc.interfaceCount);
        assertEquals(1, rc.fieldCount);
        assertEquals("f", rc.utf8(rc.fields[1]));
        assertEquals("I", rc.utf8(rc.fields[2]));
        assertEquals(0x0002, rc.fields[0]);
        assertEquals(1, rc.methodCount);
        assertEquals("m", rc.utf8(rc.methods[1]));
        assertEquals("()V", rc.utf8(rc.methods[2]));
        // The Long takes slots 9 and 10, and the second one is unusable.
        assertEquals(11, rc.cpLen);
        assertEquals(5, rc.tag(9));
        assertEquals(0, rc.tag(10));
        assertEquals(0, rc.tag(0));
        assertEquals(0, rc.tag(11));
    }

    /** NestHost attribute on a minimal class, a/B$C with NestHost a/B. */
    @Test
    void parsesNestHostAttribute() throws Exception {
        ClassFileBytes b = ClassFileBytes.header(55, 8); // entries 1..7
        b.utf8("a/B$C"); // #1
        b.utf8("java/lang/Object"); // #2
        b.utf8("a/B"); // #3
        b.classRef(1); // #4
        b.classRef(2); // #5
        b.classRef(3); // #6 (nest host)
        b.utf8("NestHost"); // #7
        b.u16(0x0020); // access: super
        b.u16(4); // this_class
        b.u16(5); // super_class
        b.u16(0); // interfaces
        b.u16(0); // fields
        b.u16(0); // methods
        b.u16(1); // class attrs: 1
        b.u16(7); // name -> #7 "NestHost"
        b.u32(2); // len 2
        b.u16(6); // host_class_index -> #6

        ClassParser rc = parse(b.toByteArray());
        assertEquals("a/B$C", Intern.str(rc.internClassName(null, rc.thisClass)));
        assertEquals("a/B", Intern.str(rc.internClassName(null, rc.nestHost)));
        assertEquals(-1, rc.permittedCount);
    }

    /** PermittedSubclasses on a minimal class, where interface a/S permits a/P. */
    @Test
    void parsesPermittedSubclassesAttribute() throws Exception {
        ClassFileBytes b = ClassFileBytes.header(61, 8); // entries 1..7
        b.utf8("a/S"); // #1
        b.utf8("java/lang/Object"); // #2
        b.utf8("a/P"); // #3
        b.classRef(1); // #4
        b.classRef(2); // #5
        b.classRef(3); // #6 (permitted)
        b.utf8("PermittedSubclasses"); // #7
        b.u16(0x0600); // access: interface abstract
        b.u16(4); // this_class
        b.u16(5); // super_class
        b.u16(0); // interfaces
        b.u16(0); // fields
        b.u16(0); // methods
        b.u16(1); // class attrs: 1
        b.u16(7); // name -> #7
        b.u32(4); // len 4
        b.u16(1); // number_of_classes
        b.u16(6); // classes[0] -> #6

        ClassParser rc = parse(b.toByteArray());
        assertEquals(1, rc.permittedCount);
        assertEquals("a/P", Intern.str(rc.internClassName(null, rc.permitted[0])));
        assertFalse(rc.sealingUnknown);
    }

    /** A class whose only class attribute is PermittedSubclasses with the given body. */
    private static ClassParser sealedWithBody(int... body) throws ClassParser.FormatException {
        ClassFileBytes b = ClassFileBytes.header(61, 2);
        b.utf8("PermittedSubclasses"); // #1
        b.u16(0x0600).u16(1).u16(0).u16(0).u16(0).u16(0);
        b.u16(1); // class attrs: 1
        b.u16(1); // name -> #1
        b.u32(body.length);
        b.raw(body);
        return parse(b.toByteArray());
    }

    /** Rust pins `parse_class_index_list` directly. The Java twin reads in place, so it is pinned through a class. */
    @Test
    void aSealedClassPermittingNothingStaysDistinctFromUnsealed() throws Exception {
        ClassParser empty = sealedWithBody(0, 0);
        assertEquals(0, empty.permittedCount);
        assertFalse(empty.sealingUnknown);

        ClassParser one = sealedWithBody(0, 1, 0, 6);
        assertEquals(1, one.permittedCount);
        assertEquals(6, one.permitted[0]);
        assertFalse(one.sealingUnknown);

        // Count disagrees with the body length.
        ClassParser disagreeing = sealedWithBody(0, 2, 0, 6);
        assertEquals(-1, disagreeing.permittedCount);
        assertTrue(disagreeing.sealingUnknown);

        ClassParser tooShort = sealedWithBody(0);
        assertEquals(-1, tooShort.permittedCount);
        assertTrue(tooShort.sealingUnknown);
    }

    /**
     * A truncated attribute table must not read as "not sealed". That is the direction that
     * turns a corrupt old class file into a `class became sealed` violation.
     */
    @Test
    void unreadableClassAttributesLeaveSealingUnknown() throws Exception {
        ClassFileBytes b = ClassFileBytes.header(61, 2); // entry 1 only
        b.utf8("a/S"); // #1
        b.u16(0x0021); // access
        b.u16(1); // this_class (index 1; class_name is not read here)
        b.u16(0); // super_class
        b.u16(0); // interfaces
        b.u16(0); // fields
        b.u16(0); // methods
        b.u16(1); // class attrs: 1 announced...
        // ...and the table is cut off mid-header.
        b.u16(7);

        ClassParser rc = parse(b.toByteArray());
        assertEquals(-1, rc.permittedCount);
        assertTrue(rc.sealingUnknown, "a cut-off table cannot prove unsealed");
    }

    /**
     * The attribute is there but its body does not parse. That is sealed with an unknown
     * permits list, which is still not "unsealed".
     */
    @Test
    void aMalformedPermittedSubclassesBodyLeavesSealingUnknown() throws Exception {
        ClassFileBytes b = ClassFileBytes.header(61, 2);
        b.utf8("PermittedSubclasses");
        b.u16(0x0600).u16(1).u16(0).u16(0).u16(0).u16(0);
        b.u16(1); // class attrs: 1
        b.u16(1); // name -> #1
        b.u32(4); // len 4
        b.u16(2); // claims 2 classes...
        b.u16(6); // ...but carries 1

        ClassParser rc = parse(b.toByteArray());
        assertEquals(-1, rc.permittedCount);
        assertTrue(rc.sealingUnknown);
    }

    /**
     * A class file truncated right after the method table (no class-attribute section) still
     * parses, with no nest host.
     */
    @Test
    void missingClassAttributesMeanNoNestHost() throws Exception {
        ClassFileBytes b = ClassFileBytes.header(52, 4); // entries 1..3
        b.utf8("a/B"); // #1
        b.classRef(1); // #2
        b.utf8("unused"); // #3
        b.u16(0x0021);
        b.u16(2); // this_class
        b.u16(0); // super_class (Object-style 0 to keep it tiny)
        b.u16(0); // interfaces
        b.u16(0); // fields
        b.u16(0); // methods
        // no class attribute section at all

        ClassParser rc = parse(b.toByteArray());
        assertEquals(0, rc.nestHost);
    }

    /** The parser is reused per thread, so one class's attributes must not leak into the next. */
    @Test
    void aReusedParserForgetsThePreviousClassAttributes() throws Exception {
        ClassFileBytes b = ClassFileBytes.header(61, 4);
        b.utf8("NestHost"); // #1
        b.utf8("PermittedSubclasses"); // #2
        b.classRef(1); // #3
        b.u16(0x0021).u16(3).u16(0).u16(0).u16(0).u16(0);
        b.u16(2);
        b.u16(1).u32(2).u16(3);
        b.u16(2).u32(4).u16(1).u16(3);

        ClassParser p = new ClassParser();
        byte[] first = b.toByteArray();
        p.parse(first, first.length);
        assertEquals(3, p.nestHost);
        assertEquals(1, p.permittedCount);

        byte[] second = handCraftedClass();
        p.parse(second, second.length);
        assertEquals(0, p.nestHost);
        assertEquals(-1, p.permittedCount);
        assertFalse(p.sealingUnknown);
        assertEquals(0, p.codeRefCount);
    }

    @Test
    void rejectsNonClassFile() {
        ClassParser.FormatException notMagic = assertThrows(ClassParser.FormatException.class, () -> parse(new byte[] {0, 1, 2, 3}));
        assertEquals("not a class file (bad magic)", notMagic.getMessage());
        ClassParser.FormatException zip = assertThrows(
                ClassParser.FormatException.class, () -> parse("PK\u0003\u0004rest-of-zip".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals("not a class file (bad magic)", zip.getMessage());
        ClassParser.FormatException tooShort = assertThrows(ClassParser.FormatException.class, () -> parse(new byte[] {(byte) 0xCA}));
        assertEquals("truncated class file at offset 0", tooShort.getMessage());
    }

    @Test
    void reportsAnUnknownConstantPoolTagWithItsOffset() {
        ClassFileBytes b = ClassFileBytes.header(52, 3);
        b.utf8("a/B"); // #1
        b.u8(2); // #2: tag 2 does not exist
        b.u16(0);
        ClassParser.FormatException e = assertThrows(ClassParser.FormatException.class, () -> parse(b.toByteArray()));
        // The offset is the one after the tag byte, which is where the Rust reader stands.
        assertEquals("unknown constant pool tag 2 at offset 17", e.getMessage());
    }

    /** A class with one method whose Code attribute holds {@code code}. */
    private static byte[] classWithCode(int... code) {
        ClassFileBytes b = ClassFileBytes.header(52, 6);
        b.utf8("a/B"); // #1
        b.classRef(1); // #2
        b.utf8("m"); // #3
        b.utf8("()V"); // #4
        b.utf8("Code"); // #5
        b.u16(0x0021).u16(2).u16(0).u16(0);
        b.u16(0); // fields
        b.u16(1); // methods
        b.u16(0x0009).u16(3).u16(4);
        b.u16(1); // attrs
        b.u16(5); // "Code"
        b.u32(8 + code.length + 4);
        b.u16(2).u16(1); // max_stack, max_locals
        b.u32(code.length);
        b.raw(code);
        b.u16(0); // exception_table_length
        b.u16(0); // attributes_count
        b.u16(0); // class attrs
        return b.toByteArray();
    }

    /** Rust pins `scan_instructions` directly. The Java twin scans in place, so it is pinned through a Code attribute. */
    @Test
    void scansReferenceAfterUnalignedTableswitch() throws Exception {
        ClassParser rc = parse(classWithCode(
                0x00, // nop: place tableswitch at offset 1
                0xaa, // tableswitch
                0x00, 0x00, // padding to code-offset 4
                0x00, 0x00, 0x00, 0x00, // default
                0x00, 0x00, 0x00, 0x00, // low
                0x00, 0x00, 0x00, 0x00, // high
                0x00, 0x00, 0x00, 0x00, // jump offset for the only entry
                0xb2, 0x12, 0x34 // getstatic #0x1234
                ));

        assertEquals(1, rc.codeRefCount);
        assertEquals(0x1234, rc.codeRefs[0] & 0xffff);
        assertEquals(0xb2, rc.codeRefs[0] >>> 16);
    }

    @Test
    void scansReferenceAfterUnalignedLookupswitch() throws Exception {
        ClassParser rc = parse(classWithCode(
                0x00, // nop: place lookupswitch at offset 1
                0xab, // lookupswitch
                0x00, 0x00, // padding to code-offset 4
                0x00, 0x00, 0x00, 0x00, // default
                0x00, 0x00, 0x00, 0x00, // npairs
                0xb8, 0xab, 0xcd // invokestatic #0xabcd
                ));

        assertEquals(1, rc.codeRefCount);
        assertEquals(0xabcd, rc.codeRefs[0] & 0xffff);
        assertEquals(0xb8, rc.codeRefs[0] >>> 16);
    }

    @Test
    void recordsEveryReferenceOpcodeAndSkipsOperandsOfTheRest() throws Exception {
        ClassParser rc = parse(classWithCode(
                0xbb, 0x00, 0x07, // new #7
                0x10, 0xb6, // bipush whose operand looks like invokevirtual
                0xb9, 0x00, 0x08, 0x01, 0x00, // invokeinterface #8
                0xc4, 0x84, 0x00, 0x01, 0x00, 0xb8, // wide iinc whose operand looks like invokestatic
                0xb5, 0x00, 0x09, // putfield #9
                0xb6, 0x00 // invokevirtual cut off by the end of the code
                ));

        int[] expected = {0xbb << 16 | 7, 0xb9 << 16 | 8, 0xb5 << 16 | 9};
        assertArrayEquals(expected, Arrays.copyOf(rc.codeRefs, rc.codeRefCount));
    }

    @Test
    void aMalformedCodeAttributeFailsTheClassWithBodyRelativeOffsets() {
        // The Rust parser reads a Code body with its own reader, so the offset restarts at 0.
        for (int bodyLength : new int[] {0, 3}) {
            ClassParser.FormatException e = assertThrows(ClassParser.FormatException.class, () -> parse(codeBodyOf(bodyLength, 0)));
            assertEquals("truncated class file at offset 0", e.getMessage());
        }
        ClassParser.FormatException noLength = assertThrows(ClassParser.FormatException.class, () -> parse(codeBodyOf(7, 0)));
        assertEquals("truncated class file at offset 4", noLength.getMessage());
        ClassParser.FormatException shortCode = assertThrows(ClassParser.FormatException.class, () -> parse(codeBodyOf(10, 3)));
        assertEquals("truncated class file at offset 8", shortCode.getMessage());
    }

    /** A Code attribute of {@code bodyLength} bytes that claims {@code codeLength} bytes of code. */
    private static byte[] codeBodyOf(int bodyLength, int codeLength) {
        ClassFileBytes b = ClassFileBytes.header(52, 2);
        b.utf8("Code"); // #1
        b.u16(0x0021).u16(0).u16(0).u16(0);
        b.u16(0); // fields
        b.u16(1); // methods
        b.u16(0x0001).u16(1).u16(1);
        b.u16(1); // attrs
        b.u16(1); // "Code"
        b.u32(bodyLength);
        byte[] body = new byte[bodyLength];
        if (bodyLength >= 8) {
            body[7] = (byte) codeLength;
        }
        b.raw(body);
        b.u16(0); // class attrs
        return b.toByteArray();
    }

    @Test
    void decodesModifiedUtf8Names() throws Exception {
        ClassFileBytes b = ClassFileBytes.header(52, 5);
        b.utf8("caf\u00e9/\u65e5\u672c"); // #1: two- and three-byte sequences are plain UTF-8 already
        b.utf8(new byte[] {'a', (byte) 0xC0, (byte) 0x80, 'b'}); // #2: the two-byte NUL
        // #3 is U+1F600 as a surrogate pair, each half in three bytes
        b.utf8(new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0xBD, (byte) 0xED, (byte) 0xB8, (byte) 0x80});
        b.utf8(new byte[] {'x', (byte) 0xED, (byte) 0xA0, (byte) 0xBD}); // #4: a lone high surrogate
        b.u16(0x0021).u16(0).u16(0).u16(0).u16(0).u16(0).u16(0);

        ClassParser rc = parse(b.toByteArray());
        assertEquals("caf\u00e9/\u65e5\u672c", rc.utf8(1));
        assertEquals("a\u0000b", rc.utf8(2));
        assertEquals(new String(Character.toChars(0x1F600)), rc.utf8(3));
        ClassParser.FormatException e = assertThrows(ClassParser.FormatException.class, () -> rc.utf8(4));
        assertEquals("could not convert CESU-8 data to UTF-8", e.getMessage());
        ClassParser.FormatException notUtf8 = assertThrows(ClassParser.FormatException.class, () -> rc.utf8(0));
        assertEquals("constant pool #0 is not Utf8", notUtf8.getMessage());
        ClassParser.FormatException notClass = assertThrows(ClassParser.FormatException.class, () -> rc.internClassName(null, 1));
        assertEquals("constant pool #1 is not Class", notClass.getMessage());
    }

    // ---- the incremental parse, which only the Java parser has ----

    /** Every field a caller reads after a parse, so two parses can be compared whole. */
    private static String snapshot(ClassParser p) {
        StringBuilder sb = new StringBuilder();
        sb.append("cp=").append(p.cpLen).append(Arrays.toString(Arrays.copyOf(p.cpPos, p.cpLen)));
        sb.append(" headerEnd=").append(p.headerEnd);
        sb.append(" access=").append(p.access).append(" this=").append(p.thisClass).append(" super=").append(p.superClass);
        sb.append(" interfaces=").append(Arrays.toString(Arrays.copyOf(p.interfaces, p.interfaceCount)));
        sb.append(" fields=").append(Arrays.toString(Arrays.copyOf(p.fields, p.fieldCount * 3)));
        sb.append(" methods=").append(Arrays.toString(Arrays.copyOf(p.methods, p.methodCount * 3)));
        sb.append(" codeRefs=").append(Arrays.toString(Arrays.copyOf(p.codeRefs, p.codeRefCount)));
        sb.append(" nestHost=").append(p.nestHost);
        sb.append(" permitted=").append(p.permittedCount < 0 ? "none" : Arrays.toString(Arrays.copyOf(p.permitted, p.permittedCount)));
        sb.append(" sealingUnknown=").append(p.sealingUnknown);
        return sb.toString();
    }

    /** One entry of every constant-pool shape, two interfaces, members with code, and both class attributes. */
    private static byte[] classWithEveryPoolShape() {
        ClassFileBytes b = ClassFileBytes.header(61, 24);
        b.utf8("a/B"); // #1
        b.classRef(1); // #2
        b.utf8("java/lang/Object"); // #3
        b.classRef(3); // #4
        b.u8(3).u32(7); // #5 Integer
        b.u8(4).u32(7); // #6 Float
        b.u8(5).u64(7); // #7 Long, #8 unusable
        b.u8(6).u64(7); // #9 Double, #10 unusable
        b.stringRef(1); // #11
        b.nameAndType(13, 14); // #12
        b.utf8("m"); // #13
        b.utf8("()V"); // #14
        b.memberRef(9, 2, 12); // #15 Fieldref
        b.memberRef(10, 2, 12); // #16 Methodref
        b.memberRef(11, 2, 12); // #17 InterfaceMethodref
        b.u8(15).u8(6).u16(16); // #18 MethodHandle
        b.u8(16).u16(14); // #19 MethodType
        b.u8(17).u16(0).u16(12); // #20 Dynamic
        b.u8(18).u16(0).u16(12); // #21 InvokeDynamic
        b.utf8("Code"); // #22
        b.utf8("NestHost"); // #23
        b.u16(0x0021).u16(2).u16(4);
        b.u16(2).u16(2).u16(4); // interfaces
        b.u16(1); // fields
        b.u16(0x0002).u16(13).u16(14).u16(0);
        b.u16(1); // methods
        b.u16(0x0001).u16(13).u16(14);
        b.u16(1); // attrs
        b.u16(22).u32(8 + 3 + 4).u16(1).u16(1).u32(3).raw(0xb8, 0x00, 0x10).u16(0).u16(0);
        b.u16(1); // class attrs
        b.u16(23).u32(2).u16(2);
        return b.toByteArray();
    }

    /** A header that arrives split at any byte boundary parses to what the one-shot parse gives. */
    @Test
    void aHeaderSplitAtEveryByteBoundaryParsesLikeTheOneShotParse() throws Exception {
        for (byte[] bytes : new byte[][] {classWithEveryPoolShape(), handCraftedClass()}) {
            ClassParser whole = parse(bytes);
            String expected = snapshot(whole);

            for (int split = 0; split <= bytes.length; split++) {
                ClassParser p = new ClassParser();
                p.begin(bytes);
                boolean early = p.parseHeader(split, false);
                assertEquals(split >= whole.headerEnd, early, "split at " + split);
                if (!early) {
                    assertTrue(p.headerNeed() > split, "split at " + split + " asks for more than it has");
                    assertTrue(p.headerNeed() <= whole.headerEnd, "split at " + split + " asks for no more than the header");
                }
                assertTrue(p.parseHeader(bytes.length, true), "split at " + split);
                p.parseBody(bytes.length);
                assertEquals(expected, snapshot(p), "split at " + split);
            }
        }
    }

    /** The scan feeds the parser as the class inflates, so every prefix in turn is the worst case. */
    @Test
    void aHeaderFedOneByteAtATimeParsesLikeTheOneShotParse() throws Exception {
        byte[] bytes = classWithEveryPoolShape();
        ClassParser whole = parse(bytes);

        ClassParser p = new ClassParser();
        p.begin(bytes);
        int available = 0;
        while (!p.parseHeader(available, false)) {
            assertTrue(available < whole.headerEnd, "the header is complete at " + whole.headerEnd);
            available++;
        }
        assertEquals(whole.headerEnd, available);
        p.parseBody(bytes.length);
        assertEquals(snapshot(whole), snapshot(p));
    }

    /**
     * A truncated input reports the same error, at the same offset, whether it was parsed in
     * one go or the header was first attempted on a shorter prefix.
     */
    @Test
    void aTruncatedFinalInputReportsTheSameErrorAsTheOneShotParse() {
        byte[] full = classWithEveryPoolShape();
        for (int length = 0; length < full.length; length++) {
            // Cut for real, so a read past the truncation point cannot go unnoticed.
            byte[] bytes = Arrays.copyOf(full, length);
            String expected = outcome(p -> p.parse(bytes, bytes.length));
            for (int split = 0; split <= length; split++) {
                int first = split;
                String actual = outcome(p -> {
                    p.begin(bytes);
                    p.parseHeader(first, false);
                    p.parseHeader(bytes.length, true);
                    p.parseBody(bytes.length);
                });
                assertEquals(expected, actual, "length " + length + ", split at " + split);
            }
        }
    }

    /** Truncation offsets are the Rust reader's, which reads a member reference as two u16. */
    @Test
    void truncationOffsetsMatchTheRustReader() {
        byte[] full = classWithEveryPoolShape();
        ClassParser whole = assertParses(full);
        int fieldref = whole.cpPos[15];
        // One u16 of the Fieldref is there. The Rust reader consumes it and fails on the next.
        assertEquals("truncated class file at offset " + (fieldref + 3), outcome(p -> p.parse(full, fieldref + 3)));
        assertEquals("truncated class file at offset " + (fieldref + 3), outcome(p -> p.parse(full, fieldref + 4)));
        assertEquals("truncated class file at offset " + (fieldref + 1), outcome(p -> p.parse(full, fieldref + 2)));
        int nameAndType = whole.cpPos[12];
        assertEquals("truncated class file at offset " + (nameAndType + 3), outcome(p -> p.parse(full, nameAndType + 4)));
        // A Utf8 cut inside its bytes fails after the length, a Long at its first byte.
        assertEquals("truncated class file at offset " + (whole.cpPos[3] + 3), outcome(p -> p.parse(full, whole.cpPos[3] + 5)));
        assertEquals("truncated class file at offset " + (whole.cpPos[7] + 1), outcome(p -> p.parse(full, whole.cpPos[7] + 8)));
        // A cut class-attribute table is not an error.
        assertTrue(outcome(p -> p.parse(full, full.length - 1)).startsWith("ok "));
    }

    private static ClassParser assertParses(byte[] bytes) {
        try {
            return parse(bytes);
        } catch (ClassParser.FormatException e) {
            throw new AssertionError(e);
        }
    }

    private interface Parse {
        void run(ClassParser p) throws ClassParser.FormatException;
    }

    private static String outcome(Parse parse) {
        ClassParser p = new ClassParser();
        try {
            parse.run(p);
            return "ok " + snapshot(p);
        } catch (ClassParser.FormatException e) {
            return e.getMessage();
        }
    }
}
