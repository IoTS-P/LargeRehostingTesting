#!/usr/bin/env bash
# Host-side bootstrap: check out the tool submodules at the pinned commits,
# optionally initialise their nested submodules, and apply the overlays in patches/.
#
#   scripts/setup.sh                 # submodules + patches
#   scripts/setup.sh --with-nested   # also fetch nested submodules (fuzzware, firmline, ...)
#   scripts/setup.sh --no-patches
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

WITH_NESTED=0
WITH_PATCHES=1
for arg in "$@"; do
  case "$arg" in
    --with-nested) WITH_NESTED=1 ;;
    --no-patches)  WITH_PATCHES=0 ;;
    *) die "unknown option $arg" ;;
  esac
done

cd "$REPO_ROOT"

# pinned commits — same as the reference server, see patches/README.md
declare -A PINS=(
  [evaluated_tools_and_configurations/tools/RealworldFirmware]=4133f1fe23e7cfec0e2690acce2039e886ef869a
  [evaluated_tools_and_configurations/tools/firmline]=38f2ddb8a26781070d3ce27b09a84aee1a2fd045
  [evaluated_tools_and_configurations/tools/fuzzware]=e43dfbd38185ef4a374e1e9a6c9d930a9cc40653
  [evaluated_tools_and_configurations/tools/gdma]=f5979d009f18ce0069c7e57c9f557766b5f977c8
  [evaluated_tools_and_configurations/tools/hoedur]=a021fd064e5d831a427a25b17975779eef05c45b
  [evaluated_tools_and_configurations/tools/MultiFuzz]=44d0cc5df781ccba0cfb805814abe60655eb3334
  [evaluated_tools_and_configurations/tools/FirmRCA]=357958d05ebe69ac95af699a0f0da96c1cd7585d
)

export GIT_HTTP_LOW_SPEED_LIMIT=1000 GIT_HTTP_LOW_SPEED_TIME=60
# The firmline checkout tracks two Git-LFS objects, and one of them cannot be fetched at all:
# processed-firmware.txz (2.7 GB, its own processed corpus) is gone from the upstream store, so the
# smudge filter aborts the checkout with
#   Downloading processed-firmware.txz (2.7 GB)
#   Error downloading object ... Object does not exist on the server: [404]
#   fatal: processed-firmware.txz: smudge filter lfs failed
# and the submodule never lands.  No stage reads either file (fwdb.db.txz ships firmline's result
# database, whose schema setup_tools.sh builds itself), so the pointer files are the working state.
# A real download is possible with `git -C <tool> lfs pull` where an object still exists.
export GIT_LFS_SKIP_SMUDGE=1

# git refuses to touch a work tree owned by another uid ("detected dubious ownership in
# repository at '<path>'"), which is what a clone made as root, or a tree mounted in from the
# host, looks like.  The remedy is one config line per path and the error text names the path,
# so trust it and retry — otherwise every submodule fails with a message that reads like a
# network problem.
trust_dubious() {                      # stdin: git output; exit 0 when a path was trusted
  local out p
  out="$(cat)"
  p="$(printf '%s' "$out" | sed -n "s/.*dubious ownership in repository at '\([^']*\)'.*/\1/p" | head -1)"
  [ -n "$p" ] || return 1
  git config --global --get-all safe.directory 2>/dev/null | grep -qxF "$p" && return 1
  git config --global --add safe.directory "$p"
  c_blue "trusted   $p (owned by another user; git had refused to read it)"
  return 0
}

# Run a git command; on an ownership failure, trust the named path and run it once more.
git_trusted() {
  local out rc
  out="$("$@" 2>&1)"; rc=$?
  if [ $rc -ne 0 ] && printf '%s' "$out" | grep -q "dubious ownership"; then
    printf '%s' "$out" | trust_dubious >/dev/null && { out="$("$@" 2>&1)"; rc=$?; }
  fi
  printf '%s\n' "$out"
  return $rc
}

# A pinned commit usually sits outside the shallow history that `git submodule update --init
# --depth 1` fetches, and a clone that died halfway leaves a repository whose objects are
# missing altogether ("reference is not a tree").  Fetch the pin, and if it is not in this
# clone at all, start that one submodule over.
pin_submodule() {
  local path="$1" want="$2" have
  if [ ! -e "$path/.git" ]; then
    c_blue "pin     $path: no repository — initialising it"
    rm -rf "$path" ".git/modules/$path"
    git_trusted git submodule update --init --depth 1 "$path" >/dev/null 2>&1
  fi
  have="$(git -C "$path" rev-parse HEAD 2>/dev/null || echo none)"

  # A .git that resolves to the superproject's git directory makes `git -C <path>` operate on the
  # superproject itself: `rev-parse HEAD` then answers with its commit, and a checkout there would
  # move its work tree.  That is a stale pointer, not a submodule — refuse, and let the pointer
  # repair fix it (scripts/_fix_submodule_gitdirs.py, run before this loop).
  local gd
  gd="$(git -C "$path" rev-parse --absolute-git-dir 2>/dev/null || true)"
  if [ -n "$gd" ] && [ "$gd" = "$(cd "${REPO_ROOT:-.}" && pwd -P)/.git" ]; then
    c_red "pin     $path: its .git resolves to the superproject's git directory — refusing to touch it"
    c_red "          repair: python3 evaluation_framework/scripts/_fix_submodule_gitdirs.py ."
    return 1
  fi

  [ "$have" = "$want" ] && { c_green "ok      $path @ ${want:0:8}"; return 0; }

  c_blue "pin     $path ${have:0:8} -> ${want:0:8}"
  git_trusted git -C "$path" fetch --quiet --depth 1 origin "$want" >/dev/null 2>&1 \
    || git_trusted git -C "$path" fetch --quiet origin >/dev/null 2>&1
  git_trusted git -C "$path" checkout --quiet --detach "$want" >/dev/null 2>&1
  [ "$(git -C "$path" rev-parse HEAD 2>/dev/null)" = "$want" ] \
    && { c_green "ok      $path @ ${want:0:8}"; return 0; }

  # the objects are not in this clone: re-clone just this submodule.  Anything built inside it
  # (setup_tools.sh output, patches already applied) goes with it — say so rather than silently
  # throwing it away.
  c_red "pin     $path: ${want:0:8} is not in this clone — re-cloning the submodule"
  c_red "          build output inside $path is lost; scripts/setup_tools.sh rebuilds it"
  rm -rf "$path" ".git/modules/$path"
  git_trusted git submodule update --init --depth 1 "$path" >/dev/null 2>&1
  git_trusted git -C "$path" fetch --quiet --depth 1 origin "$want" >/dev/null 2>&1
  git_trusted git -C "$path" checkout --quiet --detach "$want" >/dev/null 2>&1
  [ "$(git -C "$path" rev-parse HEAD 2>/dev/null)" = "$want" ] \
    && { c_green "ok      $path @ ${want:0:8} (re-cloned)"; return 0; }
  c_red "FAILED  $path is not at ${want:0:8} (network, or the pin is gone from the remote)"
  return 1
}

