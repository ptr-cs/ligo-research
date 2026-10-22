#!/usr/bin/env bash
# One command to reproduce the Gravity Spy triage analysis and serve the dashboard.
#
#   ./run.sh            build everything, run the agent team, serve on http://localhost:8787
#   ./run.sh analyze    build and run the agent team once (exit 1 if QA or tests fail)
#   ./run.sh test       run the JUnit suite
#   ./run.sh serve      serve existing results without starting a new run
#   ./run.sh campaign [HOURS]   all 8 Gravity Spy datasets + comparison + sensitivity sweep (default 4 h)
#
# Set ANTHROPIC_API_KEY to have Claude agents staff each role; without it the
# team runs in "scripted" mode (same tools, deterministic policy).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
MVN_VERSION=3.9.16
TOOLS="$ROOT/.tools"
# The largest dataset (L1_O3b, ~216 MB) is held in memory by several roles at once.
JAVA_OPTS="${JAVA_OPTS:--Xmx12g}"

ensure_maven() {
  if command -v mvn >/dev/null 2>&1; then MVN=mvn; return; fi
  MVN="$TOOLS/apache-maven-$MVN_VERSION/bin/mvn"
  if [[ ! -x "$MVN" ]]; then
    echo "==> Bootstrapping Maven $MVN_VERSION into .tools/"
    mkdir -p "$TOOLS"
    curl -fsSL "https://dlcdn.apache.org/maven/maven-3/$MVN_VERSION/binaries/apache-maven-$MVN_VERSION-bin.tar.gz" \
      | tar -xz -C "$TOOLS"
  fi
}

build_web() {
  if ! command -v npm >/dev/null 2>&1; then
    echo "==> npm not found; skipping web build (API still available on :8787)"; return
  fi
  echo "==> Building TypeScript dashboard"
  (cd "$ROOT/web" && { [[ -d node_modules ]] || npm install --no-audit --no-fund; } && npm run build --silent)
}

build_backend() {
  echo "==> Building Java fog server"
  "$MVN" -q -f "$ROOT/backend/pom.xml" -DskipTests package
}

ensure_maven
cmd="${1:-all}"
case "$cmd" in
  test)
    "$MVN" -q -f "$ROOT/backend/pom.xml" test ;;
  analyze)
    build_backend
    java $JAVA_OPTS -jar "$ROOT/backend/target/gravity-spy-triage.jar" analyze --root "$ROOT" ;;
  serve)
    build_backend
    java $JAVA_OPTS -jar "$ROOT/backend/target/gravity-spy-triage.jar" serve --root "$ROOT" ;;
  campaign)
    build_web
    build_backend
    java $JAVA_OPTS -jar "$ROOT/backend/target/gravity-spy-triage.jar" campaign --hours "${2:-4}" --root "$ROOT" ;;
  all)
    build_web
    build_backend
    java $JAVA_OPTS -jar "$ROOT/backend/target/gravity-spy-triage.jar" serve --run --root "$ROOT" ;;
  *)
    echo "usage: ./run.sh [all|analyze|test|serve|campaign [hours]]"; exit 2 ;;
esac
