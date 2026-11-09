#!/usr/bin/env bash
# Writes the API a caller or subclass sees, one sorted file per module (see ApiList.java).
# Usage: scripts/api-diff.sh <out-dir>   (run after ./gradlew classes)
set -euo pipefail
out="${1:?out dir}"
mkdir -p "$out"
deps=$(./gradlew -q :testing:apiClasspath :eloquent:apiClasspath :pgsql:apiClasspath :sqlite:apiClasspath | tr '\n' ':')
cp="core/build/classes/java/main:sqlite/build/classes/java/main:pgsql/build/classes/java/main:eloquent/build/classes/java/main:testing/build/classes/java/main:$deps"
for module in core sqlite pgsql eloquent testing; do
    java -cp "$cp" scripts/ApiList.java "$module/build/classes/java/main" > "$out/$module.txt"
done
