#!/usr/bin/env bash
set -euo pipefail
cd /workspace/ChargeHub
python3 scripts/setup-jdk.py
python3 scripts/dev-env.py
./scripts/mvn.sh -B -ntp -DskipTests package
