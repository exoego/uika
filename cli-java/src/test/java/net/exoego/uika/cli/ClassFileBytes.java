package net.exoego.uika.cli;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Byte-pushing builder for hand-crafted class files, the Java twin of the Rust tests' `b.extend(...)`. */
final class ClassFileBytes {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    /** Magic, minor 0, the given major, and constant_pool_count. */
    static ClassFileBytes header(int major, int cpCount) {
        return new ClassFileBytes().u32(0xCAFEBABEL).u16(0).u16(major).u16(cpCount);
    }

    ClassFileBytes u8(int value) {
        out.write(value);
        return this;
    }

    ClassFileBytes u16(int value) {
        out.write(value >>> 8);
        out.write(value);
        return this;
    }

    ClassFileBytes u32(long value) {
        return u16((int) (value >>> 16) & 0xffff).u16((int) value & 0xffff);
    }

    ClassFileBytes u64(long value) {
        return u32(value >>> 32).u32(value & 0xffffffffL);
    }

    ClassFileBytes raw(int... bytes) {
        for (int b : bytes) {
            out.write(b);
        }
        return this;
    }

    ClassFileBytes raw(byte[] bytes) {
        out.write(bytes, 0, bytes.length);
        return this;
    }

    /** CONSTANT_Utf8 holding the given bytes verbatim. */
    ClassFileBytes utf8(byte[] bytes) {
        return u8(1).u16(bytes.length).raw(bytes);
    }

    ClassFileBytes utf8(String s) {
        return utf8(s.getBytes(StandardCharsets.UTF_8));
    }

    /** CONSTANT_Class naming the Utf8 at {@code nameIndex}. */
    ClassFileBytes classRef(int nameIndex) {
        return u8(7).u16(nameIndex);
    }

    /** CONSTANT_String over the Utf8 at {@code utf8Index}. */
    ClassFileBytes stringRef(int utf8Index) {
        return u8(8).u16(utf8Index);
    }

    /** Fieldref (9), Methodref (10) or InterfaceMethodref (11). */
    ClassFileBytes memberRef(int tag, int classIndex, int nameAndTypeIndex) {
        return u8(tag).u16(classIndex).u16(nameAndTypeIndex);
    }

    ClassFileBytes nameAndType(int nameIndex, int descriptorIndex) {
        return u8(12).u16(nameIndex).u16(descriptorIndex);
    }

    int size() {
        return out.size();
    }

    byte[] toByteArray() {
        return out.toByteArray();
    }
}
