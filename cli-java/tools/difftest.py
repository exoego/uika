#!/usr/bin/env python3
"""Differential test: run the Rust CLI and the Java CLI on the same inputs and compare.

The Rust binary is the specification, so any difference in stdout or exit code is a bug in
the port until proven otherwise. Inputs come from the local Gradle cache, which holds real
jars in many versions.

    tools/difftest.py dump  [--limit N]    uika dump on every jar
    tools/difftest.py diff  [--limit N]    uika diff on adjacent versions of each artifact
    tools/difftest.py check [--limit N]    uika check of old -> new against a classpath sample

Environment: UIKA_RUST (default target/release/uika), UIKA_JAVA (default
"java -cp cli-java/build/classes net.exoego.uika.cli.Main"), UIKA_CACHE (default
~/.gradle/caches/modules-2/files-2.1). Run from the repository root.
"""
import argparse
import concurrent.futures
import glob
import json
import os
import random
import re
import shlex
import subprocess
import sys

RUST = shlex.split(os.environ.get("UIKA_RUST", "target/release/uika"))
JAVA = shlex.split(os.environ.get("UIKA_JAVA", "java -XX:TieredStopAtLevel=1 -cp cli-java/build/classes net.exoego.uika.cli.Main"))
CACHE = os.path.expanduser(os.environ.get("UIKA_CACHE", "~/.gradle/caches/modules-2/files-2.1"))


def version_key(v):
    return [int(p) if p.isdigit() else p for p in re.split(r"([0-9]+)", v)]


def artifacts():
    """{(group, name): [(version, jar)]} sorted by version."""
    out = {}
    for group in sorted(os.listdir(CACHE)):
        gp = os.path.join(CACHE, group)
        if not os.path.isdir(gp):
            continue
        for name in sorted(os.listdir(gp)):
            versions = []
            for v in os.listdir(os.path.join(gp, name)):
                jars = sorted(
                    j
                    for j in glob.glob(os.path.join(gp, name, v, "*", "*.jar"))
                    if not j.endswith(("-sources.jar", "-javadoc.jar")) and "profiler_java_agent" not in j
                )
                if jars:
                    versions.append((v, jars[0]))
            if versions:
                try:
                    versions.sort(key=lambda t: version_key(t[0]))
                except TypeError:
                    versions.sort()
                out[(group, name)] = versions
    return out


def run(cmd):
    p = subprocess.run(cmd, capture_output=True)
    return p.returncode, p.stdout, p.stderr


def compare(label, args, normalize=None):
    rc_r, out_r, err_r = run(RUST + args)
    rc_j, out_j, err_j = run(JAVA + args)
    if normalize:
        out_r, out_j = normalize(out_r), normalize(out_j)
    if rc_r == rc_j and out_r == out_j:
        return None
    return {
        "label": label,
        "args": args,
        "rust_exit": rc_r,
        "java_exit": rc_j,
        "rust_out": out_r[:4000].decode("utf-8", "replace"),
        "java_out": out_j[:4000].decode("utf-8", "replace"),
        "java_err": err_j[:2000].decode("utf-8", "replace"),
        "rust_err": err_r[:2000].decode("utf-8", "replace"),
    }


def sorted_lines(data):
    return b"\n".join(sorted(data.split(b"\n")))


def first_difference(a, b):
    la, lb = a.split("\n"), b.split("\n")
    for i, (x, y) in enumerate(zip(la, lb)):
        if x != y:
            return f"line {i + 1}:\n  rust: {x[:300]}\n  java: {y[:300]}"
    return f"length differs: rust {len(la)} lines, java {len(lb)} lines"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["dump", "diff", "check"])
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--jobs", type=int, default=6)
    parser.add_argument("--classpath-size", type=int, default=250)
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--extra", default="", help="extra CLI arguments for check, e.g. '--jdk-release 17'")
    opts = parser.parse_args()

    arts = artifacts()
    all_jars = [jar for versions in arts.values() for _, jar in versions]
    rng = random.Random(opts.seed)
    jobs = []
    if opts.mode == "dump":
        for jar in all_jars:
            # Entry order inside a directory-less jar is the same on both sides, but the
            # comparison is about content, so order is normalized away.
            jobs.append((jar, ["dump", jar], sorted_lines))
    elif opts.mode == "diff":
        for (group, name), versions in arts.items():
            for (v1, j1), (v2, j2) in zip(versions, versions[1:]):
                jobs.append((f"{group}:{name} {v1}->{v2}", ["diff", "--json", j1, j2], None))
                jobs.append((f"{group}:{name} {v1}->{v2} text", ["diff", j1, j2], None))
    else:
        extra = shlex.split(opts.extra)
        for (group, name), versions in arts.items():
            if len(versions) < 2:
                continue
            (v1, j1), (v2, j2) = versions[0], versions[-1]
            sample = rng.sample(all_jars, min(opts.classpath_size, len(all_jars)))
            # Same-group jars are the likeliest referencers.
            same_group = [jar for (g, _), vs in arts.items() if g == group for _, jar in vs]
            classpath = ":".join(dict.fromkeys(same_group + sample))
            jobs.append((f"{group}:{name} {v1}->{v2}", ["check", "--json", "--old", j1, "--new", j2, "--classpath", classpath] + extra, None))
    if opts.limit:
        rng.shuffle(jobs)
        jobs = jobs[: opts.limit]

    failures = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=opts.jobs) as pool:
        futures = {pool.submit(compare, label, args, norm): label for label, args, norm in jobs}
        done = 0
        for future in concurrent.futures.as_completed(futures):
            done += 1
            result = future.result()
            if result:
                failures.append(result)
                print(f"DIFF {result['label']} (exit rust={result['rust_exit']} java={result['java_exit']})")
                print("  " + first_difference(result["rust_out"], result["java_out"]).replace("\n", "\n  "))
                if result["java_exit"] not in (0, 1) and result["java_err"]:
                    print("  java stderr: " + result["java_err"][:600].replace("\n", "\n    "))
            if done % 100 == 0:
                print(f"... {done}/{len(jobs)} compared, {len(failures)} differ", file=sys.stderr)
    print(f"{len(jobs)} compared, {len(failures)} differ")
    if failures:
        with open("cli-java/build/difftest-failures.json", "w") as f:
            json.dump(failures, f, indent=1)
        print("details: cli-java/build/difftest-failures.json")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
