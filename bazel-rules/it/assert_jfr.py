"""Assertions over the report produced from a JFR-recorded test run.

An empty conversion is the failure mode worth guarding: jdk.ClassLoad is disabled in the
default JFC profile, so a recording made without the injected settings converts to nothing
and every downstream step still succeeds, just with no evidence in it.
"""

import re
import sys


def fail(message):
    print("assertion failed: " + message, file=sys.stderr)
    sys.exit(1)


with open(sys.argv[1], encoding="utf-8") as handle:
    report = handle.read()

converted = re.search(r"converted .* \((\d+) jdk\.ClassLoad events\)", report)
if not converted:
    fail("no recording was converted")
if int(converted.group(1)) == 0:
    fail("the recording converted to zero jdk.ClassLoad events")

# The whole point of the evidence: the selenium class the broken references live in was
# observed loading, so they leave the not-proven-reachable tier and fail --fail-on reachable.
reachable = re.search(r"💥 (\d+) reachable", report)
if not reachable:
    fail("the report has no reachable count")
if int(reachable.group(1)) == 0:
    fail("load evidence did not promote any violation to reachable")
