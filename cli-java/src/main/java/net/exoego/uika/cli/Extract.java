package net.exoego.uika.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Turns a parsed class into the API surface, hierarchy edges, references and evidence. */
final class Extract {
    /** The constant is referenced by an invoke/get/put opcode, so the code-ref loop owns it. */
    private static final byte CP_CODE_REF = 1;
    /** The Class constant is the target of a {@code new} opcode. */
    private static final byte CP_INSTANTIATED = 2;

    private static final int OP_NEW = 0xbb;

    private Extract() {}

    // ---- full API surface (library indexes, pass 2) ----

    static ClassApi extractApi(ClassParser p, Scratch scratch) throws ClassParser.FormatException {
        ClassApi api = new ClassApi();
        api.name = p.internClassName(scratch, p.thisClass);
        api.access = p.access;
        // super_class = 0 only for java/lang/Object itself.
        api.superName = p.superClass == 0 ? Intern.NONE : p.internClassName(scratch, p.superClass);
        api.interfaces = new int[p.interfaceCount];
        for (int i = 0; i < p.interfaceCount; i++) {
            api.interfaces[i] = p.internClassName(scratch, p.interfaces[i]);
        }
        members(p, scratch, p.methods, p.methodCount, api, true);
        members(p, scratch, p.fields, p.fieldCount, api, false);
        api.nestHost = p.nestHost == 0 ? Intern.NONE : p.internClassName(scratch, p.nestHost);
        if (p.permittedCount >= 0) {
            api.permitted = new int[p.permittedCount];
            for (int i = 0; i < p.permittedCount; i++) {
                api.permitted[i] = p.internClassName(scratch, p.permitted[i]);
            }
        }
        api.sealingUnknown = p.sealingUnknown;
        return api;
    }

    private static void members(ClassParser p, Scratch scratch, int[] table, int count, ClassApi api, boolean methods)
            throws ClassParser.FormatException {
        long[] keys = new long[count];
        char[] access = new char[count];
        for (int i = 0; i < count; i++) {
            int name = p.internUtf8(scratch, table[i * 3 + 1]);
            int descriptor = p.internUtf8(scratch, table[i * 3 + 2]);
            keys[i] = MemberKey.of(name, descriptor);
            access[i] = (char) table[i * 3];
        }
        ClassApi.sortMembers(keys, access, count, api, methods);
    }

    /** Parses every class under the paths in order. Parse failures become warnings. */
    static List<ClassApi> loadApis(List<String> paths, List<String> warnings) {
        List<ClassApi> all = new ArrayList<>();
        Input.onPool(() -> {
            for (String path : paths) {
                List<ApiLeaf> leaves = new ArrayList<>();
                Input.forEachClass(path, API_SINK, leaves);
                for (ApiLeaf leaf : leaves) {
                    all.addAll(leaf.apis);
                    warnings.addAll(leaf.warnings);
                }
            }
        });
        return all;
    }

    static final class ApiLeaf {
        final List<ClassApi> apis = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
    }

    static final Input.Sink<ApiLeaf> API_SINK = new Input.Sink<>() {
        @Override
        public ApiLeaf newLeaf() {
            return new ApiLeaf();
        }

        @Override
        public void accept(ApiLeaf leaf, Scratch scratch, int source, int entry, ClassSource bytes) {
            try {
                bytes.readAll();
                scratch.parser.parse(bytes.bytes, bytes.available);
                leaf.apis.add(extractApi(scratch.parser, scratch));
            } catch (ClassParser.FormatException e) {
                leaf.warnings.add(context(source, entry, e));
            }
        }
    };

    static String context(int source, int entry, Exception e) {
        return Intern.str(source) + "!" + Intern.str(entry) + ".class: " + e.getMessage();
    }

    // ---- pass 1 ----

    /**
     * Pass-1 output of one leaf, packed so that parsing a class allocates nothing. A record is
     * {@code source, className, super, nestHost, overrideIndex, interfaceCount, interfaces...,
     * edgeCount, edges..., refCount, (meta, owner, name, descriptor)...}.
     */
    static final class ScanLeaf {
        final IntBuf records = IntBuf.pooled(256);
        /** (owner, name, descriptor) triples: invocation evidence, NOT filtered by first-wins. */
        final IntBuf invocations = new IntBuf(8);
        /** Entry names that differ from "{className}.class" (directory outputs and the like). */
        List<String> overrides;
        List<String> warnings;
        int scanned;
    }

