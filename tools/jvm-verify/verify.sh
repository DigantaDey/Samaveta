#!/usr/bin/env bash
# SAMAVETA — offline JVM verification harness.
#
# The development sandbox for this repository blocks Maven Central / Google Maven, so a normal
# Gradle build cannot be resolved locally. This script compiles the dependency-free `core` module
# and its JUnit-style tests with the standalone Kotlin compiler and runs them, so domain logic is
# genuinely executed (not merely written) during development.
#
# Requirements (see docs/DEVELOPMENT_ENVIRONMENT.md for exactly how to obtain them):
#   * a JRE/JDK >= 17 on $JAVA_HOME or $PATH to run the compiler and the tests
#   * `kotlinc` on $PATH, or $KOTLINC pointing at a Kotlin compiler distribution
#
# This script is NOT a substitute for the Gradle/Android build; CI does that. Exit code is the
# verdict: 0 = all tests passed, non-zero = build or test failure.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

KOTLINC="${KOTLINC:-kotlinc}"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/build/jvm-verify}"

if ! command -v "$KOTLINC" >/dev/null 2>&1; then
  echo "error: kotlinc not found. Set KOTLINC=/path/to/kotlinc or put it on PATH." >&2
  echo "       See docs/DEVELOPMENT_ENVIRONMENT.md." >&2
  exit 127
fi

JDK_HOME_ARG=()
if [[ -n "${JAVA_HOME:-}" ]]; then
  JDK_HOME_ARG=(-jdk-home "$JAVA_HOME")
fi

echo "== cleaning $OUT_DIR"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/shim" "$OUT_DIR/main" "$OUT_DIR/test"

echo "== compiling verification shim"
"$KOTLINC" "${JDK_HOME_ARG[@]}" -nowarn \
  tools/jvm-verify/shim/src/JunitShim.kt \
  tools/jvm-verify/shim/src/ShimMain.kt \
  -d "$OUT_DIR/shim"

echo "== compiling core (main sources)"
# The core module must stay free of Android framework imports so it can run on a plain JVM.
if ! grep -rIl --include='*.kt' -E '^\s*import\s+android\.|^\s*import\s+androidx\.' core/src/main >/dev/null 2>&1; then
  "$KOTLINC" "${JDK_HOME_ARG[@]}" -nowarn \
    $(find core/src/main -name '*.kt' -print) \
    -d "$OUT_DIR/main"
else
  echo "error: core/src/main must not import android.* or androidx.* (it is a pure-Kotlin module)." >&2
  grep -rIn --include='*.kt' -E '^\s*import\s+(android|androidx)\.' core/src/main >&2
  exit 1
fi

echo "== compiling core tests"
TEST_SOURCES=$(find core/src/test -name '*.kt' -print)
if [[ -z "$TEST_SOURCES" ]]; then
  echo "error: no test sources found under core/src/test" >&2
  exit 1
fi
"$KOTLINC" "${JDK_HOME_ARG[@]}" -nowarn \
  -cp "$OUT_DIR/shim:$OUT_DIR/main" \
  $TEST_SOURCES \
  -d "$OUT_DIR/test"

# Locate the stdlib shipped with the compiler distribution so the runner has kotlin.* at runtime.
KOTLINC_BIN_DIR="$(dirname "$(command -v "$KOTLINC")")"
STDLIB_JAR="$(ls "$KOTLINC_BIN_DIR"/../lib/kotlin-stdlib.jar 2>/dev/null || true)"
if [[ -z "$STDLIB_JAR" ]]; then
  echo "error: kotlin-stdlib.jar not found next to kotlinc ($KOTLINC_BIN_DIR/../lib)." >&2
  exit 1
fi

echo "== running tests"
set +e
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT_DIR/shim:$OUT_DIR/main:$OUT_DIR/test:$STDLIB_JAR" SamavetaVerify \
  com.samaveta.android.core
STATUS=$?
set -e
exit $STATUS
