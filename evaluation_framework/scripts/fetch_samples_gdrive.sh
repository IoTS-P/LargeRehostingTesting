#!/usr/bin/env bash
# Fetch the firmware sample set from Google Drive into evaluation_samples/.
#
#   scripts/fetch_samples_gdrive.sh <google-drive-url-or-id> [--dest DIR] [--cookies FILE]
#                                   [--rounds N] [--delay SECONDS] [--keep-archives] [--dry-run]
#
# <google-drive-url-or-id> may be
#   * a Drive *folder* link        https://drive.google.com/drive/folders/<id>
#   * a *file* link                https://drive.google.com/file/d/<id>/view?usp=sharing
#   * a share link of any shape    https://drive.google.com/open?id=<id>
#   * a bare file or folder id
#
# Google Drive *quotas*, not permissions, are the usual reason a large download stops part way
# through with `Cannot retrieve the public link of the file ... or have had many accesses` —
# gdown raises that message whenever the page Google returns is not a download page.  Anonymous
# downloads are rate limited hard, and folder *listings* are capped too (there are reports that
# Drive returns only the first 50 entries, which gdown cannot see past).  What follows from that:
# individual file failures do not abort the rest, completed files are skipped on a re-run, so a
# later run picks up the remainder.  --rounds/--delay automate those re-runs, and the listing
# count printed at the end shows whether the folder itself is short or the listing was truncated.
#
#   restricted share    → --cookies <file> (a Netscape cookies.txt).  Cookies also lift the
#                         *anonymous* download quota, so they help even for a public share.
#                         Get one with `gdown --cookies-from-browser chrome` on a signed-in
#                         machine and copy ~/.cache/gdown/cookies.txt over.
#   quota / rate limit  → re-run later (minutes, occasionally up to a day); every run resumes.
#                         --rounds N --delay SECONDS retries unattended.
#
# gdown has renamed and dropped flags across major versions: --remaining-ok (gdown 4.x) and
# --fuzzy are gone in 6.x, where --continue covers the old "skip the files that are already
# there, resume the partial ones" behaviour.  This script asks the installed gdown (--help)
# which flags it accepts and passes only those, and prints the version it used, so
# `pip install --upgrade gdown` cannot break the download.  Passing -O without a trailing
# slash still means "put the folder's contents directly into DIR" in 6.x, which is what the
# unpacking below expects.
#
# The download lands in evaluation_samples/ (the import root of the pipeline, mounted at
# /data/samples in the container).  Archives (.zip/.tar/.tar.gz/.tar.xz/.7z/.rar) that
# the Drive folder contains are extracted in place and then removed, so `evaluation_samples/`
# ends up holding loose firmware files; pass --keep-archives to keep the archives.
#
# Typical use:
#   scripts/fetch_samples_gdrive.sh https://drive.google.com/drive/folders/XXXXXXXX
#   scripts/fetch_samples_gdrive.sh <folder-url> --rounds 5 --delay 600
#   scripts/fetch_samples_gdrive.sh <folder-url> --cookies ~/cookies.txt
#   scripts/import_samples.sh --list      # see what would be imported
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

DEST="$REPO_ROOT/evaluation_samples"
KEEP_ARCHIVES=0
DRY_RUN=0
COOKIE_FILE=""
ROUNDS=1
DELAY=300
GD_RETRIES=3
URL=""

while [ $# -gt 0 ]; do
  case "$1" in
    --dest)          DEST="$2"; shift 2 ;;
    --cookies)       COOKIE_FILE="$2"; shift 2 ;;
    --rounds)        ROUNDS="$2"; shift 2 ;;
    --delay)         DELAY="$2"; shift 2 ;;
    --gdown-retries) GD_RETRIES="$2"; shift 2 ;;
    --keep-archives) KEEP_ARCHIVES=1; shift ;;
    --dry-run)       DRY_RUN=1; shift ;;
    -h|--help)       awk 'NR > 1 { if (/^set -/) exit; print }' "$0"; exit 0 ;;
    -*)              echo "unknown option $1" >&2; exit 2 ;;
    *)               URL="$1"; shift ;;
  esac
done

[ -n "$URL" ] || { echo "usage: $0 <google-drive-url-or-id> [--dest DIR] [--cookies FILE] [--rounds N] [--delay SECONDS] [--keep-archives] [--dry-run]" >&2; exit 2; }
if [ -n "$COOKIE_FILE" ] && [ ! -s "$COOKIE_FILE" ]; then
    echo "!! --cookies $COOKIE_FILE does not exist or is empty" >&2; exit 2
fi