    static final class ScanSink implements Input.Sink<ScanLeaf> {
        private final NameSet oldNames;
        private final ClassGraph known;
        private final boolean collectEdges;
        private final MemberProbe probe;

        ScanSink(NameSet oldNames, ClassGraph known, boolean collectEdges, MemberProbe probe) {
            this.oldNames = oldNames;
            this.known = known;
            this.collectEdges = collectEdges;
            this.probe = probe;
        }

        @Override
        public ScanLeaf newLeaf() {
            return new ScanLeaf();
        }

        @Override
        public void accept(ScanLeaf leaf, Scratch scratch, int source, int entry, ClassSource bytes) {
            leaf.scanned++;
            int recordMark = leaf.records.n;
            int invocationMark = leaf.invocations.n;
            try {
                ClassParser p = scratch.parser;
                parseHeader(p, bytes);
                int className = p.internClassName(scratch, p.thisClass);
                // A class already in the graph before this chunk is a guaranteed first-wins
                // loser, so only evidence is swept: that must not depend on which chunk a
                // copy landed in, and chunk size scales with the thread count.
                if (!known.contains(className)) {
                    // Member references always go through a Class constant of the same pool,
                    // so a pool naming no checked-library class holds no reference at all and
                    // the members (most of the remaining inflate) need not be read.
                    boolean body = referencesLibrary(p, scratch, oldNames);
                    if (body) {
                        bytes.readAll();
                        p.bytes = bytes.bytes;
                        p.parseBody(bytes.available);
                    }
                    hierarchy(leaf, p, scratch, source, className, entry, body);
                    edges(leaf.records, p, scratch, className, collectEdges);
                    if (body) {
                        refs(leaf.records, p, scratch, oldNames);
                    } else {
                        leaf.records.add(0);
                    }
                }
                if (!probe.isEmpty()) {
                    invocationEvidence(leaf.invocations, p, scratch, probe);
                }
            } catch (ClassParser.FormatException e) {
                leaf.records.n = recordMark;
                leaf.invocations.n = invocationMark;
                if (leaf.warnings == null) {
                    leaf.warnings = new ArrayList<>();
                }
                leaf.warnings.add(context(source, entry, e));
            } catch (ClassSource.DeflateError e) {
                // An entry that does not inflate was never a scanned class.
                leaf.records.n = recordMark;
                leaf.invocations.n = invocationMark;
                leaf.scanned--;
                throw e;
            }
        }
    }

    /**
     * Parses the pool and class header, inflating further only while the header needs it. The
     * next slice is sized from the entries seen so far, so a typical class costs two inflate
     * calls rather than many small ones (zlib decodes the tail of every call on its slow path).
     */
    static void parseHeader(ClassParser p, ClassSource bytes) throws ClassParser.FormatException {
        p.begin(bytes.bytes);
        while (!p.parseHeader(bytes.available, bytes.complete)) {
            int parsed = p.cpParsed();
            int declared = p.cpDeclared();
            long estimate = p.headerNeed();
            if (declared > 0 && parsed > 1) {
                long perEntry = p.position() / parsed + 1;
                estimate = Math.max(estimate, p.position() + (declared - parsed) * perEntry * 9 / 8 + 64);
            }
            if (estimate >= bytes.expected * 15L / 16) {
                bytes.readAll();
            } else {
                bytes.ensure((int) estimate);
            }
            p.bytes = bytes.bytes;
        }
    }

