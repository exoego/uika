use clap::{Parser, Subcommand, ValueEnum};
use std::path::PathBuf;

/// When a check run should exit non-zero. Only affects the exit code; the report
/// (and its reachable/not-proven-reachable split) is printed the same way regardless.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default, ValueEnum)]
pub enum FailOn {
    /// Always exit 0; just print the violations as warnings.
    Never,
    /// Exit 1 only when a violation is in the 💥 tier (likely to break). A violation
    /// that is not proven reachable (⚠️) or latent (💤: the class is reachable but no
    /// scanned bytecode invokes the newly-abstract member, so it cannot throw yet)
    /// does not fail the run. The reachable axis falls back to `any` when reachability
    /// was not computed (no application roots) or no application root matched a
    /// scanned class; the latent axis is scan-derived and never falls back.
    Reachable,
    /// Exit 1 when any violation is found, regardless of reachability (default, strictest).
    #[default]
    Any,
}

#[derive(Parser)]
#[command(
    name = "uika",
    about = "Unseen Incompatibility, Kick Away: catch NoSuchMethodError and friends statically before you ship",
    // Release builds inject the version from the git tag via UIKA_VERSION at
    // compile time; Cargo.toml stays at the 0.0.0-dev placeholder.
    version = option_env!("UIKA_VERSION").unwrap_or(env!("CARGO_PKG_VERSION"))
)]
pub struct Cli {
    #[command(subcommand)]
    pub command: Command,
}

#[derive(Subcommand)]
pub enum Command {
    /// List breaking changes between old and new library JARs
    /// (removals, access narrowing, static/instance changes, newly-final/abstract classes/members, class<->interface flips)
    Diff {
        /// Old-version JAR
        old: PathBuf,
        /// New-version JAR
        new: PathBuf,
        #[arg(long)]
        json: bool,
    },
    /// Detect uses of breaking changes from classpath or application classes
    /// (exit codes: 0=clean, 1=violations found per --fail-on, 2=error)
    Check {
        /// Old-version JARs (the ones bound at compile time). May be specified multiple times.
        /// Mutually exclusive with a JDK-upgrade pair, which supplies both sides itself
        #[arg(
            long,
            required_unless_present = "jdk_release_old",
            conflicts_with = "jdk_release_old"
        )]
        old: Vec<PathBuf>,
        /// New-version JARs (the ones resolved on the runtime classpath). May be specified multiple times.
        /// Mutually exclusive with a JDK-upgrade pair
        #[arg(
            long,
            required_unless_present = "jdk_release_new",
            conflicts_with = "jdk_release_new"
        )]
        new: Vec<PathBuf>,
        /// Transitive dependency JARs (':'-separated, may be specified multiple times)
        #[arg(long, value_delimiter = ':')]
        classpath: Vec<PathBuf>,
        /// Build outputs for the current project (class directories or JARs, may be specified multiple times)
        #[arg(long)]
        app: Vec<PathBuf>,
        /// Resolved classpath JSON emitted by the uika build-tool plugins
        /// (Gradle/sbt uikaDumpClasspath, Maven uika:dump-classpath).
        /// Included artifacts and build outputs are added to the scan targets
        #[arg(long)]
        classpath_file: Vec<PathBuf>,
        /// TOML file(s) of known false positives to suppress (e.g. reflection-only member
        /// access). May be specified multiple times; rules from all files are merged
        #[arg(long)]
        exclude_file: Vec<PathBuf>,
        #[arg(long)]
        json: bool,
        /// When to exit non-zero: never, reachable (only reachable violations),
        /// or any (any violation, default)
        #[arg(long, value_enum, default_value_t = FailOn::default())]
        fail_on: FailOn,
        /// Resolve hierarchy escapes into the JDK API of this release (8-35, older than
        /// the installed JDK) instead of counting them unverified. Reads ct.sym from
        /// $UIKA_JDK if set (a JDK home or a ct.sym file), else $JAVA_HOME
        #[arg(long, value_parser = clap::value_parser!(u32).range(8..=35))]
        jdk_release: Option<u32>,
        /// Check a JDK upgrade itself: resolve against this release as the old side.
        /// Requires --jdk-release-new. The JDK API becomes the compared pair, so --old
        /// and --new must be omitted. Reads ct.sym for releases below the running JDK and
        /// jmods for its own release
        #[arg(long, requires = "jdk_release_new", value_parser = clap::value_parser!(u32).range(8..=35))]
        jdk_release_old: Option<u32>,
        /// The new side of a JDK upgrade check. Requires --jdk-release-old
        #[arg(long, requires = "jdk_release_old", value_parser = clap::value_parser!(u32).range(8..=35))]
        jdk_release_new: Option<u32>,
        /// Evaluation: stream every reference verdict (ok/unknown/broken) as JSON Lines
        /// to this file, for answer-checking against a real JVM (tools/jvm-probe)
        #[arg(long)]
        verdicts_json: Option<PathBuf>,
    },
    /// Compare resolved classpath JSON files before and after a dependency update,
    /// then detect breaking references from all artifacts whose versions changed
    /// (exit codes: 0=clean, 1=violations found per --fail-on, 2=error)
    UpgradeCheck {
        /// Resolved classpath JSON before the update (uikaDumpClasspath output)
        #[arg(long)]
        before: PathBuf,
        /// Resolved classpath JSON after the update
        #[arg(long)]
        after: PathBuf,
        /// TOML file(s) of known false positives to suppress (e.g. reflection-only member
        /// access). May be specified multiple times; rules from all files are merged
        #[arg(long)]
        exclude_file: Vec<PathBuf>,
        #[arg(long)]
        json: bool,
        /// When to exit non-zero: never, reachable (only reachable violations),
        /// or any (any violation, default)
        #[arg(long, value_enum, default_value_t = FailOn::default())]
        fail_on: FailOn,
        /// Resolve hierarchy escapes into the JDK API of this release (8-35, older than
        /// the installed JDK) instead of counting them unverified. Reads ct.sym from
        /// $UIKA_JDK if set (a JDK home or a ct.sym file), else $JAVA_HOME
        #[arg(long, value_parser = clap::value_parser!(u32).range(8..=35))]
        jdk_release: Option<u32>,
        /// Evaluation: stream every reference verdict (ok/unknown/broken) as JSON Lines
        /// to this file, for answer-checking against a real JVM (tools/jvm-probe)
        #[arg(long)]
        verdicts_json: Option<PathBuf>,
        /// Check the union of all modules' classpaths as one flat classpath instead of
        /// checking each module against its own resolved classpath. Also the automatic
        /// fallback when the dumps carry no per-module artifact data
        #[arg(long)]
        merged: bool,
    },
    /// Debugging: dump the API surface extracted from a JAR or directory
    Dump { path: PathBuf },
}

