#!/usr/bin/env bash
set -euo pipefail

# MinePilot's lightweight, repeatable pre-commit gate. It inspects only the
# index, so unrelated work in the working tree is preserved.

failures=0
warnings=0
fail() { printf 'ERROR: %s\n' "$1" >&2; failures=$((failures + 1)); }
warn() { printf 'WARN: %s\n' "$1" >&2; warnings=$((warnings + 1)); }

if git diff --cached --quiet; then
  fail 'the index is empty; stage the intended change before committing'
  printf '\nPreflight failed.\n' >&2
  exit 1
fi

if ! git diff --cached --check; then
  fail 'staged diff contains whitespace errors'
fi

staged_files=$(git diff --cached --name-only --diff-filter=ACMR)
while IFS= read -r path; do
  [ -z "$path" ] && continue
  case "$path" in
    build/*|run/*|logs/*|e2e/results/*|*.sqlite|*.sqlite3|*.db)
      fail "generated/runtime artifact is staged: $path" ;;
  esac
done <<< "$staged_files"

# Scan staged file contents, not the diff itself, and exclude this checker so
# its documented patterns cannot trigger the checker's own gate.
while IFS= read -r path; do
  [ -z "$path" ] && continue
  [ "$path" = 'scripts/preflight-before-commit.sh' ] && continue
  case "$path" in
    *.png|*.jpg|*.jpeg|*.gif|*.jar|*.zip) continue ;;
  esac
  content=$(git show ":$path" 2>/dev/null || true)
  if printf '%s\n' "$content" | grep -En '(MCAI_API_KEY[[:space:]]*[=:][[:space:]]*[A-Za-z0-9_./+-]{20,}|Authorization[[:space:]]*:[[:space:]]*Bearer[[:space:]]+[A-Za-z0-9_./+-]{20,}|\b(sk|tp)-[A-Za-z0-9]{20,})' >/dev/null; then
    fail "possible credential in staged file: $path"
  fi
done <<< "$staged_files"

current_patch_id=$(git diff --cached | git patch-id --stable | awk 'NR == 1 {print $1}')
if [ -n "$current_patch_id" ]; then
  while IFS= read -r commit; do
    [ -z "$commit" ] && continue
    previous_patch_id=$(git show --format= "$commit" | git patch-id --stable | awk 'NR == 1 {print $1}')
    if [ -n "$previous_patch_id" ] && [ "$current_patch_id" = "$previous_patch_id" ]; then
      fail "staged patch duplicates recent commit $commit"
      break
    fi
  done < <(git rev-list --max-count=30 HEAD)
fi

# New production placeholders are almost always unfinished behavior. Existing
# historical text is intentionally ignored by inspecting only added lines.
added_production=$(git diff --cached --unified=0 -- src/main 2>/dev/null || true)
if printf '%s\n' "$added_production" | grep -E '^\+[^+].*(TODO|FIXME|UnsupportedOperationException)' >/dev/null; then
  fail 'new production placeholder/TODO detected; implement it or document a real blocker'
fi

if git diff --cached --name-only | grep -E '^src/main/' >/dev/null && \
   ! git diff --cached --name-only | grep -E '(^src/test/|GameTests?\.java$|docs/|README\.md$|CONTRIBUTING\.md$)' >/dev/null; then
  warn 'production code changed without a staged test or documentation update'
fi

printf 'Preflight: %s staged file(s), %s warning(s).\n' "$(printf '%s\n' "$staged_files" | sed '/^$/d' | wc -l | tr -d ' ')" "$warnings"
if [ "$failures" -ne 0 ]; then
  printf 'Preflight failed with %s error(s).\n' "$failures" >&2
  exit 1
fi
printf 'Preflight passed. Review the staged diff before committing.\n'