    /** Whether any Class constant names a class of the checked library. Primes the per-slot owner cache. */
    static boolean referencesLibrary(ClassParser p, Scratch scratch, NameSet oldNames) throws ClassParser.FormatException {
        int cpLen = p.cpLen;
        if (scratch.cpFlags.length < cpLen) {
            scratch.cpFlags = new byte[Math.max(cpLen, scratch.cpFlags.length * 2)];
            scratch.cpOwner = new int[scratch.cpFlags.length];
        }
        Arrays.fill(scratch.cpOwner, 0, cpLen, 0);
        boolean any = false;
        for (int index = 1; index < cpLen; index++) {
            if (p.tag(index) == ClassParser.TAG_CLASS && classRefOwner(p, scratch, oldNames, index) != Intern.NONE) {
                any = true;
            }
        }
        return any;
    }

    private static void hierarchy(
            ScanLeaf leaf, ClassParser p, Scratch scratch, int source, int className, int entry, boolean body)
            throws ClassParser.FormatException {
        IntBuf out = leaf.records;
        int superName = p.superClass == 0 ? Intern.NONE : p.internClassName(scratch, p.superClass);
        out.add(source);
        out.add(className);
        out.add(superName);
        int nestHostAt = out.n;
        // NestHost is a class attribute at the very end of the file; unread means unknown.
        out.add(body ? Intern.NONE : ClassGraph.NEST_HOST_UNREAD);
        int overrideAt = out.n;
        out.add(-1);
        out.add(p.interfaceCount);
        for (int i = 0; i < p.interfaceCount; i++) {
            out.add(p.internClassName(scratch, p.interfaces[i]));
        }
        if (body && p.nestHost != 0) {
            out.a[nestHostAt] = p.internClassName(scratch, p.nestHost);
        }
        // Entry names are interned without the suffix, so "named after its class" is one compare.
        if (entry != className) {
            if (leaf.overrides == null) {
                leaf.overrides = new ArrayList<>();
            }
            out.a[overrideAt] = leaf.overrides.size();
            leaf.overrides.add(Intern.str(entry) + ".class");
        }
    }

    /**
     * Class-load edges for reachability: every Class constant (arrays unwrapped) plus string
     * constants shaped like binary class names, which over-approximates Class.forName.
     * Shaped strings are interned unconditionally so an edge never depends on parse order.
     */
    static void edges(IntBuf out, ClassParser p, Scratch scratch, int self, boolean collect) {
        int countAt = out.n;
        out.add(0);
        if (!collect) {
            return;
        }
        byte[] b = p.bytes;
        for (int index = 1; index < p.cpLen; index++) {
            int tag = p.tag(index);
            int sym = Intern.NONE;
            if (tag == ClassParser.TAG_CLASS) {
                int name = p.operand1(index);
                if (p.tag(name) == ClassParser.TAG_UTF8) {
                    sym = internObjectClass(scratch, b, p.cpPos[name] + 3, p.utf8Length(name));
                }
            } else if (tag == ClassParser.TAG_STRING) {
                int utf8 = p.operand1(index);
                if (p.tag(utf8) == ClassParser.TAG_UTF8) {
                    sym = internSlashedClassName(scratch, b, p.cpPos[utf8] + 3, p.utf8Length(utf8));
                }
            }
            if (sym != Intern.NONE && sym != self) {
                out.add(sym);
            }
        }
        int from = countAt + 1;
        Arrays.sort(out.a, from, out.n);
        int n = from;
        for (int i = from; i < out.n; i++) {
            if (n == from || out.a[n - 1] != out.a[i]) {
                out.a[n++] = out.a[i];
            }
        }
        out.n = n;
        out.a[countAt] = n - from;
    }

    /** "foo/Bar" and "[[Lfoo/Bar;" intern foo/Bar; "[I" and undecodable names intern nothing. */
    static int internObjectClass(Scratch scratch, byte[] b, int off, int len) {
        int start = off;
        int end = off + len;
        while (start < end && b[start] == '[') {
            start++;
        }
        if (start != off) {
            if (end - start < 2 || b[start] != 'L' || b[end - 1] != ';') {
                return Intern.NONE;
            }
            start++;
            end--;
        }
        try {
            return ModifiedUtf8.intern(scratch, b, start, end - start);
        } catch (ClassParser.FormatException e) {
            return Intern.NONE;
        }
    }

