#!/usr/bin/env sh
set -eu
project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
jar_path="$project_root/lib/flowtrail-server.jar"
if [ ! -f "$jar_path" ]; then jar_path="$project_root/target/flowtrail-server.jar"; fi
if [ ! -f "$jar_path" ]; then echo 'Build first: mvn clean verify' >&2; exit 1; fi
java_command=java
if [ -n "${JAVA_HOME:-}" ]; then java_command="$JAVA_HOME/bin/java"; fi
cd "$project_root"
exec "$java_command" -Dfile.encoding=UTF-8 -jar "$jar_path" "$@"

