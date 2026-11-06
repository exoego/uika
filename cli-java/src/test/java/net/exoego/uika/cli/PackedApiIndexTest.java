package net.exoego.uika.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PackedApiIndexTest {
    private static final int MEMBER_WORDS = 3;
    private static final int MEMBER_NAME = 1;
    private static final int MEMBER_DESCRIPTOR = 2;

    private sealed interface Service permits Provider {
        void serve();
    }

    private static final class Provider implements Service, Runnable {
        private int value;
        protected static long counter;

        @Override
        public void serve() {
            value++;
        }

        @Override
        public void run() {
            counter++;
        }
    }

    private static class Empty {}

    private static ClassParser parse(Scratch scratch, Class<?> type) throws IOException, ClassParser.FormatException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var in = type.getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            byte[] bytes = in.readAllBytes();
            scratch.parser.parse(bytes, bytes.length);
        }
        return scratch.parser;
    }

    private static void assertRow(ClassApi expected, ApiIndex index) {
        int entry = index.entry(expected.name);
        assertEquals(expected.name, index.nameOf(entry));
        assertEquals(expected.access, index.accessOf(entry));
        assertEquals(expected.superName, index.superOf(entry));
        assertEquals(expected.nestHost, index.nestHostOf(entry));
        assertEquals(expected.sealingUnknown, index.sealingUnknown(entry));
        assertEquals(expected.source, index.sourceOf(entry));
        assertEquals(expected.interfaces.length, index.interfaceCount(entry));
        for (int i = 0; i < expected.interfaces.length; i++) {
            assertEquals(expected.interfaces[i], index.interfaceAt(entry, i));
        }
        int permits = expected.permitted == null ? -1 : expected.permitted.length;
        assertEquals(permits, index.permittedCount(entry));
        for (int i = 0; i < permits; i++) {
            assertEquals(expected.permitted[i], index.permittedAt(entry, i));
        }
        assertEquals(expected.methodKeys.length, index.methodCount(entry));
        for (int i = 0; i < expected.methodKeys.length; i++) {
            assertEquals(expected.methodKeys[i], index.methodKeyAt(entry, i));
            assertEquals(expected.methodAccess[i], index.methodAccessAt(entry, i));
            assertEquals(expected.methodAccess[i], index.findMethod(entry, expected.methodKeys[i]));
        }
        assertEquals(expected.fieldKeys.length, index.fieldCount(entry));
        for (int i = 0; i < expected.fieldKeys.length; i++) {
            assertEquals(expected.fieldKeys[i], index.fieldKeyAt(entry, i));
            assertEquals(expected.fieldAccess[i], index.fieldAccessAt(entry, i));
            assertEquals(expected.fieldAccess[i], index.findField(entry, expected.fieldKeys[i]));
        }
    }

    @Test
    void packedRowsMatchTheFullApiAfterScratchIsReused() throws Exception {
        Scratch scratch = new Scratch();
        ApiIndex index = new ApiIndex();
        List<ClassApi> expected = new ArrayList<>();
        for (Class<?> type : List.of(Provider.class, Service.class, String.class, Object.class, Empty.class)) {
            ClassParser parser = parse(scratch, type);
            parser.sealingUnknown = type == Empty.class;
            ClassApi api = Extract.extractApi(parser, scratch);
            api.source = type == Object.class ? Intern.NONE : Intern.intern(type.getName() + ".class");
            expected.add(api);
            index.insertIfAbsent(parser, scratch, api.source);
        }
        assertEquals(expected.size(), index.classCount());
        for (ClassApi api : expected) {
            assertRow(api, index);
        }
    }

    @Test
    void aDuplicateClassDoesNotAppendMembersOrReplaceItsSource() throws Exception {
        Scratch scratch = new Scratch();
        ClassParser parser = parse(scratch, Provider.class);
        ClassApi expected = Extract.extractApi(parser, scratch);
        expected.source = Intern.intern("first.jar");
        ApiIndex index = new ApiIndex();
        index.insertIfAbsent(parser, scratch, expected.source);
        int members = index.memberCount();
        parser.access ^= Acc.PUBLIC;
        parser.methodCount = 0;
        parser.fieldCount = 0;
        index.insertIfAbsent(parser, scratch, Intern.intern("second.jar"));
        assertEquals(1, index.classCount());
        assertEquals(members, index.memberCount());
        assertRow(expected, index);
    }

    private static int[] duplicateFirstMember(int[] table, int count) {
        int[] out = Arrays.copyOf(table, (count + 1) * MEMBER_WORDS);
        System.arraycopy(table, 0, out, count * MEMBER_WORDS, MEMBER_WORDS);
        out[count * MEMBER_WORDS] ^= Acc.FINAL;
        return out;
    }

    @Test
    void duplicateMembersKeepTheFirstDeclaredAccess() throws Exception {
        Scratch scratch = new Scratch();
        ClassParser parser = parse(scratch, Provider.class);
        int methods = parser.methodCount;
        int fields = parser.fieldCount;
        int methodAccess = parser.methods[0];
        int fieldAccess = parser.fields[0];
        long method = MemberKey.of(parser.internUtf8(scratch, parser.methods[MEMBER_NAME]), parser.internUtf8(scratch, parser.methods[MEMBER_DESCRIPTOR]));
        long field = MemberKey.of(parser.internUtf8(scratch, parser.fields[MEMBER_NAME]), parser.internUtf8(scratch, parser.fields[MEMBER_DESCRIPTOR]));
        parser.methods = duplicateFirstMember(parser.methods, parser.methodCount++);
        parser.fields = duplicateFirstMember(parser.fields, parser.fieldCount++);
        ApiIndex index = new ApiIndex();
        index.insertIfAbsent(parser, scratch, Intern.NONE);
        int entry = index.entry(parser.internClassName(scratch, parser.thisClass));
        assertEquals(methods, index.methodCount(entry));
        assertEquals(fields, index.fieldCount(entry));
        assertEquals(methodAccess, index.findMethod(entry, method));
        assertEquals(fieldAccess, index.findField(entry, field));
    }

    @Test
    void anInvalidMemberDoesNotPublishAPartialClassOrLeaveMembersBehind() throws Exception {
        Scratch scratch = new Scratch();
        ClassParser parser = parse(scratch, Empty.class);
        ClassApi first = Extract.extractApi(parser, scratch);
        ApiIndex index = new ApiIndex();
        index.insertIfAbsent(parser, scratch, Intern.NONE);
        int members = index.memberCount();
        parser = parse(scratch, Provider.class);
        ClassApi expected = Extract.extractApi(parser, scratch);
        int descriptor = parser.fields[MEMBER_DESCRIPTOR];
        parser.fields[MEMBER_DESCRIPTOR] = parser.thisClass;
        assertThrows(ClassParser.FormatException.class, () -> index.insertIfAbsent(scratch.parser, scratch, Intern.NONE));
        assertEquals(1, index.classCount());
        assertEquals(members, index.memberCount());
        assertFalse(index.containsClass(expected.name));
        assertRow(first, index);
        parser.fields[MEMBER_DESCRIPTOR] = descriptor;
        index.insertIfAbsent(parser, scratch, Intern.NONE);
        assertEquals(2, index.classCount());
        assertEquals(members + expected.methodKeys.length + expected.fieldKeys.length, index.memberCount());
        assertRow(expected, index);
    }
}
