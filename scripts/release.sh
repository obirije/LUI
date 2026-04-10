#!/bin/bash
# Usage:
#   ./scripts/release.sh 0.3.0 --apk   # Build APK + GitHub Release (no PyPI/npm)
#   ./scripts/release.sh 0.3.0 --lib   # GitHub Release → triggers PyPI + npm via CI (no APK)
#   ./scripts/release.sh 0.3.0 --all   # Build APK + GitHub Release + triggers PyPI + npm via CI

set -e

VERSION="$1"
MODE="$2"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [ -z "$VERSION" ] || [ -z "$MODE" ]; then
  echo "Usage: $0 <version> <mode>"
  echo ""
  echo "Modes:"
  echo "  --apk   Build APK, create GitHub Release (no lib publish)"
  echo "  --lib   Create GitHub Release, triggers PyPI + npm publish via CI"
  echo "  --all   Build APK + create GitHub Release + triggers PyPI + npm via CI"
  exit 1
fi

echo "=== Releasing v$VERSION ($MODE) ==="

# Bump versions
echo "→ Bumping versions..."
sed -i "s/^version = \".*\"/version = \"$VERSION\"/" "$ROOT/lui-python/pyproject.toml"
sed -i "s/__version__ = \".*\"/__version__ = \"$VERSION\"/" "$ROOT/lui-python/src/lui/__init__.py"
cd "$ROOT/lui-node" && npm version "$VERSION" --no-git-tag-version && cd "$ROOT"
sed -i "s/versionName \".*\"/versionName \"$VERSION\"/" "$ROOT/app/build.gradle"

# Build APK if needed
APK=""
if [ "$MODE" = "--apk" ] || [ "$MODE" = "--all" ]; then
  echo "→ Building release APK..."
  "$ROOT/gradlew" assembleFullRelease
  APK=$(find "$ROOT/app/build/outputs/apk" -name "*.apk" -path "*/release/*" | head -1)
  if [ -n "$APK" ]; then
    echo "  APK: $(du -h "$APK" | cut -f1)"
  else
    echo "  Warning: APK build failed"
  fi
fi

# Commit, tag, push
echo "→ Committing and pushing..."
git add -A
git commit -m "Release v$VERSION"
git tag "v$VERSION"
git push origin main --tags

# Create GitHub Release
# --lib and --all create a release which triggers publish.yml → PyPI + npm
if [ "$MODE" = "--lib" ] || [ "$MODE" = "--all" ]; then
  echo "→ Creating GitHub Release (triggers PyPI + npm publish)..."
  if [ -n "$APK" ]; then
    gh release create "v$VERSION" "$APK" --title "v$VERSION" --generate-notes
  else
    gh release create "v$VERSION" --title "v$VERSION" --generate-notes
  fi
  echo "  Release: https://github.com/obirije/LUI/releases/tag/v$VERSION"
  echo "  PyPI + npm: publishing via GitHub Actions (check Actions tab)"
elif [ "$MODE" = "--apk" ]; then
  echo "→ Creating GitHub Release (APK only, no lib publish)..."
  if [ -n "$APK" ]; then
    gh release create "v$VERSION" "$APK" --title "v$VERSION" --generate-notes --prerelease
  else
    gh release create "v$VERSION" --title "v$VERSION" --generate-notes --prerelease
  fi
  echo "  Release: https://github.com/obirije/LUI/releases/tag/v$VERSION"
  echo "  (marked as prerelease — publish.yml won't trigger)"
fi

echo ""
echo "=== v$VERSION released ==="
