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
# them (their paths carry no submodule prefix).  gdma's `pipeline` is a nested submodule
# as well — setup.sh --with-nested fetches it (the parent commit's gitlink pins the
# commit), and gdma.pipeline.patch is applied inside it.
NESTED_PATCHES=(
  "evaluated_tools_and_configurations/patches/fuzzware.emulator.patch:evaluated_tools_and_configurations/tools/fuzzware/emulator"
  "evaluated_tools_and_configurations/patches/fuzzware.pipeline.patch:evaluated_tools_and_configurations/tools/fuzzware/pipeline"
  "evaluated_tools_and_configurations/patches/gdma.pipeline.patch:evaluated_tools_and_configurations/tools/gdma/pipeline"
)

applied()  { git -C "$1" apply --reverse --check "$2" >/dev/null 2>&1; }
appliable() { git -C "$1" apply --check "$2" >/dev/null 2>&1; }
# `git diff` records a file *mode* in the index line, so `git apply` also warns about whitespace
# errors at EOF ("new blank line at EOF"), which says nothing about whether the patch landed and
# makes applied/failed hard to tell apart.  Every apply below passes --whitespace=nowarn for that.
apply_quiet() { git -C "$1" apply --whitespace=nowarn "$2"; }
# A submodule whose `.git` file points outside this checkout (the container bind-mounts the tool at a
# different depth than the repository keeps it, so the pointer resolves to a path that does not exist)
# still passes the initialised-check, but every `git apply` then runs against something else and the
# failure reads like a bad patch.  Name it instead.  Two layouts are legitimate and must not be flagged:
# `<repo>/.git/modules/...` (the submodule layout) and a checkout carrying its own `.git` (a standalone
# clone).
gitdir_escaped() { # <submodule dir>: 0 when its git dir cannot serve this checkout
  local gd
  # No git dir to resolve at all: the `.git` points at a path that does not exist (in the container the
  # tool is bind-mounted at a different depth than the repository keeps it, which is exactly how
  # `fatal: not a git repository: /data/tools/FirmRCA/../../../.git/modules/...` arises).  Everything
  # below would then run against something else.
  gd=$(git -C "$REPO_ROOT/$1" rev-parse --absolute-git-dir 2>/dev/null) || return 0
  [ -d "$gd" ] || return 0
  case "$gd" in
    "$REPO_ROOT/.git/modules/"*) return 1 ;;
    "$REPO_ROOT/$1"*)            return 1 ;;
    *)                           return 0 ;;
  esac
}
# Why a git-apply patch did not apply: the first few lines git itself prints, not just "does NOT apply".
why_not_applies() { # <submodule dir> <patch>
  git -C "$REPO_ROOT/$1" apply --check -v "$2" 2>&1 | head -4 | sed 's/^/      /'
}

