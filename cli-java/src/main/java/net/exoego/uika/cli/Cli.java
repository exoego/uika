package net.exoego.uika.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Command-line parsing. The surface and its help text are the ones the native CLI shipped with. */
final class Cli {
    private Cli() {}

    /** When a check run should exit non-zero. Only affects the exit code, never the report. */
    enum FailOn {
        /** Always exit 0. */
        NEVER,
        /** Exit 1 only when a violation is in the likely-to-break tier. */
        REACHABLE,
        /** Exit 1 when any violation is found (default, strictest). */
        ANY;

        static FailOn parse(String value) {
            return switch (value) {
                case "never" -> NEVER;
                case "reachable" -> REACHABLE;
                case "any" -> ANY;
                default -> null;
            };
        }
    }

    sealed interface Command permits Diff, Check, UpgradeCheck, DumpApi, Print {}

    record Diff(String oldJar, String newJar, boolean json) implements Command {}

    record Check(
            List<String> oldJars,
            List<String> newJars,
            List<String> classpath,
            List<String> app,
            List<String> classpathFile,
            List<String> excludeFile,
            boolean json,
            FailOn failOn,
            Integer jdkRelease,
            Integer jdkReleaseOld,
            Integer jdkReleaseNew,
            String verdictsJson,
            List<String> classLoadLog,
            String draftExcludeFile)
            implements Command {}

    record UpgradeCheck(
            String before,
            String after,
            List<String> excludeFile,
            boolean json,
            FailOn failOn,
            Integer jdkRelease,
            String verdictsJson,
            boolean mergedClasspath,
            List<String> classLoadLog,
            String draftExcludeFile)
            implements Command {}

    record DumpApi(String path) implements Command {}

    /** Help or version: text for stdout, exit 0. */
    record Print(String text) implements Command {}

