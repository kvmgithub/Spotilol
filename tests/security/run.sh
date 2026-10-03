#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
build_dir=$(mktemp -d)
trap 'rm -rf "$build_dir"' EXIT
javac -d "$build_dir" app/src/main/java/com/project/lol/security/*.java tests/security/SecurityPolicyTest.java
java -cp "$build_dir" SecurityPolicyTest
if [[ -f tests/security/bridge.test.cjs ]]; then node --test tests/security/*.test.cjs; fi
