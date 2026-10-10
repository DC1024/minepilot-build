#!/bin/sh
set -eu

script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repository_root=$(CDPATH= cd -- "$script_directory/.." && pwd)
source_directory="$repository_root/.agents/skills/minecraft-companion"
codex_root="${CODEX_HOME:-$HOME/.codex}"
destination_parent="$codex_root/skills"
destination="$destination_parent/minecraft-companion"

if [ ! -f "$source_directory/SKILL.md" ]; then
  echo "Repository skill source is missing: $source_directory" >&2
  exit 1
fi
if [ -e "$destination" ]; then
  echo "Refusing to overwrite existing skill: $destination" >&2
  exit 1
fi

mkdir -p "$destination_parent"
cp -R "$source_directory" "$destination"
echo "Installed minecraft-companion skill at $destination"