c_blue "==> fetching submodules"
# A tree that was copied, renamed, or checked out as another user can end up with submodule .git
# files pointing somewhere else — including at the superproject, in which case `git -C <submodule>
# rev-parse HEAD` answers with the *superproject's* commit and a checkout there would move the wrong
# work tree.  Recompute those pointers first; pin_submodule below refuses any that are still wrong.
if command -v python3 >/dev/null 2>&1; then
  python3 "$SCRIPT_DIR/_fix_submodule_gitdirs.py" "$REPO_ROOT" 2>&1 | tail -5
else
  c_blue "python3 not found — skipping the submodule gitdir check"
fi

git_trusted git submodule sync --quiet >/dev/null 2>&1

# One update call covers every submodule, so an ownership error on the first aborts the rest:
# trust the paths it names and go again, retrying a couple of times for the network as well.
for attempt in 1 2 3; do
  out="$(git_trusted git submodule update --init --depth 1 "${!PINS[@]}" 2>&1 | tail -20)"
  rc=$?
  printf '%s\n' "$out"
  [ $rc -eq 0 ] && break
  printf '%s' "$out" | grep -q "dubious ownership" || sleep 10
done

for path in "${!PINS[@]}"; do
  pin_submodule "$path" "${PINS[$path]}"
  # GIT_LFS_SKIP_SMUDGE only covers this script; make the skip stick for the checkout itself, so a
  # later `git -C <tool> checkout` does not try the 2.7 GB (or 404) LFS object again.
  if command -v git-lfs >/dev/null 2>&1; then
    git -C "$path" lfs install --local --skip-smudge >/dev/null 2>&1 || true
  fi
done

SETUP_RC=0

if [ "$WITH_NESTED" = 1 ]; then
  c_blue "==> fetching nested submodules (this pulls radare2, binwalk, qemu patches, …)"
  # gdma belongs in this list: its `pipeline` and `emulator` are submodules as well, and
  # the admission overlays patch files inside `pipeline/`.  Without it `patch` can only
  # report "can't find file to patch" for admission-gdma.* and gdma.pipeline.patch.
  for path in "evaluated_tools_and_configurations/tools/firmline" \
              "evaluated_tools_and_configurations/tools/fuzzware" \
              "evaluated_tools_and_configurations/tools/gdma" \
              "evaluated_tools_and_configurations/tools/MultiFuzz"; do
    echo "--- $path"
    case "$path" in
      */firmline)
        # radare2's pinned tree carries one gitlink (test/ravc2_git_branch_test, a
        # test-only fixture) but ships no .gitmodules, so *any* recursive update dies
        # there with "fatal: No url found for submodule path
        # 'radare2/test/ravc2_git_branch_test' in .gitmodules" + "Failed to recurse into
        # submodule path 'radare2'".  Recursion would fetch nothing anyway (no .gitmodules
        # means no nested submodules), and the five submodules firmline needs are all
        # direct children, so init them without recursion.
        git -C "$path" submodule update --init 2>&1 | tail -10 || true
        ;;
      *)
        git -C "$path" submodule update --init --recursive 2>&1 | tail -10 || true
        ;;
    esac
  done
  # An empty nested tree is exactly what makes the overlays and the build fail later, so
  # check the ones they need rather than trusting the loop above.
  c_blue "==> checking the nested trees the overlays and the build need"
  for d in firmline/radare2 firmline/bgrep firmline/binwalk firmline/cpu_rec firmline/firmxray \
           fuzzware/emulator fuzzware/pipeline gdma/emulator gdma/pipeline MultiFuzz/ghidra; do
    if [ -n "$(ls -A "evaluated_tools_and_configurations/tools/$d" 2>/dev/null)" ]; then
      c_green "ok      $d"
    else
      c_red "MISSING $d"
      SETUP_RC=1
    fi
  done
fi

if [ "$WITH_PATCHES" = 1 ]; then
  c_blue "==> applying tool overlays"
  bash "$SCRIPT_DIR/apply_patches.sh" || { c_red "some patches did not apply (see above)"; SETUP_RC=1; }
fi

echo
if [ "$SETUP_RC" = 0 ]; then
  c_green "setup done."
else
  c_red "setup finished with errors (see the lines above)"
fi
echo "next:  scripts/build.sh        # build the container image"
echo "       scripts/up.sh           # start it"
exit $SETUP_RC