    /**
     * "com.foo.Bar" interns com/foo/Bar when shaped like a binary class name: dot-separated
     * ASCII Java identifier segments with at least one dot. Any non-ASCII byte fails the
     * segment test, so the raw Modified UTF-8 bytes can be judged without decoding.
     */
    static int internSlashedClassName(Scratch scratch, byte[] b, int off, int len) {
        if (len < 3 || len > 300) {
            return Intern.NONE;
        }
        boolean dot = false;
        boolean segmentStart = true;
        for (int i = 0; i < len; i++) {
            int c = b[off + i];
            if (c == '.') {
                if (segmentStart) {
                    return Intern.NONE;
                }
                dot = true;
                segmentStart = true;
                continue;
            }
            boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$';
            if (!letter && (segmentStart || c < '0' || c > '9')) {
                return Intern.NONE;
            }
            segmentStart = false;
        }
        if (!dot || segmentStart) {
            return Intern.NONE;
        }
        byte[] name = scratch.nameBytes(len);
        for (int i = 0; i < len; i++) {
            byte c = b[off + i];
            name[i] = c == '.' ? (byte) '/' : c;
        }
        return Intern.intern(scratch, name, 0, len);
    }

    /**
     * Symbol references whose owner is in {@code oldNames}, which scopes them to the checked
     * library. Owners are tested by raw bytes first, so the vast majority is rejected without
     * touching the intern pool. MethodHandle constants point at Methodref-like entries, so the
     * plain pool walk covers them; InvokeDynamic names are bootstrap synthetics and out of scope.
     */
    static void refs(IntBuf out, ClassParser p, Scratch scratch, NameSet oldNames)
            throws ClassParser.FormatException {
        int countAt = out.n;
        out.add(0);
        int cpLen = p.cpLen;
        if (scratch.cpFlags.length < cpLen) {
            scratch.cpFlags = new byte[Math.max(cpLen, scratch.cpFlags.length * 2)];
            scratch.cpOwner = new int[scratch.cpFlags.length];
        }
        // The owner cache was primed by referencesLibrary for this same pool.
        byte[] flags = scratch.cpFlags;
        Arrays.fill(flags, 0, cpLen, (byte) 0);
        int[] codeRefs = p.codeRefs;
        int codeRefCount = p.codeRefCount;
        for (int i = 0; i < codeRefCount; i++) {
            int ref = codeRefs[i];
            int index = ref & 0xffff;
            if (index < cpLen) {
                flags[index] |= (ref >>> 16) == OP_NEW ? CP_INSTANTIATED : CP_CODE_REF;
            }
        }
        int count = 0;
        for (int index = 1; index < cpLen; index++) {
            int tag = p.tag(index);
            if (tag == ClassParser.TAG_CLASS) {
                int owner = classRefOwner(p, scratch, oldNames, index);
                if (owner != Intern.NONE) {
                    out.add(SymbolRef.pack(RefKind.CLASS, 0, 0, (flags[index] & CP_INSTANTIATED) != 0));
                    out.add(owner);
                    out.add(Intern.NONE);
                    out.add(Intern.NONE);
                    count++;
                }
                continue;
            }
            if ((flags[index] & CP_CODE_REF) != 0) {
                continue;
            }
            if (tag >= ClassParser.TAG_FIELDREF && tag <= ClassParser.TAG_INTERFACE_METHODREF) {
                count += memberRef(out, p, scratch, oldNames, index, tag, 0, 0);
            }
        }
        for (int i = 0; i < codeRefCount; i++) {
            int ref = codeRefs[i];
            int opcode = ref >>> 16;
            if (opcode == OP_NEW) {
                continue;
            }
            int index = ref & 0xffff;
            int tag = p.tag(index);
            if (tag < ClassParser.TAG_FIELDREF || tag > ClassParser.TAG_INTERFACE_METHODREF) {
                continue;
            }
            int expectedStatic =
                    switch (opcode) {
                        case 0xb2, 0xb3, 0xb8 -> SymbolRef.TRI_TRUE;
                        default -> SymbolRef.TRI_FALSE;
                    };
            int fieldWrite =
                    switch (opcode) {
                        case 0xb2, 0xb4 -> SymbolRef.TRI_FALSE;
                        case 0xb3, 0xb5 -> SymbolRef.TRI_TRUE;
                        default -> SymbolRef.TRI_NONE;
                    };
            count += memberRef(out, p, scratch, oldNames, index, tag, expectedStatic, fieldWrite);
        }
        out.a[countAt] = count;
    }