# A patch stops being applicable in either direction once another overlay has rewritten
# the same lines — fuzzware.pipeline.patch and the admission-fuzzware diffs both touch
# fuzzware/pipeline/fuzzware_pipeline/*.py.  Reporting that as "does NOT apply" is a false
# alarm, so fall back to a content check: every substantive "+" line must exist in its
# target file.  Comment-only additions are ignored, because a captured reference diff keeps
# commented-out variants that a later revision may have dropped while keeping the change.
postimage_present() { # <dir> <patch>
  local dir="$1" patch="$2" f line
  while read -r f; do
    [ -n "$f" ] || continue
    [ -f "$REPO_ROOT/$dir/$f" ] || return 1
    while IFS= read -r line; do
      [ -n "${line//[[:space:]]/}" ] || continue
      case "${line#"${line%%[![:space:]]*}"}" in \#*) continue ;; esac
      grep -qxF "$line" "$REPO_ROOT/$dir/$f" || return 1
    done < <(awk -v want="$f" '
        /^\+\+\+ /{n=$2; sub(/^[ab]\//, "", n); cur=(n==want); next}
        /^--- /{cur=0; next}
        cur && /^\+/ {print substr($0,2)}' "$patch")
  done < <(awk '/^\+\+\+ /{n=$2; sub(/^[ab]\//, "", n); print n}' "$patch" | sort -u)
  return 0
}

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
  if gitdir_escaped "$dir"; then
    c_red "wrong git dir   : $dir — its .git points at the superproject, so no patch can apply"
    echo "      fix: evaluation_framework/scripts/setup.sh --with-nested"
    rc=1; continue
  fi
  if applied "$REPO_ROOT/$dir" "$REPO_ROOT/$patch"; then
    c_green "already applied : $patch"
    [ "$MODE" = apply ] && fix_modes "$dir" "$REPO_ROOT/$patch"
    continue
  fi
  case "$MODE" in
    check) if appliable "$REPO_ROOT/$dir" "$REPO_ROOT/$patch"; then
             c_blue "not applied     : $patch"
           elif postimage_present "$dir" "$REPO_ROOT/$patch"; then
             c_green "applied         : $patch (content matches; a later overlay re-touched these lines)"
           else
             c_red "does NOT apply  : $patch (submodule dirty or wrong commit?)"; rc=1
             echo "      at $(git -C "$REPO_ROOT/$dir" rev-parse --short HEAD), git says:"
             why_not_applies "$dir" "$REPO_ROOT/$patch"
           fi ;;
    apply) if appliable "$REPO_ROOT/$dir" "$REPO_ROOT/$patch"; then
             apply_quiet "$REPO_ROOT/$dir" "$REPO_ROOT/$patch" \
               && { c_green "applied         : $patch"; fix_modes "$dir" "$REPO_ROOT/$patch"; } \
               || { c_red "apply failed    : $patch"; why_not_applies "$dir" "$REPO_ROOT/$patch"; rc=1; }
           elif postimage_present "$dir" "$REPO_ROOT/$patch"; then
             c_green "applied         : $patch (content matches; a later overlay re-touched these lines)"
             fix_modes "$dir" "$REPO_ROOT/$patch"
           else
             c_red "does NOT apply  : $patch"; rc=1
             echo "      at $(git -C "$REPO_ROOT/$dir" rev-parse --short HEAD), git says:"
             why_not_applies "$dir" "$REPO_ROOT/$patch"
           fi ;;
    revert) if applied "$REPO_ROOT/$dir" "$REPO_ROOT/$patch"; then
              git -C "$REPO_ROOT/$dir" apply --reverse --whitespace=nowarn "$REPO_ROOT/$patch" \
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
  if gitdir_escaped "$dir"; then
    c_red "wrong git dir   : $dir — its .git points at the superproject, so no patch can apply"
    echo "      fix: evaluation_framework/scripts/setup.sh --with-nested"
    rc=1; continue
  fi
  if applied "$REPO_ROOT/$dir" "$REPO_ROOT/$patch"; then
    c_green "already applied : $patch"
    [ "$MODE" = apply ] && fix_modes "$dir" "$REPO_ROOT/$patch"
  elif appliable "$REPO_ROOT/$dir" "$REPO_ROOT/$patch"; then
    case "$MODE" in
      check)  c_blue "not applied     : $patch" ;;
      apply)  apply_quiet "$REPO_ROOT/$dir" "$REPO_ROOT/$patch" \
                && { c_green "applied         : $patch"; fix_modes "$dir" "$REPO_ROOT/$patch"; } \
                || { c_red "apply failed: $patch"; why_not_applies "$dir" "$REPO_ROOT/$patch"; rc=1; } ;;
      revert) : ;;
    esac
  else
    if postimage_present "$dir" "$REPO_ROOT/$patch"; then
      c_green "applied         : $patch (content matches; a later overlay re-touched these lines)"
      [ "$MODE" = apply ] && fix_modes "$dir" "$REPO_ROOT/$patch"
    else
      c_red "does NOT apply  : $patch"; rc=1
      echo "      at $(git -C "$REPO_ROOT/$dir" rev-parse --short HEAD), git says:"
      why_not_applies "$dir" "$REPO_ROOT/$patch"
    fi
  fi
done

