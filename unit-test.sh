#!/usr/bin/env bash
# Run the JVM unit tests locally (candidate logic + SQL against the bundled .sqlite3 assets).
set -euo pipefail
cd "$(dirname "$0")"

# Optionally pass Gradle test filters, e.g.: ./unit-test.sh --tests "*DictionarySqlTest*"
./gradlew testDebugUnitTest "$@"

echo
echo "Test report: $(pwd)/app/build/reports/tests/testDebugUnitTest/index.html"
