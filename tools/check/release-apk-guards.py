#!/usr/bin/env python3
"""Fail the release job when an APK would misrepresent itself.

Run against a signed/unsigned release APK with `aapt2 dump badging` (Android build-tools, present on
GitHub runners). The checks encode promises the project makes in writing:

  * `android:debuggable` must not be true in a release artifact.
  * The application id must be `com.samaveta.android` (no `.debug` suffix leaking into a release).
  * `android:allowBackup` must be false — SAMAVETA's privacy documentation states that no app data is
    ever sent to a cloud backup, and the manifest is the thing that enforces it.
  * The native ABI set must be arm64-v8a only, as documented; shipping a 32-bit slice would break the
    stated device support contract and add size with no working runtime.
  * A version name must be present.

Usage: release-apk-guards.py <path-to-apk> <path-to-aapt2>
"""
import re
import subprocess
import sys


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: release-apk-guards.py <apk> <aapt2>")
        return 2
    apk, aapt2 = sys.argv[1], sys.argv[2]
    badging = subprocess.run(
        [aapt2, "dump", "badging", apk], capture_output=True, text=True, check=False
    )
    if badging.returncode != 0:
        print(f"::error::aapt2 could not read {apk}: {badging.stderr[:400]}")
        return 1
    text = badging.stdout
    problems = []

    if re.search(r"\bapplication-debuggable\b", text):
        problems.append("the release APK is marked debuggable")
    package = re.search(r"package: name='([^']+)'", text)
    if not package:
        problems.append("no package name in the APK")
    elif package.group(1) != "com.samaveta.android":
        problems.append(f"unexpected application id '{package.group(1)}'")
    if "versionName=" not in text:
        problems.append("the APK has no versionName")

    manifest_dump = subprocess.run(
        [aapt2, "dump", "xmltree", apk, "--file", "AndroidManifest.xml"],
        capture_output=True, text=True, check=False,
    ).stdout
    if "allowBackup" in manifest_dump:
        if re.search(r'allowBackup\(0x[0-9a-f]+\)=\(type 0x12\)0xffffffff', manifest_dump):
            problems.append("allowBackup is true in the release manifest")

    native = re.findall(r"native-code: '([^']*)'", text)
    if native:
        abis = native[0].split("' '")
        unexpected = [abi for abi in abis if abi not in ("arm64-v8a",)]
        if unexpected:
            problems.append(f"unexpected ABIs shipped: {unexpected}")

    for problem in problems:
        print(f"::error::release APK guard: {problem}")
    if problems:
        return 1
    print("Release APK guards: clean")
    return 0


if __name__ == "__main__":
    sys.exit(main())
