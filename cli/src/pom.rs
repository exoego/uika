//! Reading `<optional>` out of a dependency's POM, to keep upgrade-check advice honest.
//!
//! When an upgrade drops a coordinate entirely, `suggest.rs` used to tell the user that the
//! referencing artifact "still needs it" and to upgrade to a release that no longer requires
//! it. For a dependency the referencing artifact declares `<optional>true</optional>` both
//! halves are wrong: it was never required transitively (it arrived through some other branch
//! of the graph), and a release dropping it will never exist because optional integrations --
//! logging facades, alternative serialization backends -- are permanent design choices.
//! See https://github.com/exoego/uika/issues/96.
//!
//! This reads a POM that is ALREADY on disk beside the JAR the CLI is scanning. No network, no
//! resolver, no JVM: the no-JVM claim in README holds, and a missing POM just falls back to the
//! original wording. The alternative -- having the build plugins resolve POM metadata into the
//! dump -- would be authoritative but needs a dump-format change plus three plugin
//! implementations, and would not help the dumps already in CI caches.

use std::path::{Path, PathBuf};

/// Whether `pom` declares `group:name` as an optional dependency.
///
/// Deliberately loose in two directions, because the caller only rewords advice and never
/// suppresses a violation:
///
/// - Any declaration outside `<dependencyManagement>` counts, including ones inside a
///   `<profile>`. Profile activation is not evaluated (google-auth declares its slf4j-api
///   optional inside an `activeByDefault` profile, which is the shape that motivated this).
/// - Inherited declarations are not followed. A parent POM declaring the dependency optional
///   reads as not-optional here, which keeps the original wording -- the safe direction.
///
/// Namespace-PREFIXED element names (`<m:dependency>`) are not matched, so such a POM reads as
/// not-optional. That is the safe direction, and Maven tooling writes the default namespace.
pub fn declares_optional(pom: &str, group: &str, name: &str) -> bool {
    let text = blank_uninterpreted(pom);
    let managed = dependency_management_spans(&text);

    let mut cursor = 0;
    while let Some(open) = find_element(&text[cursor..], "dependency") {
        let start = cursor + open;
        let Some(end) = element_end(&text[start..], "dependency") else {
            break;
        };
        let end = start + end;
        cursor = end;
        if managed.iter().any(|(s, e)| start >= *s && end <= *e) {
            continue;
        }
        let block = &text[start..end];
        if child_text(block, "groupId").as_deref() == Some(group)
            && child_text(block, "artifactId").as_deref() == Some(name)
            && child_text(block, "optional").as_deref() == Some("true")
        {
            return true;
        }
    }
    false
}

/// The POM sitting next to `jar` in a local artifact cache, if it is there.
///
/// Two layouts are probed, which together cover the caches uika actually scans against:
/// the POM as a direct sibling of the JAR (Maven `~/.m2`, Coursier), and the POM one
/// directory over (Gradle's `modules-2/files-2.1/<g>/<n>/<v>/<sha1>/`, which gives each
/// artifact of a version its own checksum directory). Both are matched on the exact
/// `<name>-<version>.pom` file name, so the sibling-directory walk cannot pick up a
/// neighbouring version.
pub fn locate(jar: &Path, name: &str, version: &str) -> Option<PathBuf> {
    let file_name = format!("{name}-{version}.pom");
    let dir = jar.parent()?;

    let sibling = dir.join(&file_name);
    if sibling.is_file() {
        return Some(sibling);
    }

    for entry in std::fs::read_dir(dir.parent()?).ok()?.flatten() {
        let candidate = entry.path().join(&file_name);
        if candidate.is_file() {
            return Some(candidate);
        }
    }
    None
}

