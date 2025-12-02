#!/usr/bin/env bash
# bolt/tests/run_tests.sh
# Non-destructive tests for onboarding scripts (macOS/Linux oriented)
set -euo pipefail

echo "Running onboarding scripts basic tests"

# Determine repo root relative to this test script
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Ensure scripts exist
for f in "$REPO_ROOT/bolt/tasks"/*.sh; do
  echo "Checking $f"
  if [[ ! -f "$f" ]]; then
    echo "Missing $f"; exit 1
  fi
done

# Ensure scripts are executable
for f in "$REPO_ROOT/bolt/tasks"/*.sh; do
  if [[ ! -x "$f" ]]; then
    echo "Making $f executable"
    chmod +x "$f"
  fi
done

# Test create_local_properties in a temp dir to avoid writing repo local.properties
TMPDIR=$(mktemp -d)
pushd "$TMPDIR" >/dev/null

# Run the create_local_properties script in DRY mode by providing SDK_PATH to a temp location
mkdir -p sdk_fake
export SDK_PATH="$TMPDIR/sdk_fake"
export YES=1
bash "$REPO_ROOT/bolt/tasks/create_local_properties.sh" || { echo "create_local_properties failed"; popd >/dev/null; rm -rf "$TMPDIR"; exit 1; }

echo "create_local_properties wrote local.properties:"
cat local.properties

popd >/dev/null
rm -rf "$TMPDIR"

# Test init_submodules DRY_RUN: ensure it exits successfully when run inside a git repo without .gitmodules
TMPINIT=$(mktemp -d)
pushd "$TMPINIT" >/dev/null
# initialize an empty git repo so the script's git repo checks pass
git init -q || true
export DRY_RUN=1
bash "$REPO_ROOT/bolt/tasks/init_submodules.sh" || { echo "init_submodules (dry) failed"; popd >/dev/null; rm -rf "$TMPINIT"; exit 1; }

popd >/dev/null
rm -rf "$TMPINIT"

echo "All tests passed (non-destructive)"
exit 0
