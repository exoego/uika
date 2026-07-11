use clap::{Parser, Subcommand};
use std::path::PathBuf;

#[derive(Parser)]
#[command(
    name = "uika",
    about = "Unseen Incompatibility, Kick Away: catch NoSuchMethodError and friends statically before you ship",
    version
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
    /// (exit codes: 0=clean, 1=violations found, 2=error)
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
        #[arg(long)]
        json: bool,
    },
    /// Compare resolved classpath JSON files before and after a dependency update,
    /// then detect breaking references from all artifacts whose versions changed
    /// (exit codes: 0=clean, 1=violations found, 2=error)
    UpgradeCheck {
        /// Resolved classpath JSON before the update (uikaDumpClasspath output)
        #[arg(long)]
        before: PathBuf,
        /// Resolved classpath JSON after the update
        #[arg(long)]
        after: PathBuf,
        #[arg(long)]
        json: bool,
    },
    /// Debugging: dump the API surface extracted from a JAR or directory
    Dump { path: PathBuf },
}
