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

use rustc_hash::FxHashSet;
use std::ffi::OsStr;
use std::path::{Path, PathBuf};

/// Every `"group:name"` this POM declares optional without also requiring it unconditionally.
///
/// One pass answers every coordinate, so the caller reads a POM once per referencing artifact
/// rather than once per removed coordinate.
///
/// Deliberately loose in two directions, because the caller only rewords advice and never
/// suppresses a violation:
///
/// - A declaration inside a `<profile>` counts, and profile activation is not evaluated
///   (google-auth declares its slf4j-api optional inside an `activeByDefault` profile, which is
///   the shape that motivated this).
/// - Inherited declarations are not followed. A parent POM declaring the dependency optional
///   reads as not-optional here, which keeps the original wording -- the safe direction.
///
/// It is NOT loose about a coordinate declared both ways. An always-active declaration that is
/// not optional settles it, because profile looseness is otherwise the false-positive direction:
/// netty-transport-native-epoll requires netty-transport-native-unix-common at top level and
/// additionally declares the classifier-ed native variant optional inside its OS profiles.
///
/// Namespace-PREFIXED element names (`<m:dependency>`) are not matched, so such a POM reads as
/// not-optional. That is the safe direction, and Maven tooling writes the default namespace.
pub fn optional_dependencies(pom: &str) -> FxHashSet<String> {
    // `<dependencyManagement>` sets versions for dependencies declared elsewhere and a plugin's
    // `<dependencies>` belongs to the plugin, so neither is a dependency of this artifact.
    // Blanking rather than collecting spans is what makes an unterminated one swallow the
    // remainder, the direction that reads as not-optional.
    let text = blank_element(&blank_uninterpreted(pom), "dependencyManagement");
    let text = blank_element(&text, "plugins");

    let optional = declarations(&text, true);
    if optional.is_empty() {
        return optional;
    }
    // Second pass over the always-active declarations only. `<profiles>` is blanked rather than
    // span-tested for the same reason as above.
    let required = declarations(&blank_element(&text, "profiles"), false);
    optional.difference(&required).cloned().collect()
}

/// Whether `pom` declares `group:name` as an optional dependency. See
/// [`optional_dependencies`], which this wraps.
pub fn declares_optional(pom: &str, group: &str, name: &str) -> bool {
    optional_dependencies(pom).contains(&format!("{group}:{name}"))
}

/// The `"group:name"` of every `<dependency>` in `text` whose `<optional>` is `true` when
/// `want_optional`, or is not, when it is false.
///
/// A block carrying a `<classifier>` is skipped either way: a classifier names a different
/// artifact file, so its optionality says nothing about the plain coordinate.
fn declarations(text: &str, want_optional: bool) -> FxHashSet<String> {
    let mut found = FxHashSet::default();
    let mut cursor = 0;
    while let Some(open) = find_element(&text[cursor..], "dependency") {
        let start = cursor + open;
        let Some(end) = element_end(&text[start..], "dependency") else {
            break;
        };
        cursor = start + end;
        // An `<exclusion>` carries its own groupId/artifactId, which would otherwise be read as
        // the dependency's own coordinate. Blanked rather than truncated at, because
        // `<optional>` is free to come after the exclusions and netty puts it there.
        let block = blank_element(&text[start..cursor], "exclusions");
        if child_text(&block, "classifier").is_some_and(|c| !c.is_empty())
            || (child_text(&block, "optional") == Some("true")) != want_optional
        {
            continue;
        }
        if let (Some(group), Some(name)) = (
            child_text(&block, "groupId"),
            child_text(&block, "artifactId"),
        ) {
            found.insert(format!("{group}:{name}"));
        }
    }
    found
}

/// The POM sitting next to `jar` in a local artifact cache, if it is there.
///
/// Two layouts are probed, which together cover the caches uika actually scans against:
/// the POM as a direct sibling of the JAR (Maven `~/.m2`, Coursier), and the POM one
/// directory over (Gradle's `modules-2/files-2.1/<g>/<n>/<v>/<sha1>/`, which gives each
/// artifact of a version its own checksum directory).
///
/// The sibling-directory walk requires the two directories above `jar` to spell `<n>/<v>`,
/// because the file name alone does not identify an artifact: without the check, a jar in any
/// flat `lib/` directory answers with a same-named POM from a neighbouring directory that
/// belongs to a different group. It also makes the walk Gradle-only, which is where it pays --
/// in a Maven layout the sibling probe has already covered the one path it could match.
pub fn locate(jar: &Path, name: &str, version: &str) -> Option<PathBuf> {
    let file_name = format!("{name}-{version}.pom");
    // `parent()` of a bare file name is Some(""), which would turn the sibling probe into a
    // CWD-relative read: a stray POM in whatever directory the CLI was invoked from must not
    // decide the advice.
    let dir = jar.parent().filter(|d| !d.as_os_str().is_empty())?;

    let sibling = dir.join(&file_name);
    if sibling.is_file() {
        return Some(sibling);
    }

    let version_dir = dir.parent()?;
    if version_dir.file_name()? != OsStr::new(version)
        || version_dir.parent()?.file_name()? != OsStr::new(name)
    {
        return None;
    }
    // Lowest path wins rather than whatever `read_dir` yields first: two checksum directories
    // can both hold the POM, and the advice built from it is part of the report's grouping key.
    let mut found: Option<PathBuf> = None;
    for entry in std::fs::read_dir(version_dir).ok()?.flatten() {
        if entry.path() == dir {
            continue;
        }
        let candidate = entry.path().join(&file_name);
        if candidate.is_file() && found.as_ref().is_none_or(|best| candidate < *best) {
            found = Some(candidate);
        }
    }
    found
}

