#!/bin/sh
#
# SAMAVETA — Gradle launcher (canonical Gradle wrapper script).
#
# This is the standard Gradle wrapper shell script, committed verbatim so that `./gradlew` works on
# a clean machine and in CI. It runs `org.gradle.wrapper.GradleWrapperMain` from
# `gradle/wrapper/gradle-wrapper.jar`, which downloads the distribution pinned in
# `gradle/wrapper/gradle-wrapper.properties` (8.9) on first use.
#
# An earlier version of this file was a hand-written downloader that expected a `gradle` binary or a
# working TLS path to services.gradle.org. It did not exist on a clean runner, and CI failed with
# exit code 127 ("command not found") before any Android work began. Do not replace this file by
# hand: regenerate it with `./gradlew wrapper --gradle-version 8.9` when upgrading.

APP_HOME=$(cd -P -- "$(dirname -- "$0")" && pwd -P)
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD=$JAVA_HOME/bin/java
else
    JAVACMD=java
fi

if ! command -v "$JAVACMD" >/dev/null 2>&1; then
    echo "ERROR: JAVA_HOME is not set and no 'java' command could be found." >&2
    echo "Install a JDK (17 or newer) or set JAVA_HOME." >&2
    exit 1
fi

if [ ! -f "$CLASSPATH" ]; then
    echo "ERROR: gradle/wrapper/gradle-wrapper.jar is missing." >&2
    exit 1
fi

# JVM options are passed unquoted so the shell word-splits them; quoting here would hand the JVM a
# literal argument named "-Xmx64m" and it would look for a main class of that name.
DEFAULT_JVM_OPTS="-Xmx64m -Xms64m"

exec "$JAVACMD" \
    $DEFAULT_JVM_OPTS \
    $JAVA_OPTS \
    $GRADLE_OPTS \
    -Dorg.gradle.appname=gradlew \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
