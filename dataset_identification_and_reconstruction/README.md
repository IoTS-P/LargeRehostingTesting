# Dataset identification — the evaluated firmware benchmark

This directory identifies the firmware benchmark used in the evaluation.  It contains
**no firmware**: the corpus is distributed separately through the Google Drive link
documented in `../evaluation_samples/README.md`.

**Firmware licensing.** Access to the firmware is not granted by this artifact.  The
FirmLine firmware is publicly available, while the OTACap-derived images were released to
us by their original authors under a non-disclosure agreement — **to obtain the firmware,
please contact us**, and we will point you to the respective data owners.

| File | What it is |
|------|------------|
| `binaries_md5.csv` | one row per image in the `akiba` database — `id, original_path, checksum (MD5), arch, format, compiler_spec, size`, 19,011 rows.  This is the identification table: it lets a reader match an image against the benchmark and reproduce the selection used by the evaluation. |
| `Dataset.csv` | dataset identification output: hash, vendor, device/MCU model, source. |

## Source composition

| Source | Images | Access |
|--------|--------|--------|
| FirmLine | 14,873 | publicly available |
| OTACap-derived (NDA release + OTA payloads extracted from vendor APKs) | 4,138 | original authors' data under NDA — **contact us** |
| **Total** | **19,011** | |

The evaluated subset — the images the pipeline stages 1–3 actually run on — is the
4,571 `ARM:LE:32:v8T` raw-binary images selected with
`WHERE arch = 'ARM:LE:32:v8T' AND format = 'Raw Binary'`; `binaries_md5.csv` carries the
`arch` and `format` fields needed to reproduce exactly that selection.
