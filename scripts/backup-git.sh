#!/usr/bin/env bash
# Back up committed Git history. Signing keys remain in their separate private archive.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
backup_dir="${1:-$project_dir/../EasyBase64-backups}"
mkdir -p -- "$backup_dir"
backup_dir="$(cd -- "$backup_dir" && pwd)"
backup_file="$backup_dir/EasyBase64-$(date -u +%Y%m%dT%H%M%SZ).bundle"
git -C "$project_dir" bundle create "$backup_file" --all
git -C "$project_dir" bundle verify "$backup_file"
sha256sum "$backup_file" > "$backup_file.sha256"
printf 'Created committed-history backup: %s\n' "$backup_file"
if [[ -n "$(git -C "$project_dir" status --porcelain)" ]]; then
  printf '%s\n' 'Working-tree changes are not included. Commit the changes you want to preserve, then run this script again.'
fi
