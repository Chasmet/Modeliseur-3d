#!/usr/bin/env bash
# Publish the tested application source with the default branch's unchanged workflows.
# GitHub's contents-only Actions token may publish this tree without Workflows write.
set -euo pipefail
VERSION="$1"
TARGET_FILE="$2"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
# The existing release workflow calls this before publication. Keep its permissions
# unchanged while publishing notes for the actual version, rather than old CI text.
if [ -f "release-notes/$VERSION.md" ]; then
  cp "release-notes/$VERSION.md" /tmp/modeliseur-release.md
fi
DEFAULT_BRANCH=$(gh api "repos/$GITHUB_REPOSITORY" --jq '.default_branch')
git fetch --no-tags --depth=1 origin "$DEFAULT_BRANCH"
MAIN_SHA=$(git rev-parse FETCH_HEAD)
MAIN_WORKFLOWS=$(git rev-parse "$MAIN_SHA:.github/workflows")
export GIT_INDEX_FILE="$RUNNER_TEMP/modeliseur-release-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}.index"
rm -f "$GIT_INDEX_FILE"
trap 'rm -f "$GIT_INDEX_FILE"' EXIT
git read-tree "$GITHUB_SHA"
git ls-files -z .github/workflows | git update-index --force-remove -z --stdin
git read-tree --prefix=.github/workflows/ "$MAIN_SHA:.github/workflows"
SNAPSHOT_TREE=$(git write-tree)
test "$(git rev-parse "$SNAPSHOT_TREE:.github/workflows")" = "$MAIN_WORKFLOWS"
# Everything outside CI workflows must be exactly the source which produced the tested APK.
git diff --exit-code "$GITHUB_SHA" "$SNAPSHOT_TREE" -- . ':(exclude).github/workflows/**'
SOURCE_REF="refs/heads/apk-source/$VERSION"
if git ls-remote --exit-code --heads origin "$SOURCE_REF" >/dev/null 2>&1; then
  git fetch --no-tags --depth=1 origin "$SOURCE_REF"
  SOURCE_COMMIT=$(git rev-parse FETCH_HEAD)
  test "$(git rev-parse "$SOURCE_COMMIT^{tree}")" = "$SNAPSHOT_TREE"
else
  SOURCE_COMMIT=$(printf 'APK %s application source; compiled and verified from %s\n' "$VERSION" "$GITHUB_SHA" | git -c user.name='github-actions[bot]' -c user.email='41898282+github-actions[bot]@users.noreply.github.com' commit-tree "$SNAPSHOT_TREE" -p "$MAIN_SHA")
  git push origin "$SOURCE_COMMIT:$SOURCE_REF"
fi
printf '%s\n' "$SOURCE_COMMIT" > "$TARGET_FILE"
echo "Source APK vérifiée : $SOURCE_COMMIT ; application identique à $GITHUB_SHA ; main inchangé."