/// Blank the two regions whose contents are not markup: `<!-- ... -->` and `<![CDATA[ ... ]]>`.
/// Both can legally contain something that reads like a dependency declaration -- a POM's
/// `<description>` is free to wrap example XML in CDATA -- and scanning them would be the one
/// way this file reports optional for a dependency the artifact never declared.
///
/// Blanking rather than deleting keeps every offset in the result equal to the same offset in
/// the input, which is what makes the `<dependencyManagement>` spans comparable against element
/// offsets found later in the same string.
fn blank_uninterpreted(pom: &str) -> String {
    const REGIONS: [(&str, &str); 2] = [("<!--", "-->"), ("<![CDATA[", "]]>")];

    let mut out = String::with_capacity(pom.len());
    let mut rest = pom;
    // Whichever region opens first, so a "<!--" inside CDATA (and vice versa) cannot reopen a
    // region that is already skipped.
    while let Some((open, start, end)) = REGIONS
        .iter()
        .filter_map(|(s, e)| rest.find(s).map(|at| (at, *s, *e)))
        .min_by_key(|(at, _, _)| *at)
    {
        out.push_str(&rest[..open]);
        let after = &rest[open + start.len()..];
        match after.find(end) {
            Some(close) => {
                out.push_str(&" ".repeat(start.len() + close + end.len()));
                rest = &after[close + end.len()..];
            }
            None => {
                // Unterminated: everything from here on is inside the region.
                out.push_str(&" ".repeat(rest.len() - open));
                rest = "";
            }
        }
    }
    out.push_str(rest);
    out
}

/// Byte ranges covered by `<dependencyManagement>` elements. Entries there set versions for
/// dependencies declared elsewhere; they are not dependencies of this artifact.
fn dependency_management_spans(text: &str) -> Vec<(usize, usize)> {
    let mut spans = Vec::new();
    let mut cursor = 0;
    while let Some(open) = find_element(&text[cursor..], "dependencyManagement") {
        let start = cursor + open;
        let Some(end) = element_end(&text[start..], "dependencyManagement") else {
            break;
        };
        spans.push((start, start + end));
        cursor = start + end;
    }
    spans
}

/// Offset of the next `<tag>` open tag in `text`. Matches `<tag>` and `<tag attr=..>` but not
/// `<tagFoo>`, and ignores self-closing `<tag/>` (an empty element declares nothing).
fn find_element(text: &str, tag: &str) -> Option<usize> {
    let bytes = text.as_bytes();
    let mut i = 0;
    loop {
        let at = find_bytes(bytes, i, format!("<{tag}").as_bytes())?;
        let after = at + 1 + tag.len();
        match bytes.get(after) {
            Some(b'>') => return Some(at),
            Some(c) if c.is_ascii_whitespace() => {
                // An attribute list still opens the element unless it self-closes.
                let close = find_bytes(bytes, after, b">")?;
                if bytes[close - 1] != b'/' {
                    return Some(at);
                }
                i = close;
            }
            _ => i = after,
        }
    }
}

/// Offset just past the matching `</tag>`, counting nested opens of the same tag.
fn element_end(text: &str, tag: &str) -> Option<usize> {
    let bytes = text.as_bytes();
    let close = format!("</{tag}>");
    let mut depth = 0usize;
    let mut i = 0;
    loop {
        let next_open = find_element(&text[i..], tag).map(|o| i + o);
        let next_close = find_bytes(bytes, i, close.as_bytes())?;
        match next_open {
            Some(open) if open < next_close => {
                depth += 1;
                i = open + 1 + tag.len();
            }
            _ => {
                // `depth <= 1` rather than a decrement-then-test, so a malformed POM whose
                // close tag precedes its open tag returns a bound instead of underflowing.
                if depth <= 1 {
                    return Some(next_close + close.len());
                }
                depth -= 1;
                i = next_close + close.len();
            }
        }
    }
}

/// Text of the first `<tag>` inside `block`, trimmed. Nesting is not tracked, which is fine for
/// the leaf elements read here (`groupId`, `artifactId`, `optional`) once `<exclusions>` is out
/// of the way: an `<exclusion>` carries its own groupId/artifactId, which would otherwise be
/// read as the dependency's own coordinate.
///
/// Exclusions are blanked rather than truncated at, because `<optional>` is free to come after
/// them and netty puts it there.
fn child_text(block: &str, tag: &str) -> Option<String> {
    let block = blank_element(block, "exclusions");
    let open = find_element(&block, tag)?;
    let value_start = open + block[open..].find('>')? + 1;
    let close = block[value_start..].find(&format!("</{tag}>"))?;
    Some(block[value_start..value_start + close].trim().to_string())
}

