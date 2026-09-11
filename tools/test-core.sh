#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p core/build/offline-test
find core/src/main/java core/src/test/java -name '*.java' -print > core/build/offline-test/sources.txt
javac --release 17 -encoding UTF-8 -d core/build/offline-test @core/build/offline-test/sources.txt
java -cp core/build/offline-test dev.flowstream.core.CoreTests
