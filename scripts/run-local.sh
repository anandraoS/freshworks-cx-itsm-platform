#!/usr/bin/env bash
# Local runtime: core-service :8081 (with EMBEDDED notifications) + gateway :8080 + frontend dev server :5173.
# H2 in-memory + in-process event relay (profile "local"); no Docker needed.
#
#   scripts/run-local.sh build      # build the jars only
#   scripts/run-local.sh up         # build (if needed) and start everything; Ctrl+C stops all
#   scripts/run-local.sh            # same as "up"
#
# Environment overrides: JAVA_HOME (default: graalvm-jdk-21.0.7 path used in this project; set inline, never exported globally),
#                        MVN (path to mvn), CX_JWT_SECRET (dummy default below; must be identical for core and gateway).
# NOTE: this script was written but NOT executed end-to-end by its author (core-service was being built in parallel).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-C:/Users/sahukari.rao/.jdks/graalvm-jdk-21.0.7}"
MVN="${MVN:-C:/Users/sahukari.rao/.m2/wrapper/dists/apache-maven-3.9.12-bin/5nmfsn99br87k5d4ajlekdq10k/apache-maven-3.9.12/bin/mvn.cmd}"
JAVA="$JAVA_HOME/bin/java"
LOG_DIR="$ROOT/.run"
# Dummy local-only secret (>= 32 chars). Replace for anything beyond a local demo.
export CX_JWT_SECRET="${CX_JWT_SECRET:-dev-only-NOT-A-REAL-SECRET-change-me-0123456789abcdef}"

build() {
  echo "==> building jars (tests skipped; run 'mvn test' separately)"
  (cd "$ROOT" && JAVA_HOME="$JAVA_HOME" "$MVN" -B -q -DskipTests -pl core-service,gateway,notification-service -am package)
}

PIDS=()
cleanup() {
  echo; echo "==> stopping"
  for pid in "${PIDS[@]:-}"; do [ -n "$pid" ] && kill "$pid" 2>/dev/null || true; done
}

start() {
  local name="$1"; shift
  mkdir -p "$LOG_DIR"
  echo "==> starting $name (log: .run/$name.log)"
  "$@" >"$LOG_DIR/$name.log" 2>&1 &
  PIDS+=($!)
}

up() {
  [ -f "$ROOT/core-service/target/core-service-1.0.0.jar" ] && [ -f "$ROOT/gateway/target/gateway-1.0.0.jar" ] || build
  trap cleanup EXIT INT TERM

  # Core with embedded notifications (cx.notifications.embedded=true): no separate notification-service process locally,
  # so the gateway's notifications route defaults to the core URI.
  start core "$JAVA" -Dcx.notifications.embedded=true -Dserver.port=8081 -jar "$ROOT/core-service/target/core-service-1.0.0.jar"
  start gateway "$JAVA" -Dserver.port=8080 -Droutes.core.uri=http://localhost:8081 -jar "$ROOT/gateway/target/gateway-1.0.0.jar"

  if [ -d "$ROOT/frontend/node_modules" ]; then
    start frontend bash -c "cd '$ROOT/frontend' && VITE_API_BASE_URL=http://localhost:8080 npm run dev -- --port 5173"
  else
    echo "!! frontend/node_modules missing: run 'npm ci' in frontend/ first; skipping the dev server"
  fi

  echo "==> core http://localhost:8081  gateway http://localhost:8080  frontend http://localhost:5173"
  echo "==> logs in $LOG_DIR ; seed logins are listed in docs/API-CONTRACT.md ; Ctrl+C to stop"
  wait
}

case "${1:-up}" in
  build) build ;;
  up) up ;;
  *) echo "usage: $0 [build|up]"; exit 2 ;;
esac