    /** A usage error: message for stderr, exit 2. */
    static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message, null, false, false);
        }
    }

    static String version() {
        String version = Cli.class.getPackage().getImplementationVersion();
        return version == null ? "0.0.0-dev" : version;
    }

    static Command parse(String[] args) throws UsageException {
        if (args.length == 0) {
            throw new UsageException(help("root", false));
        }
        String first = args[0];
        switch (first) {
            case "-h":
                return new Print(help("root", false));
            case "--help":
                return new Print(help("root", true));
            case "-V", "--version":
                return new Print("uika " + version() + "\n");
            case "help":
                if (args.length > 1) {
                    return new Print(help(subcommand(args[1]), true));
                }
                return new Print(help("root", true));
            default:
                break;
        }
        if (Options.looksLikeFlag(first)) {
            throw new UsageException("error: unexpected argument '" + first + "' found\n\nUsage: uika <COMMAND>\n\n"
                    + "For more information, try '--help'.\n");
        }
        String command = subcommand(first);
        Options options = new Options(command, args);
        return switch (command) {
            case "diff" -> options.diff();
            case "check" -> options.check();
            case "upgrade-check" -> options.upgradeCheck();
            default -> options.dump();
        };
    }

    private static String subcommand(String name) throws UsageException {
        return switch (name) {
            case "diff", "check", "upgrade-check", "dump" -> name;
            default -> throw new UsageException("error: unrecognized subcommand '" + name + "'\n\nUsage: uika <COMMAND>\n\n"
                    + "For more information, try '--help'.\n");
        };
    }

    static String help(String command, boolean longForm) {
        String resource = "help/" + command + (longForm ? "-long.txt" : "-short.txt");
        try (InputStream in = Cli.class.getResourceAsStream(resource)) {
            if (in == null) {
                return "Usage: uika <COMMAND>\n";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Usage: uika <COMMAND>\n";
        }
    }

    /** One subcommand's arguments, consumed left to right. */
    private static final class Options {
        private final String command;
        private final List<String> positional = new ArrayList<>();
        private final java.util.Map<String, List<String>> values = new java.util.LinkedHashMap<>();
        private final java.util.Set<String> flags = new java.util.HashSet<>();
        private Print print;

        Options(String command, String[] args) throws UsageException {
            this.command = command;
            boolean onlyPositional = false;
            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                if (onlyPositional || !arg.startsWith("-") || arg.equals("-")) {
                    positional.add(arg);
                } else if (arg.equals("--")) {
                    onlyPositional = true;
                } else if (arg.equals("-h")) {
                    print = new Print(help(command, false));
                } else if (arg.equals("--help")) {
                    print = new Print(help(command, true));
                } else if (arg.startsWith("--")) {
                    int eq = arg.indexOf('=');
                    String name = eq < 0 ? arg : arg.substring(0, eq);
                    Kind kind = kind(name);
                    if (kind == null) {
                        throw unexpected(name);
                    }
                    if (kind == Kind.FLAG) {
                        if (eq >= 0) {
                            throw usage("unexpected value '" + arg.substring(eq + 1) + "' for '" + name + "' found; no more were expected");
                        }
                        flags.add(name);
                        continue;
                    }
                    String value;
                    if (eq >= 0) {
                        value = arg.substring(eq + 1);
                    } else if (i + 1 < args.length && !looksLikeFlag(args[i + 1])) {
                        value = args[++i];
                    } else if (i + 1 < args.length && !isKnownFlag(args[i + 1])) {
                        // clap reads a dash-led token as the next argument, never as this
                        // option's value, and an unknown one is the error it reports.
                        throw unexpected(args[i + 1]);
                    } else {
                        throw missingValue(name + " <" + placeholder(name) + ">");
                    }
                    // A path or a choice rejects an empty value up front. A number reaches
                    // its parser, which words the failure itself.
                    if (value.isEmpty() && !isRelease(name)) {
                        throw missingValue(name + " <" + placeholder(name) + ">");
                    }
                    if (name.equals("--classpath")) {
                        // clap splits on the delimiter first, so an empty part is a missing value.
                        for (String part : value.split(":", -1)) {
                            if (part.isEmpty()) {
                                throw missingValue(name + " <" + placeholder(name) + ">");
                            }
                        }
                    }
                    List<String> list = values.computeIfAbsent(name, k -> new ArrayList<>());
                    if (kind == Kind.SINGLE && !list.isEmpty()) {
                        throw usage("the argument '" + name + " <" + placeholder(name) + ">' cannot be used multiple times");
                    }
                    list.add(value);
                } else {
                    throw unexpected(arg);
                }
            }
        }

        private static boolean looksLikeFlag(String arg) {
            return arg.startsWith("-") && !arg.equals("-");
        }

        private static boolean isRelease(String name) {
            return name.equals("--jdk-release") || name.equals("--jdk-release-old") || name.equals("--jdk-release-new");
        }

        private boolean isKnownFlag(String arg) {
            if (arg.equals("--") || arg.equals("-h") || arg.equals("--help")) {
                return true;
            }
            int eq = arg.indexOf('=');
            return arg.startsWith("--") && kind(eq < 0 ? arg : arg.substring(0, eq)) != null;
        }

        /** The positional the command declares, spelled the way clap prints its usage line. */
        private String usageLine() {
            return switch (command) {
                case "diff" -> "diff [OPTIONS] <OLD> <NEW>";
                case "dump" -> "dump <PATH>";
                default -> command + " [OPTIONS]";
            };
        }

        private UsageException unexpected(String arg) {
            String message = "unexpected argument '" + arg + "' found";
            if (looksLikeFlag(arg) && (command.equals("diff") || command.equals("dump"))) {
                message += "\n\n  tip: to pass '" + arg + "' as a value, use '-- " + arg + "'";
            }
            return usage(message);
        }

        private UsageException missingValue(String shown) {
            String hint = shown.startsWith("--fail-on ") ? "\n  [possible values: never, reachable, any]" : "";
            return new UsageException("error: a value is required for '" + shown + "' but none was supplied" + hint
                    + "\n\nFor more information, try '--help'.\n");
        }

        private enum Kind {
            FLAG,
            SINGLE,
            MANY
        }

        private Kind kind(String name) {
            boolean check = command.equals("check");
            boolean upgrade = command.equals("upgrade-check");
            return switch (name) {
                case "--json" -> command.equals("dump") ? null : Kind.FLAG;
                case "--merged-classpath" -> upgrade ? Kind.FLAG : null;
                case "--old", "--new", "--classpath", "--app", "--classpath-file" -> check ? Kind.MANY : null;
                case "--exclude-file", "--class-load-log" -> check || upgrade ? Kind.MANY : null;
                case "--fail-on", "--jdk-release", "--verdicts-json", "--draft-exclude-file" -> check || upgrade ? Kind.SINGLE : null;
                case "--jdk-release-old", "--jdk-release-new" -> check ? Kind.SINGLE : null;
                case "--before", "--after" -> upgrade ? Kind.SINGLE : null;
                default -> null;
            };
        }

        private static String placeholder(String name) {
            return name.substring(2).replace('-', '_').toUpperCase(java.util.Locale.ROOT);
        }

        private UsageException usage(String message) {
            return new UsageException("error: " + message + "\n\nUsage: uika " + usageLine() + "\n\n"
                    + "For more information, try '--help'.\n");
        }

        private List<String> many(String name) {
            return values.getOrDefault(name, List.of());
        }

        private String single(String name) {
            List<String> list = values.get(name);
            return list == null ? null : list.get(0);
        }

        private FailOn failOn() throws UsageException {
            String value = single("--fail-on");
            if (value == null) {
                return FailOn.ANY;
            }
            FailOn parsed = FailOn.parse(value);
            if (parsed == null) {
                throw new UsageException("error: invalid value '" + value + "' for '--fail-on <FAIL_ON>'\n"
                        + "  [possible values: never, reachable, any]\n\nFor more information, try '--help'.\n");
            }
            return parsed;
        }

        private Integer release(String name) throws UsageException {
            String value = single(name);
            if (value == null) {
                return null;
            }
            String shown = "'" + name + " <" + placeholder(name) + ">'";
            long n;
            try {
                n = Long.parseLong(value);
            } catch (NumberFormatException e) {
                String why = value.isEmpty() ? "cannot parse integer from empty string" : "invalid digit found in string";
                throw new UsageException("error: invalid value '" + value + "' for " + shown + ": " + why + "\n\n"
                        + "For more information, try '--help'.\n");
            }
            if (n < Jdk.MIN_RELEASE || n > Jdk.MAX_RELEASE) {
                throw new UsageException("error: invalid value '" + value + "' for " + shown + ": " + value + " is not in 8..=35\n\n"
                        + "For more information, try '--help'.\n");
            }
            return (int) n;
        }

        private void noPositional() throws UsageException {
            if (!positional.isEmpty()) {
                throw unexpected(positional.get(0));
            }
        }

        private void requires(String name, String other) throws UsageException {
            if (values.containsKey(name) && !values.containsKey(other)) {
                throw new UsageException("error: the following required arguments were not provided:\n  " + other + " <"
                        + placeholder(other) + ">\n\nUsage: uika " + command + " [OPTIONS]\n\nFor more information, try '--help'.\n");
            }
        }

        private void conflicts(String name, String other) throws UsageException {
            if (values.containsKey(name) && values.containsKey(other)) {
                throw new UsageException("error: the argument '" + name + " <" + placeholder(name) + ">' cannot be used with '" + other
                        + " <" + placeholder(other) + ">'\n\nUsage: uika " + command + " [OPTIONS]\n\n"
                        + "For more information, try '--help'.\n");
            }
        }

        private void required(List<String> names) throws UsageException {
            StringBuilder missing = new StringBuilder();
            for (String name : names) {
                if (!values.containsKey(name)) {
                    missing.append("  ").append(name).append(" <").append(placeholder(name)).append(">\n");
                }
            }
            if (missing.length() > 0) {
                throw new UsageException("error: the following required arguments were not provided:\n" + missing + "\nUsage: uika "
                        + command + " [OPTIONS]\n\nFor more information, try '--help'.\n");
            }
        }

        Command diff() throws UsageException {
            if (print != null) {
                return print;
            }
            if (positional.size() < 2) {
                throw new UsageException("error: the following required arguments were not provided:\n"
                        + (positional.isEmpty() ? "  <OLD>\n" : "") + "  <NEW>\n\nUsage: uika diff <OLD> <NEW>\n\n"
                        + "For more information, try '--help'.\n");
            }
            if (positional.size() > 2) {
                throw unexpected(positional.get(2));
            }
            return new Diff(positionalValue(0, "<OLD>"), positionalValue(1, "<NEW>"), flags.contains("--json"));
        }

        Command check() throws UsageException {
            if (print != null) {
                return print;
            }
            noPositional();
            // A JDK pair supplies both compared sides itself. Accepting --old/--new next to it
            // would silently ignore them, since only one pair reaches the check.
            conflicts("--old", "--jdk-release-old");
            conflicts("--new", "--jdk-release-new");
            requires("--jdk-release-old", "--jdk-release-new");
            requires("--jdk-release-new", "--jdk-release-old");
            if (!values.containsKey("--jdk-release-old")) {
                required(List.of("--old", "--new"));
            }
            requires("--draft-exclude-file", "--class-load-log");
            List<String> classpath = new ArrayList<>();
            for (String value : many("--classpath")) {
                for (String part : value.split(":", -1)) {
                    classpath.add(part);
                }
            }
            return new Check(
                    many("--old"),
                    many("--new"),
                    classpath,
                    many("--app"),
                    many("--classpath-file"),
                    many("--exclude-file"),
                    flags.contains("--json"),
                    failOn(),
                    release("--jdk-release"),
                    release("--jdk-release-old"),
                    release("--jdk-release-new"),
                    single("--verdicts-json"),
                    many("--class-load-log"),
                    single("--draft-exclude-file"));
        }

        Command upgradeCheck() throws UsageException {
            if (print != null) {
                return print;
            }
            noPositional();
            required(List.of("--before", "--after"));
            requires("--draft-exclude-file", "--class-load-log");
            return new UpgradeCheck(
                    single("--before"),
                    single("--after"),
                    many("--exclude-file"),
                    flags.contains("--json"),
                    failOn(),
                    release("--jdk-release"),
                    single("--verdicts-json"),
                    flags.contains("--merged-classpath"),
                    many("--class-load-log"),
                    single("--draft-exclude-file"));
        }

        Command dump() throws UsageException {
            if (print != null) {
                return print;
            }
            if (positional.isEmpty()) {
                throw new UsageException("error: the following required arguments were not provided:\n  <PATH>\n\n"
                        + "Usage: uika dump <PATH>\n\nFor more information, try '--help'.\n");
            }
            if (positional.size() > 1) {
                throw unexpected(positional.get(1));
            }
            return new DumpApi(positionalValue(0, "<PATH>"));
        }

        private String positionalValue(int index, String placeholder) throws UsageException {
            String value = positional.get(index);
            if (value.isEmpty()) {
                throw missingValue(placeholder);
            }
            return value;
        }
    }
}