    /** Test entry point for the references of a fully parsed class, decoded from the packed form. */
    static List<SymbolRef> extractRefs(ClassParser p, Scratch scratch, NameSet oldNames) throws ClassParser.FormatException {
        // refs() reads the owner cache that referencesLibrary primes for this pool.
        referencesLibrary(p, scratch, oldNames);
        IntBuf out = new IntBuf();
        refs(out, p, scratch, oldNames);
        List<SymbolRef> decoded = new ArrayList<>(out.a[0]);
        for (int at = 1; at < out.n; at += 4) {
            decoded.add(SymbolRef.unpack(out.a[at], out.a[at + 1], out.a[at + 2], out.a[at + 3]));
        }
        return decoded;
    }

    /** Test entry point for the class-load edges of a parsed class, in the order the scan stores them. */
    static int[] extractEdges(ClassParser p, Scratch scratch, int self) {
        IntBuf out = new IntBuf();
        edges(out, p, scratch, self, true);
        return Arrays.copyOfRange(out.a, 1, out.n);
    }

    /** Owner of a Class constant (arrays unwrapped to the element type), if it is in the checked library. */
    private static int classRefOwner(ClassParser p, Scratch scratch, NameSet oldNames, int classIndex)
            throws ClassParser.FormatException {
        int nameIndex = p.operand1(classIndex);
        int off = p.utf8Offset(nameIndex);
        int len = p.utf8Length(nameIndex);
        byte[] b = p.bytes;
        if (len > 0 && b[off] == '[') {
            int start = off;
            int end = off + len;
            while (start < end && b[start] == '[') {
                start++;
            }
            if (end - start < 2 || b[start] != 'L' || b[end - 1] != ';') {
                return Intern.NONE;
            }
            return acceptOwner(scratch, oldNames, b, start + 1, end - start - 2);
        }
        int cached = scratch.cpOwner[classIndex];
        if (cached == 0) {
            int owner = acceptOwner(scratch, oldNames, b, off, len);
            cached = owner == Intern.NONE ? -1 : owner + 1;
            scratch.cpOwner[classIndex] = cached;
        }
        return cached < 0 ? Intern.NONE : cached - 1;
    }

    private static int acceptOwner(Scratch scratch, NameSet oldNames, byte[] b, int off, int len)
            throws ClassParser.FormatException {
        // The first eight bytes are ASCII in every real name, and ASCII is the same in
        // Modified UTF-8 and UTF-8, so the raw prefix is what a member's prefix was built from.
        if (!oldNames.mayContain(b, off, len) && ModifiedUtf8.isAscii(b, off, Math.min(len, 8))) {
            return Intern.NONE;
        }
        if (!ModifiedUtf8.isAscii(b, off, len) && !ModifiedUtf8.isValidUtf8(b, off, len)) {
            b = ModifiedUtf8.toUtf8(b, off, len);
            off = 0;
            len = b.length;
        }
        return oldNames.find(b, off, len, Intern.hash(b, off, len));
    }

    /** Appends the member reference when its owner is accepted; returns how many were appended. */
    private static int memberRef(
            IntBuf out, ClassParser p, Scratch scratch, NameSet oldNames, int index, int tag, int expectedStatic, int fieldWrite)
            throws ClassParser.FormatException {
        int classIndex = p.operand1(index);
        int nameIndex = p.classNameIndex(classIndex);
        int off = p.utf8Offset(nameIndex);
        // Methods on array owners (clone and friends) come from java/lang/Object.
        if (p.utf8Length(nameIndex) > 0 && p.bytes[off] == '[') {
            return 0;
        }
        int owner = classRefOwner(p, scratch, oldNames, classIndex);
        if (owner == Intern.NONE) {
            return 0;
        }
        int nameAndType = p.operand2(index);
        p.requireNameAndType(nameAndType);
        int name = p.internUtf8(scratch, p.operand1(nameAndType));
        int descriptor = p.internUtf8(scratch, p.operand2(nameAndType));
        RefKind kind = tag == ClassParser.TAG_FIELDREF
                ? RefKind.FIELD
                : tag == ClassParser.TAG_METHODREF ? RefKind.METHOD : RefKind.INTERFACE_METHOD;
        out.add(SymbolRef.pack(kind, expectedStatic, fieldWrite, false));
        out.add(owner);
        out.add(name);
        out.add(descriptor);
        return 1;
    }