# ----------------------------------------------------------------- install gdown
# gdown is the only reliable way to fetch a Drive *folder* (it walks the folder and
# downloads every file, including the >100 MB ones that need the confirm token).
GdownBin=""
if command -v gdown >/dev/null 2>&1; then
    GdownBin="$(command -v gdown)"
elif [ -x "$HOME/.local/bin/gdown" ]; then
    GdownBin="$HOME/.local/bin/gdown"
else
    echo "==> gdown not found, installing it (pip --user)"
    if [ "$DRY_RUN" = 1 ]; then
        echo "    [dry-run] would run: python3 -m pip install --user --upgrade gdown"
    else
        python3 -m pip install --user --upgrade gdown >/dev/null 2>&1 \
          || python3 -m pip install --user --upgrade --break-system-packages gdown >/dev/null 2>&1 \
          || { echo "!! could not install gdown; install it manually (pip install gdown) and re-run" >&2; exit 1; }
        GdownBin="$HOME/.local/bin/gdown"
        [ -x "$GdownBin" ] || GdownBin="$(command -v gdown || true)"
    fi
fi
[ -n "$GdownBin" ] || [ "$DRY_RUN" = 1 ] || { echo "!! gdown unavailable" >&2; exit 1; }

# ----------------------------------------------------------------- which flags this gdown takes
# Ask the binary rather than assume a version: gdown 4.x wants --remaining-ok/--fuzzy, 5.x and
# 6.x reject both and expect --continue instead.  A dry run without gdown is given the set a
# current gdown accepts, since it is only printed.
G_COMMON=(); G_FOLDER=(); G_FILE=(); G_COOKIE=()
COOKIE_MODE=""
G_CACHE_COOKIES="$HOME/.cache/gdown/cookies.txt"

if [ -z "$GdownBin" ]; then
    G_COMMON=(--no-cookies --continue)
    G_FOLDER=(--folder)
    COOKIE_MODE="none (dry run: gdown is not installed yet)"
else
    echo "==> using $("$GdownBin" --version 2>&1 | head -1)"
    GDOWN_HELP="$("$GdownBin" --help 2>&1 || true)"
    has_flag() { printf '%s\n' "${GDOWN_HELP:-}" | grep -q -e "$1"; }

    has_flag --continue && G_COMMON+=(--continue)   # gdown >= 5: skip finished files, resume partial ones
    if [ "$GD_RETRIES" != 0 ] && has_flag --retries; then G_COMMON+=(--retries "$GD_RETRIES"); fi
    has_flag --folder       && G_FOLDER+=(--folder)
    has_flag --remaining-ok && G_FOLDER+=(--remaining-ok)   # gdown 4.x only; removed in 5.0
    has_flag --fuzzy        && G_FILE+=(--fuzzy)            # gdown 4.x only; 6.x parses Drive URLs itself

    if [ -n "$COOKIE_FILE" ]; then
        if has_flag --cookies; then
            G_COOKIE=(--cookies "$COOKIE_FILE"); COOKIE_MODE="cookies from $COOKIE_FILE"
        else
            echo "!! this gdown has no --cookies option; ignoring $COOKIE_FILE" >&2
            COOKIE_MODE="ignored $COOKIE_FILE (unsupported by this gdown)"
        fi
    elif [ -s "$G_CACHE_COOKIES" ]; then
        COOKIE_MODE="gdown's cached cookies ($G_CACHE_COOKIES)"
    elif has_flag --no-cookies; then
        G_COOKIE=(--no-cookies); COOKIE_MODE="none (anonymous) — pass --cookies <file> to use an account"
    fi
fi
echo "==> cookies: $COOKIE_MODE"

mkdir -p "$DEST"
echo "==> destination: $DEST"

# ----------------------------------------------------------------- fetch
is_folder_url=0
case "$URL" in
  *"/drive/folders/"*|*"/drive/u/"*"/folders/"*) is_folder_url=1 ;;
esac

if [ "$is_folder_url" = 1 ]; then
    # folder link: gdown puts the folder's contents directly in DEST (no trailing slash)
    CMD=("${GdownBin:-gdown}" "${G_FOLDER[@]+"${G_FOLDER[@]}"}" "${G_COOKIE[@]+"${G_COOKIE[@]}"}" "${G_COMMON[@]+"${G_COMMON[@]}"}" -O "$DEST" "$URL")
else
    # file link or bare id
    CMD=("${GdownBin:-gdown}" "${G_FILE[@]+"${G_FILE[@]}"}" "${G_COOKIE[@]+"${G_COOKIE[@]}"}" "${G_COMMON[@]+"${G_COMMON[@]}"}" -O "$DEST/" "$URL")
