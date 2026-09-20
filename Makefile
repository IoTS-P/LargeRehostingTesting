# Convenience targets; every one of them is a thin wrapper around evaluation_framework/scripts/.
# `make help` lists them.

SHELL := /bin/bash
.DEFAULT_GOAL := help

.PHONY: help setup build up down shell status tools import run export pipeline rebuild-framework clean check

help: ## list the targets
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

setup: ## check out submodules at the pinned commits and apply patches/
	evaluation_framework/scripts/setup.sh --with-nested

build: ## build the container image
	evaluation_framework/scripts/build.sh

up: ## start the container
	evaluation_framework/scripts/up.sh

down: ## stop the container (volumes kept; use scripts/down.sh --wipe to drop them)
	evaluation_framework/scripts/down.sh

shell: ## open a shell inside the container
	evaluation_framework/scripts/shell.sh

status: ## show container, samples and results state
	evaluation_framework/scripts/status.sh

tools: ## build/provision the six tools inside the container
	evaluation_framework/scripts/setup_tools.sh

import: ## import samples/ into the akiba database
	evaluation_framework/scripts/import_samples.sh

run: ## run the pipeline (all stages)
	evaluation_framework/scripts/run_pipeline.sh

export: ## export the database + artefacts to evaluation_results/
	evaluation_framework/scripts/export_results.sh --with-artifacts

pipeline: ## one-shot: build, start, tools, import, run, export
	evaluation_framework/scripts/pipeline.sh

rebuild-framework: ## rebuild akiba from evaluation_framework/framework/ inside the container
	evaluation_framework/scripts/rebuild_framework.sh

check: ## validate scripts, configs and patch applicability
	@for f in evaluation_framework/scripts/*.sh evaluation_framework/docker/entrypoint.sh; do bash -n $$f || exit 1; done; echo "shell syntax ok"
	@for f in evaluation_framework/pipeline_configs/*.json evaluation_framework/docker/*.json; do python3 -c "import json,sys;json.load(open('$$f'))" || exit 1; done; echo "json ok"
	@evaluation_framework/scripts/apply_patches.sh --check

clean: ## remove pipeline outputs (keeps samples/ and the container volumes)
	rm -rf evaluation_results/db/* evaluation_results/logs/* evaluation_results/artifacts/* evaluation_results/generated/* evaluation_results/export_manifest.txt