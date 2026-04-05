#!/bin/bash
# Usage: ./scripts/bump-version.sh 0.3.0
#
# Updates version in all package files, commits, tags, and pushes.
# The tag push triggers the GitHub Actions publish workflow.

set -e

VERSION="$1"

if [ -z "$VERSION" ]; then
  echo "Usage: $0 <version>"
  echo "Example: $0 0.3.0"
  exit 1
fi

echo "Bumping to v$VERSION..."

# Python
sed -i "s/^version = \".*\"/version = \"$VERSION\"/" lui-python/pyproject.toml
sed -i "s/__version__ = \".*\"/__version__ = \"$VERSION\"/" lui-python/src/lui/__init__.py

# Node
cd lui-node
npm version "$VERSION" --no-git-tag-version
cd ..

echo "Updated:"
grep "^version" lui-python/pyproject.toml
grep "__version__" lui-python/src/lui/__init__.py
grep '"version"' lui-node/package.json

echo ""
echo "Next steps:"
echo "  git add -A && git commit -m \"Bump version to v$VERSION\""
echo "  git tag v$VERSION"
echo "  git push && git push --tags"
echo ""
echo "The tag push will trigger GitHub Actions to publish to PyPI and npm."
