#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/maven-settings.py
export MAVEN_USER_HOME="${MAVEN_USER_HOME:-$PWD/.local/maven}"
if [[ -d /workspace/tools/jdk-21 ]]; then export JAVA_HOME=/workspace/tools/jdk-21; export PATH="$JAVA_HOME/bin:$PATH"; fi
exec ./mvnw -s .local/maven-settings.xml "$@"
