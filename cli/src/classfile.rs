//! Minimal class-file parser dedicated to uika.
//!
//! Only the constant pool and class/member headers are needed, so attributes
//! (Code, StackMapTable, annotations, and so on) are skipped by length.
//! Compared with a general parser (jclassfile) that structures every attribute,
//! temporary allocation is nearly zero. Utf8 values are borrowed from the input buffer
//! and decoded only when needed (JVMS 4.4.7 Modified UTF-8; ASCII is unchanged).

use anyhow::{Result, bail, ensure};
use std::borrow::Cow;

const CLASS_MAGIC: u32 = 0xCAFE_BABE;

/// One constant-pool entry. Only data needed for member-reference resolution is structured.
/// Long/Double continuation slots and index 0 are filled with Unusable to preserve direct 1-based indexing.
#[derive(Debug, Clone, Copy)]
pub enum CpEntry<'a> {
    Utf8(&'a [u8]),
    Class {
        name: u16,
    },
    Fieldref {
        class: u16,
        name_and_type: u16,
    },
    Methodref {
        class: u16,
        name_and_type: u16,
    },
    InterfaceMethodref {
        class: u16,
        name_and_type: u16,
    },
    NameAndType {
        name: u16,
        descriptor: u16,
    },
    /// String constant. Kept for reachability's Class.forName-style heuristic.
    Str {
        utf8: u16,
    },
    Unusable,
    Other,
}

#[derive(Debug, Clone, Copy)]
pub struct RawCodeRef {
    pub opcode: u8,
    pub cp_index: u16,
}

/// Member (method/field) header. Code attributes are read only for reference instructions.
#[derive(Debug, Clone)]
pub struct RawMember {
    pub access: u16,
    pub name_index: u16,
    pub descriptor_index: u16,
    pub code_refs: Vec<RawCodeRef>,
}

pub struct RawClass<'a> {
    cp: Vec<CpEntry<'a>>,
    pub access: u16,
    pub this_class: u16,
    pub super_class: u16,
    pub interfaces: Vec<u16>,
    pub fields: Vec<RawMember>,
    pub methods: Vec<RawMember>,
}

impl<'a> RawClass<'a> {
    pub fn parse(bytes: &'a [u8]) -> Result<Self> {
        let mut r = Reader { bytes, pos: 0 };
        ensure!(r.u32()? == CLASS_MAGIC, "not a class file (bad magic)");
        r.skip(4)?; // minor, major

        let cp_count = r.u16()? as usize;
        let mut cp = Vec::with_capacity(cp_count);
        cp.push(CpEntry::Unusable); // index 0 is unused.
        while cp.len() < cp_count {
            let tag = r.u8()?;
            let entry = match tag {
                1 => {
                    let len = r.u16()? as usize;
                    CpEntry::Utf8(r.take(len)?)
                }
                7 => CpEntry::Class { name: r.u16()? },
                9 => CpEntry::Fieldref {
                    class: r.u16()?,
                    name_and_type: r.u16()?,
                },
                10 => CpEntry::Methodref {
                    class: r.u16()?,
                    name_and_type: r.u16()?,
                },
                11 => CpEntry::InterfaceMethodref {
                    class: r.u16()?,
                    name_and_type: r.u16()?,
                },
                12 => CpEntry::NameAndType {
                    name: r.u16()?,
                    descriptor: r.u16()?,
                },
                3 | 4 => {
                    // Integer / Float
                    r.skip(4)?;
                    CpEntry::Other
                }
                5 | 6 => {
                    // Long / Double consume two slots (JVMS 4.4.5).
                    r.skip(8)?;
                    cp.push(CpEntry::Other);
                    CpEntry::Unusable
                }
                8 => CpEntry::Str { utf8: r.u16()? },
                16 | 19 | 20 => {
                    // MethodType / Module / Package
                    r.skip(2)?;
                    CpEntry::Other
                }
                15 => {
                    // MethodHandle (target Methodref-like entries are scanned separately).
                    r.skip(3)?;
                    CpEntry::Other
                }
                17 | 18 => {
                    // Dynamic / InvokeDynamic (NameAndType is a bootstrap synthetic name and out of scope).
                    r.skip(4)?;
                    CpEntry::Other
                }
                _ => bail!("unknown constant pool tag {tag} at offset {}", r.pos),
            };
            cp.push(entry);
        }

        let access = r.u16()?;
        let this_class = r.u16()?;
        let super_class = r.u16()?;

        let interface_count = r.u16()? as usize;
        let mut interfaces = Vec::with_capacity(interface_count);
        for _ in 0..interface_count {
            interfaces.push(r.u16()?);
        }

        let fields = r.members(&cp)?;
        let methods = r.members(&cp)?;
        // Stop without reading class attributes (SourceFile, InnerClasses, etc. are not needed).

        Ok(Self {
            cp,
            access,
            this_class,
            super_class,
            interfaces,
            fields,
            methods,
        })
    }