/// `text` with every `<tag>...</tag>` region replaced by spaces, preserving length.
fn blank_element(text: &str, tag: &str) -> String {
    let mut out = text.to_string();
    let mut cursor = 0;
    while let Some(open) = find_element(&out[cursor..], tag) {
        let start = cursor + open;
        // An unclosed element swallows the remainder: losing a later `<optional>` reads as
        // not-optional, which keeps the original advice -- the safe direction on a malformed POM.
        let end = match element_end(&out[start..], tag) {
            Some(end) => start + end,
            None => out.len(),
        };
        out.replace_range(start..end, &" ".repeat(end - start));
        cursor = end;
    }
    out
}

fn find_bytes(haystack: &[u8], from: usize, needle: &[u8]) -> Option<usize> {
    if from >= haystack.len() {
        return None;
    }
    haystack[from..]
        .windows(needle.len())
        .position(|w| w == needle)
        .map(|p| p + from)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The shape from https://github.com/exoego/uika/issues/96: google-auth declares slf4j-api
    /// optional inside an activeByDefault profile, not in the top-level dependencies.
    #[test]
    fn optional_inside_a_profile_counts() {
        let pom = r#"
        <project>
          <dependencies>
            <dependency>
              <groupId>com.google.guava</groupId>
              <artifactId>guava</artifactId>
            </dependency>
          </dependencies>
          <profiles>
            <profile>
              <id>slf4j2x</id>
              <activation><activeByDefault>true</activeByDefault></activation>
              <dependencies>
                <dependency>
                  <groupId>org.slf4j</groupId>
                  <artifactId>slf4j-api</artifactId>
                  <version>${project.slf4j.version}</version>
                  <optional>true</optional>
                </dependency>
              </dependencies>
            </profile>
          </profiles>
        </project>
        "#;
        assert!(declares_optional(pom, "org.slf4j", "slf4j-api"));
        assert!(!declares_optional(pom, "com.google.guava", "guava"));
    }

    #[test]
    fn plain_required_dependency_is_not_optional() {
        let pom = r#"
        <project><dependencies>
          <dependency>
            <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
            <version>2.0.18</version>
          </dependency>
        </dependencies></project>
        "#;
        assert!(!declares_optional(pom, "org.slf4j", "slf4j-api"));
    }

    #[test]
    fn optional_false_is_not_optional() {
        let pom = r#"
        <project><dependencies>
          <dependency>
            <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
            <optional>false</optional>
          </dependency>
        </dependencies></project>
        "#;
        assert!(!declares_optional(pom, "org.slf4j", "slf4j-api"));
    }

    /// dependencyManagement sets versions for dependencies declared elsewhere. An optional
    /// flag there does not make this artifact's own dependency optional.
    #[test]
    fn dependency_management_entries_are_ignored() {
        let pom = r#"
        <project>
          <dependencyManagement><dependencies>
            <dependency>
              <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
              <version>2.0.18</version><optional>true</optional>
            </dependency>
          </dependencies></dependencyManagement>
          <dependencies>
            <dependency>
              <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
            </dependency>
          </dependencies>
        </project>
        "#;
        assert!(!declares_optional(pom, "org.slf4j", "slf4j-api"));
    }

    /// A POM's prose is free to carry example XML in CDATA. Scanning it would be the one way
    /// this file reports optional for a dependency the artifact never declared.
    #[test]
    fn cdata_contents_are_not_declarations() {
        let pom = r#"
        <project>
          <description><![CDATA[
            To enable logging add:
            <dependency>
              <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
              <optional>true</optional>
            </dependency>
          ]]></description>
          <dependencies>
            <dependency>
              <groupId>com.example</groupId><artifactId>thing</artifactId>
              <optional>true</optional>
            </dependency>
          </dependencies>
        </project>
        "#;
        assert!(!declares_optional(pom, "org.slf4j", "slf4j-api"));
        assert!(declares_optional(pom, "com.example", "thing"));
    }

    /// Namespace-prefixed element names are not matched. Reading as not-optional keeps the
    /// original advice, which is the safe direction.
    #[test]
    fn namespace_prefixed_elements_read_as_not_optional() {
        let pom = r#"
        <m:project xmlns:m="http://maven.apache.org/POM/4.0.0"><m:dependencies>
          <m:dependency>
            <m:groupId>g</m:groupId><m:artifactId>a</m:artifactId>
            <m:optional>true</m:optional>
          </m:dependency>
        </m:dependencies></m:project>
        "#;
        assert!(!declares_optional(pom, "g", "a"));
    }

    #[test]
    fn commented_out_declarations_do_not_count() {
        let pom = r#"
        <project><dependencies>
          <!-- <dependency>
            <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
            <optional>true</optional>
          </dependency> -->
        </dependencies></project>
        "#;
        assert!(!declares_optional(pom, "org.slf4j", "slf4j-api"));
    }

    /// An `<exclusions>` block carries its own groupId/artifactId. Reading those as the
    /// dependency's own coordinate would attribute a neighbouring dependency's optional flag
    /// to whatever it excludes.
    #[test]
    fn exclusions_do_not_shadow_the_dependency_coordinate() {
        let pom = r#"
        <project><dependencies>
          <dependency>
            <groupId>com.example</groupId><artifactId>thing</artifactId>
            <optional>true</optional>
            <exclusions>
              <exclusion>
                <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
              </exclusion>
            </exclusions>
          </dependency>
        </dependencies></project>
        "#;
        assert!(declares_optional(pom, "com.example", "thing"));
        assert!(!declares_optional(pom, "org.slf4j", "slf4j-api"));
    }

    /// netty-common puts `<optional>` after `<exclusions>`. Truncating the block at the
    /// exclusions (rather than blanking them) hid the flag on 18 of 4000 cached real POMs.
    #[test]
    fn optional_after_exclusions_is_found() {
        let pom = r#"
        <project><dependencies>
          <dependency>
            <groupId>org.apache.logging.log4j</groupId>
            <artifactId>log4j-1.2-api</artifactId>
            <scope>compile</scope>
            <exclusions>
              <exclusion><artifactId>mail</artifactId><groupId>javax.mail</groupId></exclusion>
              <exclusion><artifactId>jms</artifactId><groupId>javax.jms</groupId></exclusion>
            </exclusions>
            <optional>true</optional>
          </dependency>
        </dependencies></project>
        "#;
        assert!(declares_optional(
            pom,
            "org.apache.logging.log4j",
            "log4j-1.2-api"
        ));
        assert!(!declares_optional(pom, "javax.mail", "mail"));
    }

    #[test]
    fn attributes_and_self_closing_tags_do_not_derail_the_scan() {
        let pom = r#"
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <dependencies>
            <dependency >
              <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
              <optional>true</optional>
            </dependency>
          </dependencies>
        </project>
        "#;
        assert!(declares_optional(pom, "org.slf4j", "slf4j-api"));
    }

    /// Maven and Coursier put the POM next to the JAR; Gradle gives each artifact of a version
    /// its own checksum directory, so the POM is one directory over. Both are probed.
    #[test]
    fn locate_finds_the_pom_in_both_cache_layouts() {
        let root = std::env::temp_dir().join(format!("uika-pom-locate-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);

        let m2 = root.join("m2/com/example/thing/1.0");
        std::fs::create_dir_all(&m2).unwrap();
        std::fs::write(m2.join("thing-1.0.jar"), b"").unwrap();
        std::fs::write(m2.join("thing-1.0.pom"), b"<project/>").unwrap();
        assert_eq!(
            locate(&m2.join("thing-1.0.jar"), "thing", "1.0"),
            Some(m2.join("thing-1.0.pom"))
        );

        let version = root.join("gradle/com.example/thing/1.0");
        let jar_dir = version.join("aaaaaaaa");
        let pom_dir = version.join("bbbbbbbb");
        std::fs::create_dir_all(&jar_dir).unwrap();
        std::fs::create_dir_all(&pom_dir).unwrap();
        std::fs::write(jar_dir.join("thing-1.0.jar"), b"").unwrap();
        std::fs::write(pom_dir.join("thing-1.0.pom"), b"<project/>").unwrap();
        assert_eq!(
            locate(&jar_dir.join("thing-1.0.jar"), "thing", "1.0"),
            Some(pom_dir.join("thing-1.0.pom"))
        );

        // A different version's POM must not answer for this one.
        assert_eq!(locate(&jar_dir.join("thing-1.0.jar"), "thing", "2.0"), None);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn truncated_pom_does_not_panic() {
        assert!(!declares_optional(
            "<project><dependencies><dependency>",
            "g",
            "n"
        ));
        assert!(!declares_optional("", "g", "n"));
        assert!(!declares_optional("<!-- unterminated", "g", "n"));
    }
}
