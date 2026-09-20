#!/usr/bin/env bash
# Fetch the firmware sample set from Google Drive into evaluation_samples/.
#
#   scripts/fetch_samples_gdrive.sh <google-drive-url-or-id> [--dest DIR] [--keep-archives] [--dry-run]
#
# <google-drive-url-or-id> may be
#   * a Drive *folder* link        https://drive.google.com/drive/folders/<id>
#   * a *file* link                https://drive.google.com/file/d/<id>/view?usp=sharing
#   * a share link of any shape    https://drive.google.com/open?id=<id>
#   * a bare file or folder id
# Both "anyone with the link" and restricted shares work as long as the machine can
# reach Google (for restricted shares, run `gdown --fuzzy <url>` once interactively to
# cache the credentials, or use --cookies <file>).
#
# The download lands in evaluation_samples/ (the import root of the pipeline, mounted at
# /data/samples in the container).  Archives (.zip/.tar/.tar.gz/.tar.xz/.7z/.rar) that
# the Drive folder contains are extracted in place and then removed, so `evaluation_samples/`
# ends up holding loose firmware files; pass --keep-archives to keep the archives.
#
# Typical use:
#   scripts/fetch_samples_gdrive.sh https://drive.google.com/drive/folders/XXXXXXXX
#   scripts/fetch_samples_gdrive.sh https://drive.google.com/file/d/XXXXXXXX/view
#   scripts/import_samples.sh --list      # see what would be imported
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

DEST="$REPO_ROOT/evaluation_samples"
KEEP_ARCHIVES=0
DRY_RUN=0
URL=""

while [ $# -gt 0 ]; do
  case "$1" in
    --dest)          DEST="$2"; shift 2 ;;
    --keep-archives) KEEP_ARCHIVES=1; shift ;;
    --dry-run)       DRY_RUN=1; shift ;;
    -h|--help)       sed -n '2,25p' "$0"; exit 0 ;;
    -*)              echo "unknown option $1" >&2; exit 2 ;;
    *)               URL="$1"; shift ;;
  esac
done

[ -n "$URL" ] || { echo "usage: $0 <google-drive-url-or-id> [--dest DIR] [--keep-archives] [--dry-run]" >&2; exit 2; }

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

mkdir -p "$DEST"
echo "==> destination: $DEST"

# ----------------------------------------------------------------- fetch
is_folder_url=0
case "$URL" in
  *"/drive/folders/"*|*"/drive/u/"*"/folders/"*) is_folder_url=1 ;;
esac

if [ "$DRY_RUN" = 1 ]; then
    echo "    [dry-run] would download '$URL' into $DEST"
    if [ "$is_folder_url" = 1 ]; then
        echo "    [dry-run] gdown --folder --remaining-ok -O $DEST"
    else
        echo "    [dry-run] gdown --fuzzy -O $DEST/"
    fi
    exit 0
fi

echo "==> downloading (this can take a while for a full corpus)"
before=$(find "$DEST" -type f ! -name 'README.md' | wc -l)

if [ "$is_folder_url" = 1 ]; then
    # folder link: gdown recreates the folder structure below DEST
    "$GdownBin" --folder --remaining-ok --no-cookies -O "$DEST" "$URL" \
      || { echo "!! folder download failed — for a restricted share retry with cookies:" >&2
           echo "   gdown --folder -O $DEST --cookies <cookies.txt> '$URL'" >&2; exit 1; }
else
    "$GdownBin" --fuzzy --no-cookies -O "$DEST/" "$URL" \
      || { echo "!! file download failed — if the file is large, gdown may need the confirmation token:" >&2
           echo "   gdown --fuzzy -O $DEST/ '$URL'   (or use the folder link instead)" >&2; exit 1; }
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
