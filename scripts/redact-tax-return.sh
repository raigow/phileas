#!/usr/bin/env bash
# Removes personal identifying information from a PDF tax return.
#
#   scripts/redact-tax-return.sh my-return.pdf my-return-redacted.pdf --name "First Last" --name "Spouse Name"
#
# Builds phileas the first time it runs (needs Java 21). Everything runs locally; the PDF never leaves this machine.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLASSPATH_FILE="$ROOT/target/tax-redactor.classpath"

if [[ ! -f "$CLASSPATH_FILE" || ! -d "$ROOT/target/classes" || "$ROOT/pom.xml" -nt "$CLASSPATH_FILE" ]]; then
  echo "Building phileas (first run only)..." >&2
  (cd "$ROOT" && ./mvnw -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile="$CLASSPATH_FILE" >&2)
fi

exec java -Dlog4j2.configurationFile="$ROOT/scripts/log4j2-quiet.xml" -cp "$ROOT/target/classes:$(cat "$CLASSPATH_FILE")" \
  ai.philterd.phileas.tools.TaxReturnRedactor "$@"
