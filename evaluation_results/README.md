# results/ — pipeline output

Everything the container produces is collected here. The directory is on the host
(bind-mounted to `/data/results`), so a container rebuild never costs you results.

```
results/
├── reconnaissance_stage_1.csv     stage 1 E1, one row per firmware (base address, entry_valid, …)
├── emulation_stage_2.csv          stage 2 E2, the per-tool seed-admission verdicts
├── security_testing_stage_3.csv   stage 3 E3, coverage / crashes / hangs per tool
├── diagnosis_stage_4.csv          stage 4 E4, FirmRCA crash triage
├── db/                      one CSV per database table and view of akiba-instance
│   ├── binaries.csv         imported firmware (path, arch, format, md5, …)
│   ├── firmxray_stage_1.csv       base address + entry validity per firmware
│   ├── fuzzware_stage_3.csv       coverage / crashes per firmware
│   ├── gdma_stage_3.csv           the same run with --dma (DMA detection on)
│   ├── fuzzware_replay_stage_3.csv
│   ├── fuzzware_replay_crashes_stage_3.csv   (view, one row per replayed crash)
│   ├── fuzzware_admission_seeds_stage_2.csv / hoedur_admission_seeds_stage_2.csv /
│   │   multifuzz_admission_seeds_stage_2.csv    seed-admission verdict per firmware (stage 02b)
│   ├── hoedur_stage_3.csv / hoedur_statistics_stage_3.csv
│   ├── multifuzz_stage_3.csv
│   ├── firmrca_stage_4.csv / firmrca_classified_stage_4.csv
│   └── firmline_stage_1.csv
├── logs/                    full console log per stage (teed while the stage runs)
│   ├── 01_01_firmxray.log, 03_03_fuzzware.log, … pipeline_timeline.txt
│   └── setup_<tool>.log, export_<stage>.log
├── artifacts/               per-firmware work trees (only with --with-artifacts)
│   ├── fuzzware_projects/<id>/   config.yml, crash inputs, coverage archives
│   ├── hoedur_projects/<id>/
│   └── multifuzz_projects/<id>/
├── generated/               import list used for the last import
└── export_manifest.txt      when the export ran and which objects it contained
```

The `*_stage_<N>.csv` files under `db/` are the raw per-table export: `export_results.sh` names each
file after the stage its table belongs to (`firmxray_results` -> `firmxray_stage_1.csv`,
`firmxray_on_fuzzware_results` -> `fuzzware_stage_3.csv`), and only those files are tracked.
The four `*_stage_<N>.csv` files one level up are the hand-reviewed summaries of the paper numbers.

The export is a plain snapshot, not a diff: `scripts/export_results.sh` re-writes
`db/*.csv` from the live database, and `scripts/run_pipeline.sh` calls it after
every stage so a crash in stage 05 still leaves stages 01–04 usable.

Huge fuzzer-side dumps (`memac.bin` and friends, hundreds of MB per firmware) are
excluded from `artifacts/`; they stay in the `akiba_data` volume and can be looked
at inside the container:

```bash
scripts/shell.sh
ls /data/akiba/binaries/fuzzware_projects/<id>/
```
