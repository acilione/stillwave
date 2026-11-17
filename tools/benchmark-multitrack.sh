#!/usr/bin/env bash
# Uses the project's already downloaded Kotlin compiler; no Android device or Gradle daemon needed.
set -euo pipefail
cd "$(dirname "$0")/.."
cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
compiler=$(find "$cache/org.jetbrains.kotlin/kotlin-compiler-embeddable" -name '*.jar' | sort -V | tail -1)
stdlib=$(find "$cache/org.jetbrains.kotlin/kotlin-stdlib" -name '*.jar' | sort -V | tail -1)
annotations=$(find "$cache/org.jetbrains/annotations" -name '*.jar' | sort -V | tail -1)
coroutines=$(find "$cache/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" -name '*.jar' | sort -V | tail -1)
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
java -cp "$compiler:$stdlib:$annotations:$coroutines" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$stdlib:$annotations" -jvm-target 17 -d "$work/classes" \
  app/src/main/java/app/stillwave/audio/{MultitrackRenderer,PcmFormat,WavFile}.kt \
  app/src/main/java/app/stillwave/data/MultitrackProject.kt tools/MultitrackRenderBenchmark.kt
java -Xmx512m -cp "$work/classes:$stdlib" MultitrackRenderBenchmarkKt "$@"
