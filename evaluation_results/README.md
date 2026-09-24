# results/ — pipeline output

Everything the container produces is collected here. The directory is on the host
(bind-mounted to `/data/results`), so a container rebuild never costs you results.

```
results/
├── db/                      one CSV per database table and view of akiba-instance
│   ├── binaries.csv         imported firmware (path, arch, format, md5, …)
│   ├── firmxray_results.csv base address + entry validity per firmware
│   ├── firmxray_on_fuzzware_results.csv
│   ├── firmxray_on_fuzzware_replay_results.csv
│   ├── firmxray_fuzzware_replay_crashes.csv    (view, one row per replayed crash)
│   ├── fuzzware_admission_checks_v2.csv / hoedur_admission_checks_v2.csv /
│   │   multifuzz_admission_checks_v2.csv        seed-admission verdict per firmware (stage 02b)
│   ├── hoedur_fuzz_results.csv / hoedur_statistics_results.csv
│   ├── multifuzz_results.csv
│   ├── firmrca_results.csv / firmrca_classified_results.csv
│   └── firmline_results.csv
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