    /**
     * Method references matching a probed member by name+descriptor, REGARDLESS of owner:
     * AbstractMethodError can be triggered through any owner on the broken subclass's
     * hierarchy. The whole pool is swept, not just invoke opcodes, because a method reference
     * or lambda names the member only through a MethodHandle constant. A malformed entry is
     * skipped; such a class already warned at parse.
     */
    static void invocationEvidence(IntBuf out, ClassParser p, Scratch scratch, MemberProbe probe) {
        byte[] b = p.bytes;
        for (int index = 1; index < p.cpLen; index++) {
            int tag = p.tag(index);
            if (tag != ClassParser.TAG_METHODREF && tag != ClassParser.TAG_INTERFACE_METHODREF) {
                continue;
            }
            int nameAndType = p.operand2(index);
            if (p.tag(nameAndType) != ClassParser.TAG_NAME_AND_TYPE) {
                continue;
            }
            int name = p.operand1(nameAndType);
            int descriptor = p.operand2(nameAndType);
            if (p.tag(name) != ClassParser.TAG_UTF8 || p.tag(descriptor) != ClassParser.TAG_UTF8) {
                continue;
            }
            long key = findProbed(probe, b, p.cpPos[name] + 3, p.utf8Length(name), p.cpPos[descriptor] + 3, p.utf8Length(descriptor));
            if (key == MemberKey.NONE) {
                continue;
            }
            int classIndex = p.operand1(index);
            if (p.tag(classIndex) != ClassParser.TAG_CLASS) {
                continue;
            }
            int ownerName = p.operand1(classIndex);
            if (p.tag(ownerName) != ClassParser.TAG_UTF8) {
                continue;
            }
            int off = p.cpPos[ownerName] + 3;
            int len = p.utf8Length(ownerName);
            if (len > 0 && b[off] == '[') {
                continue;
            }
            int owner;
            try {
                owner = ModifiedUtf8.intern(scratch, b, off, len);
            } catch (ClassParser.FormatException e) {
                continue;
            }
            out.add(owner);
            out.add(MemberKey.name(key));
            out.add(MemberKey.descriptor(key));
        }
    }

    private static long findProbed(MemberProbe probe, byte[] b, int nameOff, int nameLen, int descriptorOff, int descriptorLen) {
        // A non-ASCII name skips the filter: its raw bytes need not match its UTF-8 form.
        if (!probe.mayContainName(b, nameOff, nameLen) && ModifiedUtf8.isAscii(b, nameOff, nameLen)) {
            return MemberKey.NONE;
        }
        boolean plain = (ModifiedUtf8.isAscii(b, nameOff, nameLen) || ModifiedUtf8.isValidUtf8(b, nameOff, nameLen))
                && (ModifiedUtf8.isAscii(b, descriptorOff, descriptorLen)
                        || ModifiedUtf8.isValidUtf8(b, descriptorOff, descriptorLen));
        if (plain) {
            return probe.find(b, nameOff, nameLen, descriptorOff, descriptorLen);
        }
        try {
            byte[] name = ModifiedUtf8.toUtf8(b, nameOff, nameLen);
            byte[] descriptor = ModifiedUtf8.toUtf8(b, descriptorOff, descriptorLen);
            byte[] joined = Arrays.copyOf(name, name.length + descriptor.length);
            System.arraycopy(descriptor, 0, joined, name.length, descriptor.length);
            return probe.find(joined, 0, name.length, name.length, descriptor.length);
        } catch (ClassParser.FormatException e) {
            return MemberKey.NONE;
        }
    }
}
