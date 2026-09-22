#!/usr/bin/env bash
# Rebuild the akiba framework / db daemon / module JARs from the mounted sources
# (framework/ at /opt/rehosting/framework) and reinstall them in the container.
#
#   scripts/rebuild_framework.sh          # after editing framework/
#   scripts/rebuild_framework.sh --modules-only
#
# The image already contains a built framework; this is for iterating on the
# framework sources without rebuilding the whole image. Needs network access from
# the container (Gradle distribution + Maven Central).
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

MODULES_ONLY=0
[ "${1:-}" = "--modules-only" ] && MODULES_ONLY=1

if ! in_container; then
  require_container
  exec docker exec -i "$CONTAINER" bash -lc "bash /opt/rehosting/scripts/rebuild_framework.sh ${MODULES_ONLY:+--modules-only}"
fi

SRC=/opt/rehosting/framework
BUILD=/tmp/akiba-build
VERSION=$(grep -o 'version = "[0-9.]*"' "$SRC/build.gradle.kts" | head -1 | sed 's/.*"\(.*\)"/\1/')
[ -n "$VERSION" ] || die "cannot read the version from $SRC/build.gradle.kts"
c_blue "==> rebuilding akiba $VERSION in $BUILD"

rm -rf "$BUILD"; mkdir -p "$BUILD"
cp -a "$SRC/." "$BUILD/"
# ghidra.jar was downloaded at image build time
ln -sf /opt/ghidra-jar/ghidra.jar "$BUILD/lib/ghidra.jar"
for p in akiba_framework akiba_db_daemon akiba_modules akiba_mod_utils akiba_mod_example; do
  mkdir -p "$BUILD/subprojects/$p/lib"
  ln -sf /opt/ghidra-jar/ghidra.jar "$BUILD/subprojects/$p/lib/ghidra.jar"
done

cd "$BUILD"
# The image installs Gradle from the release ZIP at /opt/gradle-8.8 (see the Dockerfile); use it
# instead of ./gradlew, whose wrapper would have to download the distribution again inside the
# container. Fall back to the wrapper when this script runs on a host without that path.
if [ -x /opt/gradle-8.8/bin/gradle ]; then GRADLE=/opt/gradle-8.8/bin/gradle; else GRADLE="./gradlew"; fi
c_blue "==> using Gradle: $GRADLE"
if [ "$MODULES_ONLY" = 1 ]; then
  $GRADLE --no-daemon --console=plain ":akiba_modules:moduleJar-ALL" || die "module build failed"
else
  $GRADLE --no-daemon --console=plain \
    ":akiba_framework:distZip" ":akiba_db_daemon:distZip" ":akiba_modules:moduleJar-ALL" \
    || die "framework build failed"
fi

cp "$BUILD"/subprojects/akiba_modules/build/libs/amod-*.jar /home/akiba/akiba_framework/modules/ \
  && c_green "modules updated: $(ls /home/akiba/akiba_framework/modules | wc -l) jar(s)"

if [ "$MODULES_ONLY" = 0 ]; then
  for pair in "akiba_framework:framework" "akiba_db_daemon:db_daemon"; do
    proj="${pair%%:*}"; name="${pair##*:}"
    zip="$BUILD/subprojects/$proj/build/distributions/$proj-$VERSION.zip"
    [ -f "$zip" ] || { c_red "missing $zip"; continue; }
    rm -rf "/tmp/$proj-dist"; unzip -q "$zip" -d /tmp
    rm -rf "/home/akiba/$proj"; mv "/tmp/$proj-$VERSION" "/home/akiba/$proj"
    cp /opt/ghidra-jar/ghidra.jar "/home/akiba/$proj/lib/ghidra.jar"
    cp "$BUILD/dockerfile_needed/entrypoint.sh" "/home/akiba/$proj/" 2>/dev/null || true
    c_green "$name reinstalled from $zip"
  done
  cp "$BUILD"/subprojects/akiba_modules/build/libs/amod-*.jar /home/akiba/akiba_framework/modules/
  c_blue "restart the container to pick the new daemon up: scripts/down.sh && scripts/up.sh"
fi