    pub fn cp(&self) -> &[CpEntry<'a>] {
        &self.cp
    }

    /// Decode a Utf8 entry. Names and descriptors are almost always ASCII, so return a
    /// borrowed value unchanged in that case.
    pub fn utf8(&self, index: u16) -> Result<Cow<'a, str>> {
        match self.cp.get(index as usize) {
            Some(CpEntry::Utf8(bytes)) => decode_modified_utf8(bytes),
            _ => bail!("constant pool #{index} is not Utf8"),
        }
    }

    pub fn class_name(&self, index: u16) -> Result<Cow<'a, str>> {
        match self.cp.get(index as usize) {
            Some(CpEntry::Class { name }) => self.utf8(*name),
            _ => bail!("constant pool #{index} is not Class"),
        }
    }

    pub fn name_and_type(&self, index: u16) -> Result<(Cow<'a, str>, Cow<'a, str>)> {
        match self.cp.get(index as usize) {
            Some(CpEntry::NameAndType { name, descriptor }) => {
                Ok((self.utf8(*name)?, self.utf8(*descriptor)?))
            }
            _ => bail!("constant pool #{index} is not NameAndType"),
        }
    }
}

/// JVMS 4.4.7 Modified UTF-8. ASCII (almost all real names) is borrowed unchanged;
/// only non-ASCII data is converted with cesu8.
fn decode_modified_utf8(bytes: &[u8]) -> Result<Cow<'_, str>> {
    if bytes.is_ascii() {
        // ASCII has the same representation in Modified UTF-8 and UTF-8.
        Ok(Cow::Borrowed(std::str::from_utf8(bytes)?))
    } else {
        Ok(match cesu8::from_java_cesu8(bytes)? {
            Cow::Borrowed(s) => Cow::Borrowed(s),
            Cow::Owned(s) => Cow::Owned(s),
        })
    }
}

struct Reader<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        let end = self.pos.checked_add(n).filter(|&e| e <= self.bytes.len());
        let Some(end) = end else {
            bail!("truncated class file at offset {}", self.pos);
        };
        let slice = &self.bytes[self.pos..end];
        self.pos = end;
        Ok(slice)
    }

    fn skip(&mut self, n: usize) -> Result<()> {
        self.take(n).map(|_| ())
    }

    fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }

    fn u16(&mut self) -> Result<u16> {
        let b = self.take(2)?;
        Ok(u16::from_be_bytes([b[0], b[1]]))
    }

    fn u32(&mut self) -> Result<u32> {
        let b = self.take(4)?;
        Ok(u32::from_be_bytes([b[0], b[1], b[2], b[3]]))
    }

    /// field_info / method_info list. Only Code attributes are scanned for instruction references; others are skipped.
    fn members(&mut self, cp: &[CpEntry<'a>]) -> Result<Vec<RawMember>> {
        let count = self.u16()? as usize;
        let mut members = Vec::with_capacity(count);
        for _ in 0..count {
            let access = self.u16()?;
            let name_index = self.u16()?;
            let descriptor_index = self.u16()?;
            let code_refs = self.member_attributes(cp)?;
            members.push(RawMember {
                access,
                name_index,
                descriptor_index,
                code_refs,
            });
        }
        Ok(members)
    }

    fn member_attributes(&mut self, cp: &[CpEntry<'a>]) -> Result<Vec<RawCodeRef>> {
        let count = self.u16()?;
        let mut code_refs = Vec::new();
        for _ in 0..count {
            let name_index = self.u16()?;
            let len = self.u32()? as usize;
            let body = self.take(len)?;
            if cp_utf8(cp, name_index) == Some(b"Code") {
                code_refs.extend(parse_code_refs(body)?);
            }
        }
        Ok(code_refs)
    }
}

fn cp_utf8<'a>(cp: &[CpEntry<'a>], index: u16) -> Option<&'a [u8]> {
    match cp.get(index as usize) {
        Some(CpEntry::Utf8(bytes)) => Some(bytes),
        _ => None,
    }
}