# Admission-test changes.  These nine diffs were exported file by file against the
# reference server's copy of each tool (the files it keeps under /tmp/admission_extract
# there), so their paths carry the tool directory and their "+" side names an absolute
# path.  `git apply` would refuse the absolute side, therefore they are applied with GNU
# `patch -p2` inside the tool directory.  Direction is detected like above: a patch that
# reverses cleanly is already in place.
ADMISSION_PATCHES=(
  "evaluated_tools_and_configurations/patches/admission-fuzzware.__init__.patch:evaluated_tools_and_configurations/tools/fuzzware"
  "evaluated_tools_and_configurations/patches/admission-fuzzware.pipeline.patch:evaluated_tools_and_configurations/tools/fuzzware"
  "evaluated_tools_and_configurations/patches/admission-gdma.__init__.patch:evaluated_tools_and_configurations/tools/gdma"
  "evaluated_tools_and_configurations/patches/admission-gdma.pipeline.patch:evaluated_tools_and_configurations/tools/gdma"
  "evaluated_tools_and_configurations/patches/admission-hoedur.modeling.patch:evaluated_tools_and_configurations/tools/hoedur"
  "evaluated_tools_and_configurations/patches/admission-hoedur.lib.patch:evaluated_tools_and_configurations/tools/hoedur"
  "evaluated_tools_and_configurations/patches/admission-hoedur.runner.patch:evaluated_tools_and_configurations/tools/hoedur"
  "evaluated_tools_and_configurations/patches/admission-multifuzz.input.patch:evaluated_tools_and_configurations/tools/MultiFuzz"
  "evaluated_tools_and_configurations/patches/admission-multifuzz.main.patch:evaluated_tools_and_configurations/tools/MultiFuzz"
)

# The pre-image side of an admission diff is its target ("--- tools/gdma/pipeline/x.py"),
# and -p2 strips the first two components, so what remains is the path inside the tool.
patch_targets() { awk '/^--- /{p=$2; sub(/^[^\/]*\/[^\/]*\//, "", p); print p}' "$1" | sort -u; }

for entry in "${ADMISSION_PATCHES[@]}"; do
  pfile="${entry%%:*}"; dir="${entry##*:}"
  [ -f "$REPO_ROOT/$pfile" ] || { c_red "missing $pfile"; rc=1; continue; }
  [ -d "$REPO_ROOT/$dir" ] || { c_red "missing submodule $dir"; rc=1; continue; }
  # Several of these diffs patch files inside a *nested* submodule of the tool
  # (fuzzware/pipeline, gdma/pipeline).  When that has not been fetched, `patch` can only
  # say "can't find file to patch", which reads like a corrupt patch.  Name the real cause.
  target_missing=0
  while read -r t; do
    [ -n "$t" ] || continue
    [ -e "$REPO_ROOT/$dir/$t" ] || target_missing=1
  done < <(patch_targets "$REPO_ROOT/$pfile")
  if [ "$target_missing" = 1 ]; then
    c_blue "skipped (target not checked out — run scripts/setup.sh --with-nested): $pfile"
    continue
  fi
  if patch -p2 -R --dry-run --forward -i "$REPO_ROOT/$pfile" -d "$REPO_ROOT/$dir" >/dev/null 2>&1; then
    c_green "already applied : $pfile"
  else
    case "$MODE" in
      check) if patch -p2 --dry-run --forward -i "$REPO_ROOT/$pfile" -d "$REPO_ROOT/$dir" >/dev/null 2>&1; then
               c_blue "not applied     : $pfile"
             else c_red "does NOT apply  : $pfile"; rc=1; fi ;;
      apply) # dry-run first: a failed apply would otherwise leave .rej files behind
             if ! patch -p2 --dry-run --forward -i "$REPO_ROOT/$pfile" -d "$REPO_ROOT/$dir" >/dev/null 2>&1; then
               c_red "does NOT apply  : $pfile"
               patch -p2 --dry-run --forward -i "$REPO_ROOT/$pfile" -d "$REPO_ROOT/$dir" 2>&1 \
                 | grep -E "^(Hunk|can't find|patching)" | head -4 | sed 's/^/      /'
               rc=1
             elif patch -p2 --forward --no-backup-if-mismatch -i "$REPO_ROOT/$pfile" -d "$REPO_ROOT/$dir" >/dev/null 2>&1; then
               c_green "applied         : $pfile"
             else c_red "apply failed    : $pfile"; rc=1; fi ;;
      revert) if patch -p2 -R --forward --no-backup-if-mismatch -i "$REPO_ROOT/$pfile" -d "$REPO_ROOT/$dir" >/dev/null 2>&1; then
                c_green "reverted        : $pfile"
              else c_red "revert failed   : $pfile"; rc=1; fi ;;
    esac
  fi
done

exit $rc
