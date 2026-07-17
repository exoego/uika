use clap::{Parser, Subcommand, ValueEnum};
use std::path::PathBuf;

/// When a check run should exit non-zero. Only affects the exit code; the report
/// (and its reachable/not-proven-reachable split) is printed the same way regardless.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default, ValueEnum)]
pub enum FailOn {
    /// Always exit 0; just print the violations as warnings.
    Never,
    /// Exit 1 only when a violation is reachable from the application
    /// (💥; a violation that is not proven reachable does not fail the run).
    /// Falls back to `any` when reachability was not computed (no application
    /// roots) or no application root matched a scanned class.
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
    /// (removals, access narrowing, static/instance changes, newly-final classes/members)
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
        /// Old-version JARs (the ones bound at compile time). May be specified multiple times
        #[arg(long, required = true)]
        old: Vec<PathBuf>,
        /// New-version JARs (the ones resolved on the runtime classpath). May be specified multiple times
        #[arg(long, required = true)]
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
    },
    /// Debugging: dump the API surface extracted from a JAR or directory
    Dump { path: PathBuf },
}
