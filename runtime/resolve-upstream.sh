#!/usr/bin/env bash
# Resolves which OpenCode release to bundle into an app build.
#
# Usage: runtime/resolve-upstream.sh [tag]     (no tag: the latest published, non-draft, non-pre-release upstream release)
# Outputs tag, version and commit to $GITHUB_OUTPUT (or stdout).
# Environment: UPSTREAM_REPO, GH_TOKEN (for gh), UPSTREAM_GIT_URL (default https://github.com/$UPSTREAM_REPO.git).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=versions.env
. "$here/versions.env"

up="$UPSTREAM_REPO"
git_url="${UPSTREAM_GIT_URL:-https://github.com/$up.git}"
out="${GITHUB_OUTPUT:-/dev/stdout}"
tag="${1:-}"

die() { echo "resolve-upstream: $*" >&2; exit 1; }

if [ -z "$tag" ]; then
  latest="$(gh api "repos/$up/releases/latest")" || die "could not read the latest release of $up"
  [ "$(jq -r '.draft // false' <<<"$latest")" = false ] || die "the latest release is a draft"
  [ "$(jq -r '.prerelease // false' <<<"$latest")" = false ] || die "the latest release is a pre-release"
  tag="$(jq -r '.tag_name // empty' <<<"$latest")"
fi

[[ "$tag" =~ ^v?[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.]+)?$ ]] || die "'$tag' is not a version tag"

refs="$(git ls-remote "$git_url" "refs/tags/$tag" "refs/tags/$tag^{}")"
peeled="$(awk -v t="refs/tags/$tag^{}" '$2 == t {print $1}' <<<"$refs")"
direct="$(awk -v t="refs/tags/$tag" '$2 == t {print $1}' <<<"$refs")"
commit="${peeled:-$direct}"

[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || die "could not resolve $tag to a commit"

printf 'tag=%s\nversion=%s\ncommit=%s\n' "$tag" "${tag#v}" "$commit" >>"$out"
echo "$up $tag -> $commit" >&2