fn parse_code_refs(body: &[u8]) -> Result<Vec<RawCodeRef>> {
    let mut r = Reader {
        bytes: body,
        pos: 0,
    };
    r.skip(4)?; // max_stack, max_locals
    let code_len = r.u32()? as usize;
    let code = r.take(code_len)?;
    Ok(scan_instructions(code))
}

fn scan_instructions(code: &[u8]) -> Vec<RawCodeRef> {
    let mut refs = Vec::new();
    let mut i = 0usize;
    while i < code.len() {
        let op = code[i];
        match op {
            0xb2..=0xb8 => {
                if i + 2 < code.len() {
                    refs.push(RawCodeRef {
                        opcode: op,
                        cp_index: u16::from_be_bytes([code[i + 1], code[i + 2]]),
                    });
                }
                i += 3;
            }
            0xb9 => {
                if i + 2 < code.len() {
                    refs.push(RawCodeRef {
                        opcode: op,
                        cp_index: u16::from_be_bytes([code[i + 1], code[i + 2]]),
                    });
                }
                i += 5;
            }
            0xaa => {
                i += 1;
                while !i.is_multiple_of(4) {
                    i += 1;
                }
                if i + 12 > code.len() {
                    break;
                }
                let low = i32::from_be_bytes([code[i + 4], code[i + 5], code[i + 6], code[i + 7]]);
                let high =
                    i32::from_be_bytes([code[i + 8], code[i + 9], code[i + 10], code[i + 11]]);
                let count = high.saturating_sub(low).saturating_add(1).max(0) as usize;
                i += 12 + count * 4;
            }
            0xab => {
                i += 1;
                while !i.is_multiple_of(4) {
                    i += 1;
                }
                if i + 8 > code.len() {
                    break;
                }
                let pairs = i32::from_be_bytes([code[i + 4], code[i + 5], code[i + 6], code[i + 7]])
                    .max(0) as usize;
                i += 8 + pairs * 8;
            }
            0xc4 => {
                let Some(next) = code.get(i + 1).copied() else {
                    break;
                };
                i += if next == 0x84 { 6 } else { 4 };
            }
            _ => i += fixed_instruction_len(op),
        }
    }
    refs
}

