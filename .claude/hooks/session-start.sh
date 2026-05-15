#!/bin/bash
set -euo pipefail

# Only run in remote Claude Code environments
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

cd "$CLAUDE_PROJECT_DIR"

# Ensure gradlew is executable
chmod +x gradlew

# Download and cache the Gradle distribution
echo "Downloading Gradle distribution..."
./gradlew --version --no-daemon --quiet 2>&1 | grep -E "^Gradle" || true

echo "Session start setup complete."
