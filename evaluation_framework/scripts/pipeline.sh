#!/usr/bin/env bash
# One command for the whole thing, on the host:
#
#   scripts/pipeline.sh                 # build image, start container, provision tools,
#                                       # import samples/, run every stage, export results
#   scripts/pipeline.sh --no-build      # skip the image build
#   scripts/pipeline.sh --no-tools      # skip tool provisioning (already done)
#   scripts/pipeline.sh --only 01,03    # only these pipeline stages
#   scripts/pipeline.sh --export-only   # just re-export the database
#
# Prerequisites: docker + docker compose, and firmware files in samples/.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

DO_BUILD=1; DO_TOOLS=1; DO_SETUP=0; EXPORT_ONLY=0
PIPELINE_ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --no-build)   DO_BUILD=0; shift ;;
    --no-tools)   DO_TOOLS=0; shift ;;
    --setup)      DO_SETUP=1; shift ;;
    --export-only) EXPORT_ONLY=1; shift ;;
    --only|--skip|--restore) PIPELINE_ARGS+=("$1" "$2"); shift 2 ;;
    *) die "unknown option $1" ;;
  esac
done

if [ "$EXPORT_ONLY" = 1 ]; then
  exec bash "$SCRIPT_DIR/export_results.sh" --with-artifacts
fi

if [ "$DO_SETUP" = 1 ]; then
  c_blue "### 1/6 submodules + patches"
  bash "$SCRIPT_DIR/setup.sh" --with-nested || c_red "setup problems (see above)"
else
  c_blue "### 1/6 submodules + patches (skipped, use --setup to run)"
fi

if [ "$DO_BUILD" = 1 ]; then
  c_blue "### 2/6 building the container image"
  bash "$SCRIPT_DIR/build.sh"
else
  c_blue "### 2/6 image build skipped"
fi

c_blue "### 3/6 starting the container"
bash "$SCRIPT_DIR/up.sh"

if [ "$DO_TOOLS" = 1 ]; then
  c_blue "### 4/6 provisioning the six tools (long; logs in results/logs/)"
  bash "$SCRIPT_DIR/setup_tools.sh" || c_red "tool provisioning reported problems — check results/logs/setup_*.log"
else
  c_blue "### 4/6 tool provisioning skipped"
fi

c_blue "### 5/6 importing samples/"
bash "$SCRIPT_DIR/import_samples.sh" || c_red "import failed (is evaluation_samples/ empty?)"

c_blue "### 6/6 running the pipeline"
bash "$SCRIPT_DIR/run_pipeline.sh" "${PIPELINE_ARGS[@]+"${PIPELINE_ARGS[@]}"}" || c_red "pipeline reported failures"

c_blue "### exporting results"
bash "$SCRIPT_DIR/export_results.sh" --with-artifacts

echo
c_green "done — results: $REPO_ROOT/evaluation_results/   (db/*.csv, logs/*.log, artifacts/)"
bash "$SCRIPT_DIR/status.sh"
