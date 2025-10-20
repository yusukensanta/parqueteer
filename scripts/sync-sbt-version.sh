#!/usr/bin/env bash
# Sync sbt version from .tool-versions to project/build.properties

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
TOOL_VERSIONS="$PROJECT_ROOT/.tool-versions"
BUILD_PROPERTIES="$PROJECT_ROOT/project/build.properties"

# Check if .tool-versions exists
if [[ ! -f "$TOOL_VERSIONS" ]]; then
    echo "Error: .tool-versions not found at $TOOL_VERSIONS"
    exit 1
fi

# Extract sbt version from .tool-versions
SBT_VERSION=$(grep -E '^sbt\s+' "$TOOL_VERSIONS" | awk '{print $2}')

if [[ -z "$SBT_VERSION" ]]; then
    echo "Error: No sbt version found in .tool-versions"
    exit 1
fi

# Update or create build.properties
mkdir -p "$(dirname "$BUILD_PROPERTIES")"
echo "sbt.version=$SBT_VERSION" > "$BUILD_PROPERTIES"

echo "✓ Updated $BUILD_PROPERTIES to sbt version $SBT_VERSION"
