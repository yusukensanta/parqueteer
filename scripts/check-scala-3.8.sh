#!/bin/bash
# Check if Scala 3.8 has been released
# Run this script periodically or add to crontab

set -e

echo "🔍 Checking for Scala 3.8 release..."
echo ""

# Get latest release info
LATEST_RELEASE=$(curl -s https://api.github.com/repos/scala/scala3/releases/latest)
LATEST_VERSION=$(echo "$LATEST_RELEASE" | grep '"tag_name"' | sed -E 's/.*"([^"]+)".*/\1/')
RELEASE_DATE=$(echo "$LATEST_RELEASE" | grep '"published_at"' | sed -E 's/.*"([^"]+)".*/\1/' | cut -d'T' -f1)

echo "📦 Latest Scala 3 release: $LATEST_VERSION"
echo "📅 Published: $RELEASE_DATE"
echo ""

# Check if it's 3.8 or higher
if [[ "$LATEST_VERSION" == *"3.8"* ]] || [[ "$LATEST_VERSION" == *"3.9"* ]]; then
  echo "🎉 SUCCESS! Scala 3.8+ is available!"
  echo ""
  echo "⚡ Action Required:"
  echo "1. Review SCALA_UPGRADE.md"
  echo "2. Update build.sbt:"
  echo "   ThisBuild / scalaVersion := \"$LATEST_VERSION\""
  echo "3. Run: sbt clean compile test"
  echo "4. Verify: java -jar target/scala-3.8.x/parqueteer.jar --version"
  echo "5. Check for warnings (should be none!)"
  echo ""
  exit 0
elif [[ "$LATEST_VERSION" == *"3.7"* ]]; then
  echo "⏳ Still on Scala 3.7.x"
  echo "   Scala 3.8 expected Q4 2025 (Oct-Dec 2025)"
  echo ""
  echo "💡 What to do:"
  echo "- Continue monitoring (run this script weekly)"
  echo "- Watch https://github.com/scala/scala3/releases"
  echo "- Check https://www.scala-lang.org/blog/"
  echo ""
  exit 1
else
  echo "❓ Unknown version detected"
  echo "   Please check manually: https://github.com/scala/scala3/releases"
  echo ""
  exit 2
fi
