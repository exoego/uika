//! Helpers shared by the integration and golden test binaries.

use std::path::PathBuf;

/// Locate a vendored fixture JAR via a crate-relative path (cargo runs test
/// binaries from the crate root). The relative form matters: the path string is
/// interned as the violation `source`, and the golden JSON pins it byte for
/// byte, so an absolute path would make the goldens machine-specific.
pub fn fixture(jar_name: &str) -> PathBuf {
    let path = PathBuf::from("tests/fixtures").join(jar_name);
    assert!(
        path.exists(),
        "fixture not found: {} (tests must run from the cli/ crate root)",
        path.display()
    );
    path
}
