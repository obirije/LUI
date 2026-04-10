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

# Check if this version already has a release (e.g. --apk already ran)
EXISTING_RELEASE=$(gh release view "v$VERSION" --json tagName -q .tagName 2>/dev/null || true)

if [ -z "$EXISTING_RELEASE" ]; then
  # Fresh release — bump, commit, tag, push
  echo "→ Bumping versions..."
  sed -i "s/^version = \".*\"/version = \"$VERSION\"/" "$ROOT/lui-python/pyproject.toml"
  sed -i "s/__version__ = \".*\"/__version__ = \"$VERSION\"/" "$ROOT/lui-python/src/lui/__init__.py"
  cd "$ROOT/lui-node" && npm version "$VERSION" --no-git-tag-version 2>/dev/null || true && cd "$ROOT"
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
  git diff --cached --quiet && echo "  No changes to commit" || git commit -m "Release v$VERSION"
  git tag "v$VERSION" 2>/dev/null || true
  git push origin main --tags

  # Create GitHub Release
  if [ "$MODE" = "--apk" ]; then
    echo "→ Creating GitHub Release (APK only)..."
    if [ -n "$APK" ]; then
      gh release create "v$VERSION" "$APK" --title "v$VERSION" --generate-notes --prerelease
    else
      gh release create "v$VERSION" --title "v$VERSION" --generate-notes --prerelease
    fi
    echo "  (prerelease — run with --lib to publish to PyPI + npm)"
  else
    echo "→ Creating GitHub Release (triggers PyPI + npm publish)..."
    if [ -n "$APK" ]; then
      gh release create "v$VERSION" "$APK" --title "v$VERSION" --generate-notes
    else
      gh release create "v$VERSION" --title "v$VERSION" --generate-notes
    fi
    echo "  PyPI + npm: publishing via GitHub Actions"
  fi

else
  # Release already exists — promote or add assets
  if [ "$MODE" = "--lib" ]; then
    echo "→ Promoting v$VERSION to full release (triggers PyPI + npm)..."
    gh release edit "v$VERSION" --prerelease=false
    echo "  PyPI + npm: publishing via GitHub Actions"
  elif [ "$MODE" = "--apk" ]; then
    echo "→ Building APK and uploading to existing release..."
    "$ROOT/gradlew" assembleFullRelease
    APK=$(find "$ROOT/app/build/outputs/apk" -name "*.apk" -path "*/release/*" | head -1)
    if [ -n "$APK" ]; then
      gh release upload "v$VERSION" "$APK" --clobber
      echo "  APK uploaded: $(du -h "$APK" | cut -f1)"
    else
      echo "  Warning: APK build failed"
    fi
  elif [ "$MODE" = "--all" ]; then
    echo "→ Building APK, uploading, and promoting release..."
    "$ROOT/gradlew" assembleFullRelease
    APK=$(find "$ROOT/app/build/outputs/apk" -name "*.apk" -path "*/release/*" | head -1)
    if [ -n "$APK" ]; then
      gh release upload "v$VERSION" "$APK" --clobber
      echo "  APK uploaded: $(du -h "$APK" | cut -f1)"
    fi
    gh release edit "v$VERSION" --prerelease=false
    echo "  PyPI + npm: publishing via GitHub Actions"
  fi
fi

echo ""
echo "=== v$VERSION done ==="
echo "  https://github.com/obirije/LUI/releases/tag/v$VERSION"
