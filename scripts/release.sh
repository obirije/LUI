#!/bin/bash
# Usage: ./scripts/release.sh 0.3.0
#
# Bumps version, commits, tags, builds APK, creates GitHub Release.
# PyPI + npm publish happens automatically via GitHub Actions on tag push.

set -e

VERSION="$1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [ -z "$VERSION" ]; then
  echo "Usage: $0 <version>"
  echo "Example: $0 0.3.0"
  exit 1
fi

echo "=== Releasing v$VERSION ==="

# Bump versions
echo "→ Bumping versions..."
sed -i "s/^version = \".*\"/version = \"$VERSION\"/" "$ROOT/lui-python/pyproject.toml"
sed -i "s/__version__ = \".*\"/__version__ = \"$VERSION\"/" "$ROOT/lui-python/src/lui/__init__.py"
cd "$ROOT/lui-node" && npm version "$VERSION" --no-git-tag-version && cd "$ROOT"
sed -i "s/versionName \".*\"/versionName \"$VERSION\"/" "$ROOT/app/build.gradle"

# Build APK
echo "→ Building release APK..."
"$ROOT/gradlew" assembleFullRelease
APK=$(find "$ROOT/app/build/outputs/apk" -name "*.apk" -path "*/release/*" | head -1)

# Commit, tag, push
echo "→ Committing and pushing..."
git add -A
git commit -m "Release v$VERSION"
git tag "v$VERSION"
git push origin main --tags

# Create GitHub Release with APK
echo "→ Creating GitHub Release..."
if [ -n "$APK" ]; then
  gh release create "v$VERSION" "$APK" --title "v$VERSION" --generate-notes
else
  gh release create "v$VERSION" --title "v$VERSION" --generate-notes
fi

echo ""
echo "=== Done ==="
echo "APK + GitHub Release: https://github.com/obirije/LUI/releases/tag/v$VERSION"
echo "PyPI + npm: publishing via GitHub Actions (check Actions tab)"