/// Blank the regions whose contents are not markup: `<!-- ... -->`, `<![CDATA[ ... ]]>` and
/// `<? ... ?>`. Each can legally contain something that reads like a dependency declaration --
/// a POM's `<description>` is free to wrap example XML in CDATA -- and scanning them would be a
/// way this file reports optional for a dependency the artifact never declared. A DTD internal
/// subset can carry the same thing inside an `<!ENTITY>`, but modern Maven rejects a DOCTYPE
/// outright, so it is not blanked.
///
/// Blanking rather than deleting keeps every other offset in the result equal to the same
/// offset in the input, so removing a region can never splice two neighbouring elements
/// together.
fn blank_uninterpreted(pom: &str) -> String {
    const REGIONS: [(&str, &str); 3] = [("<!--", "-->"), ("<![CDATA[", "]]>"), ("<?", "?>")];

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

/// Offset of the next `<tag>` open tag in `text`. Matches `<tag>` and `<tag attr=..>` but not
/// `<tagFoo>`, and ignores self-closing `<tag/>` (an empty element declares nothing).
fn find_element(text: &str, tag: &str) -> Option<usize> {
    let open = format!("<{tag}");
    let bytes = text.as_bytes();
    let mut i = 0;
    loop {
        let at = i + text[i..].find(&open)?;
        let after = at + open.len();
        match bytes.get(after) {
            Some(b'>') => return Some(at),
            Some(c) if c.is_ascii_whitespace() => {
                // An attribute list still opens the element unless it self-closes.
                let close = after + text[after..].find('>')?;
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
    let close = format!("</{tag}>");
    let mut depth = 0usize;
    let mut i = 0;
    loop {
        let next_open = find_element(&text[i..], tag).map(|o| i + o);
        let next_close = i + text[i..].find(&close)?;
        match next_open {
            Some(open) if open < next_close => {
                depth += 1;
                i = open + 1 + tag.len();
            }
            // depth 1 is the element's own open tag. depth 0 means `find_element` never matched
            // it (a leading `<tag/>`, or an attribute list with no `>`), so bound at the first
            // close rather than decrementing into an underflow.
            _ if depth <= 1 => return Some(next_close + close.len()),
            _ => {
                depth -= 1;
                i = next_close + close.len();
            }
        }
    }
}

/// Text of the first `<tag>` inside `block`, trimmed. Nesting is not tracked, which is fine for
/// the leaf elements read here (`groupId`, `artifactId`, `classifier`, `optional`) once the
/// caller has blanked `<exclusions>`.
fn child_text<'a>(block: &'a str, tag: &str) -> Option<&'a str> {
    let open = find_element(block, tag)?;
    let value_start = open + block[open..].find('>')? + 1;
    let close = block[value_start..].find(&format!("</{tag}>"))?;
    Some(block[value_start..value_start + close].trim())
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

    /// The shape of netty-transport-native-epoll, which is in real caches: the plain coordinate
    /// is a hard top-level requirement and the classifier-ed native variant is optional inside
    /// OS-gated profiles. Either rule alone answers this correctly; both are pinned because
    /// each covers cases the other does not.
    #[test]
    fn an_unconditional_requirement_beats_a_profile_scoped_optional() {
        let pom = r#"
        <project>
          <dependencies>
            <dependency>
              <groupId>io.netty</groupId><artifactId>netty-transport-native-unix-common</artifactId>
              <version>${project.version}</version>
            </dependency>
          </dependencies>
          <profiles><profile>
            <id>linux</id>
            <dependencies>
              <dependency>
                <groupId>io.netty</groupId><artifactId>netty-transport-native-unix-common</artifactId>
                <classifier>${jni.classifier}</classifier>
                <optional>true</optional>
              </dependency>
            </dependencies>
          </profile></profiles>
        </project>
        "#;
        assert!(!declares_optional(
            pom,
            "io.netty",
            "netty-transport-native-unix-common"
        ));
    }

    /// A classifier names a different artifact file, so its optionality says nothing about the
    /// plain coordinate.
    #[test]
    fn a_classifier_ed_optional_does_not_cover_the_plain_coordinate() {
        let pom = r#"
        <project><dependencies>
          <dependency>
            <groupId>g</groupId><artifactId>a</artifactId>
            <classifier>linux-x86_64</classifier><optional>true</optional>
          </dependency>
        </dependencies></project>
        "#;
        assert!(!declares_optional(pom, "g", "a"));
    }

    /// Only an ALWAYS-ACTIVE requirement overrides. Both of google-auth's slf4j-api
    /// declarations sit in profiles, so the motivating case still reads optional.
    #[test]
    fn a_profile_scoped_requirement_does_not_override() {
        let pom = r#"
        <project><profiles>
          <profile>
            <id>slf4j2x</id>
            <activation><activeByDefault>true</activeByDefault></activation>
            <dependencies><dependency>
              <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
              <optional>true</optional>
            </dependency></dependencies>
          </profile>
          <profile>
            <id>slf4j2x-test</id>
            <dependencies><dependency>
              <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
            </dependency></dependencies>
          </profile>
        </profiles></project>
        "#;
        assert!(declares_optional(pom, "org.slf4j", "slf4j-api"));
    }

    /// A plugin's `<dependencies>` is the plugin's classpath, never this artifact's.
    #[test]
    fn plugin_dependencies_are_not_this_artifacts_dependencies() {
        for section in [
            "<build><plugins><plugin>",
            "<build><pluginManagement><plugins><plugin>",
            "<reporting><plugins><plugin>",
        ] {
            let pom = format!(
                "<project>{section}<dependencies><dependency>
                   <groupId>g</groupId><artifactId>a</artifactId><optional>true</optional>
                 </dependency></dependencies></project>"
            );
            assert!(!declares_optional(&pom, "g", "a"), "{section}");
        }
    }

    /// XML allows whitespace before the `>` of an end tag, so `element_end` can fail on a
    /// well-formed POM. Losing the `<dependencyManagement>` bound must not promote its entries
    /// to real declarations -- an unterminated element swallows the remainder instead.
    #[test]
    fn an_unbounded_dependency_management_still_excludes_its_entries() {
        for close in ["</dependencyManagement >", ""] {
            let pom = format!(
                r#"<project>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
                      <optional>true</optional>
                    </dependency>
                  </dependencies>{close}
                </project>"#
            );
            assert!(
                !declares_optional(&pom, "org.slf4j", "slf4j-api"),
                "{close}"
            );
        }
    }

    /// A processing instruction's content is not markup, the same as a comment or CDATA.
    #[test]
    fn processing_instruction_contents_are_not_declarations() {
        let pom = r#"<?xml version="1.0" encoding="UTF-8"?>
        <project>
          <?tool <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
                 <optional>true</optional></dependency> ?>
          <dependencies><dependency>
            <groupId>com.example</groupId><artifactId>thing</artifactId><optional>true</optional>
          </dependency></dependencies>
        </project>"#;
        assert!(!declares_optional(pom, "org.slf4j", "slf4j-api"));
        assert!(declares_optional(pom, "com.example", "thing"));
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

    /// A self-closing `<dependency/>` declares nothing and must be stepped over rather than
    /// opening an element whose `element_end` then swallows the real declaration after it.
    #[test]
    fn attributes_and_self_closing_tags_do_not_derail_the_scan() {
        let pom = r#"
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <dependencies>
            <dependency/>
            <dependency scope="compile" />
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

        // A POM for another version, present and reachable by the same walk, must not answer.
        std::fs::write(pom_dir.join("thing-2.0.pom"), b"<project/>").unwrap();
        assert_eq!(locate(&jar_dir.join("thing-1.0.jar"), "thing", "2.0"), None);
        // A bare file name would make the sibling probe read relative to the CWD.
        assert_eq!(locate(Path::new("thing-1.0.jar"), "thing", "1.0"), None);
        let _ = std::fs::remove_dir_all(&root);
    }

    /// The file name alone does not identify an artifact. Outside a cache layout the walk must
    /// stay quiet rather than answer with a same-named POM belonging to another group.
    #[test]
    fn locate_does_not_cross_into_an_unrelated_directory() {
        let root = std::env::temp_dir().join(format!("uika-pom-cross-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(root.join("libs")).unwrap();
        std::fs::create_dir_all(root.join("other")).unwrap();
        std::fs::write(root.join("libs/thing-1.0.jar"), b"").unwrap();
        std::fs::write(root.join("other/thing-1.0.pom"), b"<project/>").unwrap();
        assert_eq!(
            locate(&root.join("libs/thing-1.0.jar"), "thing", "1.0"),
            None
        );
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
