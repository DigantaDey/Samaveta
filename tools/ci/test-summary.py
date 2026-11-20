#!/usr/bin/env python3
"""Summarise JUnit XML results into a single line CI can surface as an annotation.

Why this exists: Gradle's console output is long enough that the interesting number scrolls away,
and on a runner whose logs cannot be downloaded, the check-run annotation is the only readable
channel. This turns test results into one `::notice::` (or `::error::`) line.

Usage: test-summary.py <label> <results-dir> [<results-dir> ...]
Exit code 1 when any failure/error is found *or* when no results were produced at all — a test task
that silently ran nothing must not look like success.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    if len(sys.argv) < 3:
        print("usage: test-summary.py <label> <results-dir>...")
        return 2
    label = sys.argv[1]
    xmls = []
    for base in sys.argv[2:]:
        xmls.extend(glob.glob(os.path.join(base, "TEST-*.xml")))

    if not xmls:
        print(f"::error::{label}: no JUnit XML results found — the tests did not run")
        return 1

    tests = failures = errors = skipped = 0
    failing = []
    for path in xmls:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as broken:
            print(f"::error::{label}: unreadable result file {os.path.basename(path)}: {broken}")
            return 1
        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        for suite in suites:
            tests += int(suite.get("tests", 0))
            failures += int(suite.get("failures", 0))
            errors += int(suite.get("errors", 0))
            skipped += int(suite.get("skipped", 0))
            for case in suite.findall("testcase"):
                if case.find("failure") is not None or case.find("error") is not None:
                    failing.append(f"{case.get('classname')}.{case.get('name')}")

    summary = (f"{label}: tests {tests}, failures {failures}, errors {errors}, skipped {skipped}, "
               f"suites {len(xmls)}")
    if failures or errors:
        print(f"::error::{summary}")
        for name in failing[:40]:
            print(f"::error::{label}: FAILED {name}")
        return 1
    if tests == 0:
        print(f"::error::{label}: 0 tests executed")
        return 1
    print(f"::notice::{summary}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