#[cfg(test)]
mod tests {
    use super::{Cli, Command};
    use clap::Parser;

    fn parse(args: &[&str]) -> Result<Cli, clap::Error> {
        Cli::try_parse_from(std::iter::once("uika").chain(args.iter().copied()))
    }

    /// A JDK pair supplies both compared sides itself. Accepting --old/--new next to it
    /// would silently ignore them, since only one pair reaches run_check_with_indexes.
    #[test]
    fn a_jdk_pair_and_a_jar_pair_cannot_be_asked_for_at_once() {
        let jdk = parse(&[
            "check",
            "--jdk-release-old",
            "11",
            "--jdk-release-new",
            "17",
            "--classpath",
            "app.jar",
        ])
        .expect("a JDK pair alone is valid");
        match jdk.command {
            Command::Check { old, new, .. } => {
                assert!(old.is_empty() && new.is_empty());
            }
            _ => panic!("expected check"),
        }
        assert!(
            parse(&[
                "check",
                "--old",
                "a.jar",
                "--new",
                "b.jar",
                "--jdk-release-old",
                "11",
                "--jdk-release-new",
                "17",
            ])
            .is_err(),
            "--old/--new must be rejected alongside a JDK pair, not ignored"
        );
        assert!(
            parse(&["check", "--classpath", "app.jar"]).is_err(),
            "without a JDK pair, --old/--new stay required"
        );
        assert!(
            parse(&["check", "--jdk-release-old", "11", "--classpath", "app.jar"]).is_err(),
            "half a JDK pair is not a pair"
        );
    }
}