fi

if [ "$DRY_RUN" = 1 ]; then
    echo "    [dry-run] ${CMD[*]}"
    exit 0
fi

echo "==> downloading (this can take a while for a full corpus)"
before=$(find "$DEST" -type f ! -name 'README.md' | wc -l | tr -d ' ')

# gdown records a file that failed and carries on with the rest, then exits non-zero listing
# them, so one re-run picks up the remainder — hence the round loop.
round=1
last=$before
while : ; do
    echo "==> round $round/$ROUNDS (${last} file(s) present)"
    "${CMD[@]}"; rc=$?
    now=$(find "$DEST" -type f ! -name 'README.md' | wc -l | tr -d ' ')
    if [ "$rc" = 0 ]; then
        echo "==> download finished, gdown exit 0 ($((now - before)) new file(s) this run)"
        break
    fi
    echo "!! gdown exited $rc ($((now - last)) new file(s) in this round)" >&2
    if [ "$round" -ge "$ROUNDS" ]; then
        echo "   some files are still missing.  If gdown reported" >&2
        echo "     'Cannot retrieve the public link of the file ... or have had many accesses'" >&2
        echo "   that is Drive's download quota for anonymous access, not a permission problem:" >&2
        echo "   re-run later (every run resumes; minutes, occasionally up to a day), use an" >&2
        echo "   authenticated session (--cookies <file>) for a different quota, or raise" >&2
        echo "   --rounds/--delay to keep retrying unattended." >&2
        break
    fi
    [ "$now" = "$last" ] && echo "   no progress this round — waiting ${DELAY}s before retrying" >&2
    sleep "$DELAY"
    round=$((round + 1))
    last=$now
done

# What does the folder listing resolve?  A count far below what the corpus should be is Drive's
# listing cap rather than a short folder — worth knowing before blaming permissions.
if [ "$is_folder_url" = 1 ] && [ -n "$GdownBin" ] && printf '%s\n' "${GDOWN_HELP:-}" | grep -q -e "--json"; then
    listed=$("$GdownBin" "${G_FOLDER[@]+"${G_FOLDER[@]}"}" "${G_COOKIE[@]+"${G_COOKIE[@]}"}" --json "$URL" 2>/dev/null | tr -cd '{' | wc -c | tr -d ' ')
    if [ "$listed" -gt 0 ]; then
        echo "==> the folder listing resolves $listed entries"
        echo "    (Drive/gdown cap folder listings, so a corpus larger than this needs more passes)"
    fi
fi

# ----------------------------------------------------------------- unpack
shopt -s nullglob
extracted=0
for archive in "$DEST"/*.zip "$DEST"/*.tar "$DEST"/*.tar.gz "$DEST"/*.tgz "$DEST"/*.tar.xz "$DEST"/*.txz "$DEST"/*.7z "$DEST"/*.rar; do
    echo "==> extracting $(basename "$archive")"
    case "$archive" in
      *.zip)      unzip -q -o "$archive" -d "$DEST" ;;
      *.tar)      tar xf  "$archive" -C "$DEST" ;;
      *.tar.gz|*.tgz) tar xzf "$archive" -C "$DEST" ;;
      *.tar.xz|*.txz) tar xJf "$archive" -C "$DEST" ;;
      *.7z)       7z x -y -o"$DEST" "$archive" >/dev/null || echo "   (7z not installed, keeping the archive)" ;;
      *.rar)      unrar x -o+ "$archive" "$DEST/" >/dev/null || echo "   (unrar not installed, keeping the archive)" ;;
    esac
    extracted=$((extracted + 1))
    [ "$KEEP_ARCHIVES" = 1 ] || rm -f "$archive"
done

# flatten a single top-level directory (Drive folders often wrap everything once)
entries=$(find "$DEST" -mindepth 1 -maxdepth 1 ! -name 'README.md' | wc -l)
if [ "$entries" = 1 ]; then
    only=$(find "$DEST" -mindepth 1 -maxdepth 1 ! -name 'README.md' | head -1)
    if [ -d "$only" ]; then
        echo "==> flattening $(basename "$only")/"
        shopt -s dotglob
        mv "$only"/* "$DEST"/ && rmdir "$only"
        shopt -u dotglob
    fi
fi

after=$(find "$DEST" -type f ! -name 'README.md' | wc -l)
echo
echo "==> $after firmware file(s) in $DEST (was $before, $extracted archive(s) extracted)"
du -sh "$DEST"
echo "next:  scripts/import_samples.sh --list   # review, then"
echo "       scripts/import_samples.sh          # import into the akiba database"
