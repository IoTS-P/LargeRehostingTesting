#!/usr/bin/env bash
# Apply the captured tool overlays (patches/) to the tool submodules.
#
#   scripts/apply_patches.sh            # apply everything that is not applied yet
#   scripts/apply_patches.sh --check    # only report the state
#   scripts/apply_patches.sh --revert   # undo the patches
#
# Idempotent: a patch that is already applied is skipped, a patch that applies
# cleanly is applied, anything else is reported as a failure (no partial state).
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

MODE=apply
case "${1:-}" in
  --check)  MODE=check ;;
  --revert) MODE=revert ;;
  "")       ;;
  *) die "unknown option $1" ;;
esac

# "<patch file>:<submodule path relative to repo root>"
PATCHES=(
  "evaluated_tools_and_configurations/patches/firmxray.patch:evaluated_tools_and_configurations/tools/RealworldFirmware"
  "evaluated_tools_and_configurations/patches/firmline.patch:evaluated_tools_and_configurations/tools/firmline"
  "evaluated_tools_and_configurations/patches/fuzzware.patch:evaluated_tools_and_configurations/tools/fuzzware"
  "evaluated_tools_and_configurations/patches/gdma.patch:evaluated_tools_and_configurations/tools/gdma"
  "evaluated_tools_and_configurations/patches/hoedur.patch:evaluated_tools_and_configurations/tools/hoedur"
  "evaluated_tools_and_configurations/patches/multifuzz.patch:evaluated_tools_and_configurations/tools/MultiFuzz"
  "evaluated_tools_and_configurations/patches/firmrca.patch:evaluated_tools_and_configurations/tools/FirmRCA"
)

# patches that live inside a nested submodule of a tool.  The vanilla fuzzware snapshot
# changes its emulator and pipeline submodules, so those two patches are applied inside
# them (their paths carry no submodule prefix).  The DMA (gdma) snapshot has no nested
# changes — its emulator/pipeline commits are pinned by setup.sh instead.
NESTED_PATCHES=(
  "evaluated_tools_and_configurations/patches/fuzzware.emulator.patch:evaluated_tools_and_configurations/tools/fuzzware/emulator"
  "evaluated_tools_and_configurations/patches/fuzzware.pipeline.patch:evaluated_tools_and_configurations/tools/fuzzware/pipeline"
  "evaluated_tools_and_configurations/patches/gdma.pipeline.patch:evaluated_tools_and_configurations/tools/gdma/pipeline"
)

applied()  { git -C "$1" apply --reverse --check "$2" >/dev/null 2>&1; }
appliable() { git -C "$1" apply --check "$2" >/dev/null 2>&1; }

# `git diff` in the reference working tree records the file *mode* in the index line
# but no mode-change hunk, so `git apply` only warns ("has type 100644, expected
# 100755") and the file keeps the non-executable mode it has upstream.  Some of
# those files are executed by the modules (fuzzware's install_local.sh/setup.sh,
# hoedur's scripts, FirmRCA's autogen.sh), so restore the mode the patch expects.
fix_modes() { # <submodule dir> <patch file>
  local dir="$1" patch="$2" f
  while read -r f; do
    [ -n "$f" ] || continue
    [ -f "$REPO_ROOT/$dir/$f" ] || continue
    [ -x "$REPO_ROOT/$dir/$f" ] || chmod +x "$REPO_ROOT/$dir/$f"
  done < <(awk '/^diff --git /{file=$3; sub(/^a\//,"",file)}
                /^index .* 100755/{if (file != "") print file}' "$patch" | sort -u)
}

rc=0
for entry in "${PATCHES[@]}"; do
  patch="${entry%%:*}"; dir="${entry##*:}"
  [ -f "$REPO_ROOT/$patch" ] || { c_red "missing $patch"; rc=1; continue; }
  [ -d "$REPO_ROOT/$dir" ] || { c_red "missing submodule $dir"; rc=1; continue; }
  # skip if submodule not initialised (no .git metadata)
  git -C "$REPO_ROOT/$dir" rev-parse --git-dir >/dev/null 2>&1 || {
    c_blue "not initialised: $dir — run 'git submodule update --init' first"
    continue
  }
  if applied "$REPO_ROOT/$dir" "$REPO_ROOT/$patch"; then
    c_green "already applied : $patch"
    [ "$MODE" = apply ] && fix_modes "$dir" "$REPO_ROOT/$patch"
    continue
  fi
  case "$MODE" in
    check) if appliable "$REPO_ROOT/$dir" "$REPO_ROOT/$patch"; then
             c_blue "not applied     : $patch"
           else
             c_red "does NOT apply  : $patch (submodule dirty or wrong commit?)"; rc=1
           fi ;;
    apply) if appliable "$REPO_ROOT/$dir" "$REPO_ROOT/$patch"; then
             git -C "$REPO_ROOT/$dir" apply "$REPO_ROOT/$patch" \
               && { c_green "applied         : $patch"; fix_modes "$dir" "$REPO_ROOT/$patch"; } \
               || { c_red "apply failed    : $patch"; rc=1; }
           else
             c_red "does NOT apply  : $patch"; rc=1
           fi ;;
    revert) if applied "$REPO_ROOT/$dir" "$REPO_ROOT/$patch"; then
              git -C "$REPO_ROOT/$dir" apply --reverse "$REPO_ROOT/$patch" \
                && c_green "reverted        : $patch" \
                || { c_red "revert failed   : $patch"; rc=1; }
            else
              c_blue "not applied     : $patch (nothing to revert)"
            fi ;;
  esac
done

# nested submodules (need `git submodule update --init` inside the tool first)
for entry in "${NESTED_PATCHES[@]}"; do
  patch="${entry%%:*}"; dir="${entry##*:}"
  if [ ! -e "$REPO_ROOT/$dir/.git" ]; then
    c_blue "skipped (submodule not initialised): $patch — run scripts/setup.sh --with-nested"
    continue
  fi
  if applied "$REPO_ROOT/$dir" "$REPO_ROOT/$patch"; then
    c_green "already applied : $patch"
    [ "$MODE" = apply ] && fix_modes "$dir" "$REPO_ROOT/$patch"
  elif appliable "$REPO_ROOT/$dir" "$REPO_ROOT/$patch"; then
    case "$MODE" in
      check)  c_blue "not applied     : $patch" ;;
      apply)  git -C "$REPO_ROOT/$dir" apply "$REPO_ROOT/$patch" \
                && { c_green "applied         : $patch"; fix_modes "$dir" "$REPO_ROOT/$patch"; } \
                || { c_red "apply failed: $patch"; rc=1; } ;;
      revert) : ;;
    esac
  else
    c_red "does NOT apply  : $patch"; rc=1
  fi
done

exit $rc
