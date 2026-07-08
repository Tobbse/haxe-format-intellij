#!/usr/bin/env bash
# Prepares the integration-test fixture. Requires: macOS, node >= 18, npm.
set -euo pipefail
cd "$(dirname "$0")/project"
npm install --no-save lix
npx lix scope create || true          # writes .haxerc (gitignored)
npx lix install haxe 4.3.6            # or the team's pinned Haxe version
npx lix install haxelib:formatter#1.18.0
echo "Fixture ready. Probe:"
echo 'class X {}' | ./node_modules/.bin/haxelib run formatter --stdin -s "$(pwd)/src/Unformatted.hx" || true