fn fixed_instruction_len(op: u8) -> usize {
    match op {
        0x10 | 0x12 | 0x15..=0x19 | 0x36..=0x3a | 0xa9 | 0xbc => 2,
        0x11 | 0x13 | 0x14 | 0x84 | 0x99..=0xa8 | 0xbb | 0xbd | 0xc0 | 0xc1 | 0xc6 | 0xc7 => 3,
        0xc5 => 4,
        0xc8 | 0xc9 => 5,
        _ => 1,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Hand-craft a minimal class file and verify the round trip:
    /// class a/B extends java/lang/Object { int f; void m() {} }
    #[test]
    fn parses_hand_crafted_class() {
        let mut b: Vec<u8> = Vec::new();
        b.extend(0xCAFE_BABEu32.to_be_bytes()); // magic
        b.extend([0, 0, 0, 52]); // minor 0, major 52 (Java 8)
        b.extend(11u16.to_be_bytes()); // cp_count (entries 1..10)
        let utf8 = |b: &mut Vec<u8>, s: &str| {
            b.push(1);
            b.extend((s.len() as u16).to_be_bytes());
            b.extend(s.as_bytes());
        };
        utf8(&mut b, "a/B"); // #1
        utf8(&mut b, "java/lang/Object"); // #2
        b.push(7); // #3 Class -> #1
        b.extend(1u16.to_be_bytes());
        b.push(7); // #4 Class -> #2
        b.extend(2u16.to_be_bytes());
        utf8(&mut b, "f"); // #5
        utf8(&mut b, "I"); // #6
        utf8(&mut b, "m"); // #7
        utf8(&mut b, "()V"); // #8
        b.push(5); // #9 Long (consumes 2 slots -> #10 is absent)
        b.extend(42u64.to_be_bytes());
        b.extend(0x0021u16.to_be_bytes()); // access: public super
        b.extend(3u16.to_be_bytes()); // this_class -> #3
        b.extend(4u16.to_be_bytes()); // super_class -> #4
        b.extend(0u16.to_be_bytes()); // interfaces
        b.extend(1u16.to_be_bytes()); // fields_count
        b.extend(0x0002u16.to_be_bytes()); // private
        b.extend(5u16.to_be_bytes()); // name -> #5
        b.extend(6u16.to_be_bytes()); // desc -> #6
        b.extend(0u16.to_be_bytes()); // attrs
        b.extend(1u16.to_be_bytes()); // methods_count
        b.extend(0x0001u16.to_be_bytes()); // public
        b.extend(7u16.to_be_bytes()); // name -> #7
        b.extend(8u16.to_be_bytes()); // desc -> #8
        b.extend(1u16.to_be_bytes()); // attrs: 1 entry (verify it is skipped)
        b.extend(8u16.to_be_bytes()); // attribute_name_index (arbitrary)
        b.extend(3u32.to_be_bytes()); // len 3
        b.extend([0xDE, 0xAD, 0xBE]);
        b.extend(0u16.to_be_bytes()); // class attrs

        let rc = RawClass::parse(&b).unwrap();
        assert_eq!(rc.class_name(rc.this_class).unwrap(), "a/B");
        assert_eq!(rc.class_name(rc.super_class).unwrap(), "java/lang/Object");
        assert!(rc.interfaces.is_empty());
        assert_eq!(rc.fields.len(), 1);
        assert_eq!(rc.utf8(rc.fields[0].name_index).unwrap(), "f");
        assert_eq!(rc.utf8(rc.fields[0].descriptor_index).unwrap(), "I");
        assert_eq!(rc.fields[0].access, 0x0002);
        assert_eq!(rc.methods.len(), 1);
        assert_eq!(rc.utf8(rc.methods[0].name_index).unwrap(), "m");
        assert_eq!(rc.utf8(rc.methods[0].descriptor_index).unwrap(), "()V");
    }

    #[test]
    fn rejects_non_class_file() {
        assert!(RawClass::parse(&[0, 1, 2, 3]).is_err());
        assert!(RawClass::parse(b"PK\x03\x04rest-of-zip").is_err());
    }

    #[test]
    fn scans_reference_after_unaligned_tableswitch() {
        let code = [
            0x00, // nop: place tableswitch at offset 1
            0xaa, // tableswitch
            0x00, 0x00, // padding to code-offset 4
            0x00, 0x00, 0x00, 0x00, // default
            0x00, 0x00, 0x00, 0x00, // low
            0x00, 0x00, 0x00, 0x00, // high
            0x00, 0x00, 0x00, 0x00, // jump offset for the only entry
            0xb2, 0x12, 0x34, // getstatic #0x1234
        ];

        assert_eq!(scan_instructions(&code)[0].cp_index, 0x1234);
    }

    #[test]
    fn scans_reference_after_unaligned_lookupswitch() {
        let code = [
            0x00, // nop: place lookupswitch at offset 1
            0xab, // lookupswitch
            0x00, 0x00, // padding to code-offset 4
            0x00, 0x00, 0x00, 0x00, // default
            0x00, 0x00, 0x00, 0x00, // npairs
            0xb8, 0xab, 0xcd, // invokestatic #0xabcd
        ];

        assert_eq!(scan_instructions(&code)[0].cp_index, 0xabcd);
    }
}
