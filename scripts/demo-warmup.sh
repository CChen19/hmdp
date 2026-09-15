#!/usr/bin/env bash
# Opt-in demo / warmup / data-generation tests (MySQL + Redis required).
# Not run by default `mvn test` or CI.
set -euo pipefail
cd "$(dirname "$0")/.."
exec mvn -Dgroups=demo -Dsurefire.excludedGroups= test "$@"
