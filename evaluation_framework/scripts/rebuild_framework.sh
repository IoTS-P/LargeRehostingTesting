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
  # NOTE: `${MODULES_ONLY:+...}` expands whenever the variable is merely *set*, and MODULES_ONLY=0
  # is set — so the previous form re-ran this script with --modules-only every time, i.e. a plain
  # `rebuild_framework.sh` silently rebuilt the modules and never the framework.
  extra=""
  [ "$MODULES_ONLY" = 1 ] && extra="--modules-only"
  exec docker exec -i "$CONTAINER" bash -lc "bash /opt/rehosting/scripts/rebuild_framework.sh $extra"
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
# The modules cannot be built with a single ":akiba_modules:moduleJar-ALL" invocation: the
# project's build.gradle.kts resolves inter-module dependencies to the JAR files inside
# `build/libs` while it *creates* each module's task, so in a clean tree the task creation
# itself fails ("Could not create task … amod-<dep>.jar (No such file or directory)").
# scripts/build_akiba_modules.py parses the module list and the dependency graph and builds
# them in topological batches — the Dockerfile uses the same helper.
# Every module compiles against the fileTree "modules/*.jar" of akiba_modules (its `Public`
# configuration), which is where the akiba_mod_utils helpers (MemoryUtil, DisasmHelper,
# MemorySection, ...) have to be visible from.  That directory is git-ignored, so a tree that
# has never been built has no JAR in it and every module using those helpers dies with
# unresolved references that look unrelated (EntryFinder, FuzzwareStat, HasRTOS,
# HoedurStatistics, IoTGeneralStructures, ProgramInitialization, ...).  Stage one first.
stage_module_classpath() {
  mkdir -p "$BUILD/subprojects/akiba_modules/modules"
  cp "$BUILD"/subprojects/akiba_mod_utils/build/libs/amod-AkibaUtils-*.jar \
     "$BUILD/subprojects/akiba_modules/modules/" 2>/dev/null \
    || cp "$BUILD/dockerfile_needed/amod-AkibaUtils-1.0.jar" "$BUILD/subprojects/akiba_modules/modules/" \
    || die "no AkibaUtils JAR to put on the module compile classpath"
  c_blue "    compile classpath (akiba_modules/modules): $(ls "$BUILD/subprojects/akiba_modules/modules" | wc -l) jar(s)"
}
build_modules() {
  python3 /opt/rehosting/scripts/build_akiba_modules.py \
    --project "$BUILD/subprojects/akiba_modules" --gradle-root "$BUILD" \
    --gradle "$GRADLE" --jobs "${JOBS:-4}" "$@" || die "module build failed"
}
if [ "$MODULES_ONLY" = 1 ]; then
  stage_module_classpath
  build_modules
else
  $GRADLE --no-daemon --console=plain \
    ":akiba_framework:distZip" ":akiba_db_daemon:distZip" ":akiba_mod_utils:moduleJar-AkibaUtils" \
    || die "framework build failed"
  stage_module_classpath
  build_modules
fi

install_modules() {
  # The framework/db-daemon install below replaces /home/akiba/akiba_framework wholesale
  # (rm -rf + unzip of the distribution), which deletes modules/ — so the directory has to
  # be (re)created here, and the copy has to happen after that install.
  mkdir -p /home/akiba/akiba_framework/modules
  cp "$BUILD"/subprojects/akiba_modules/build/libs/amod-*.jar /home/akiba/akiba_framework/modules/
  # AkibaUtils is built in its own subproject (akiba_mod_utils) and the framework expects it
  # next to the analysis modules, so it has to be copied explicitly.
  cp "$BUILD"/subprojects/akiba_mod_utils/build/libs/amod-*.jar /home/akiba/akiba_framework/modules/ 2>/dev/null || true
  # Two modules read a file out of their own JAR at runtime — ConvertFirmToELF unpacks the C++
  # ELFBuilder binary, FirmRCA unpacks generateDataset.py.  The rebuild packages both now
  # (generateDataset.py is a tracked module resource, and the compiled ELFBuilder ships in
  # src/ConvertFirmToELF/resources/ELFBuilder/cmake-build-debug/ — added with `git add -f` past the
  # module's own .gitignore — so it lands in the JAR through the ordinary resource set), hence a tree
  # without prebuilt-modules/ — every clean clone — installs JARs that can actually run.  The
  # reference JARs are only preferred when they are present, e.g. on the reference machine or for a
  # byte-comparison against the published build.  See docs/provenance.md.
  for m in amod-ConvertFirmToELF-1.2.jar amod-FirmRCA-1.0.jar; do
    ref="/opt/rehosting/framework/prebuilt-modules/$m"
    if [ -f "$ref" ]; then
      cp -f "$ref" /home/akiba/akiba_framework/modules/ && c_blue "    $m: reference JAR kept (it carries a payload the rebuild drops)"
    else
      c_red "    $m: reference JAR missing at $ref"
    fi
  done
  c_green "modules updated: $(ls /home/akiba/akiba_framework/modules | wc -l) jar(s)"
}

if [ "$MODULES_ONLY" = 1 ]; then
  install_modules
fi

if [ "$MODULES_ONLY" = 0 ]; then
  for pair in "akiba_framework:framework" "akiba_db_daemon:db_daemon"; do
    proj="${pair%%:*}"; name="${pair##*:}"
    zip="$BUILD/subprojects/$proj/build/distributions/$proj-$VERSION.zip"
    [ -f "$zip" ] || { c_red "missing $zip"; continue; }
    rm -rf "/tmp/$proj-dist"; unzip -q "$zip" -d /tmp
    rm -rf "/home/akiba/$proj"
    # If that rm could not remove the tree — e.g. a root-owned directory was left inside it — the mv
    # below nests the distribution *inside* the old directory instead of replacing it, and
    # /home/akiba/<proj> ends up with no bin/ and no lib/.  lib.sh reads exactly those two paths to
    # decide whether it is inside the container, so the symptom is a stage that refuses to start with
    # "docker: command not found".  Fail loudly instead of producing that state.
    if [ -e "/home/akiba/$proj" ]; then
      c_red "cannot replace /home/akiba/$proj (something inside it is not removable by $(id -un)); fix the ownership and re-run"
      exit 1
    fi
    mv "/tmp/$proj-$VERSION" "/home/akiba/$proj"
    cp /opt/ghidra-jar/ghidra.jar "/home/akiba/$proj/lib/ghidra.jar"
    cp "$BUILD/dockerfile_needed/entrypoint.sh" "/home/akiba/$proj/" 2>/dev/null || true
    c_green "$name reinstalled from $zip"
  done
  install_modules
  c_blue "restart the container to pick the new daemon up: scripts/down.sh && scripts/up.sh"
fi
