# Empirical study — root-cause categories and case-level audit

Supporting material for the paper's empirical study (Section V): the twelve root-cause
categories P1–P12 that firmware base-address inference and emulation-based fuzzing
produce on Cortex-M firmware, and the case-level audit that classifies every reproduced
crash as a *tooling/environment artifact* rather than a firmware defect.

Scope and provenance:

- The cases collected here are the ones discussed in the paper (§V, problems P1–P12) plus the
  additional verified cases found during the audit; the paper's own evidence listings
  (Listings 1–4, the per-sample tables of Appendix A/E) are reproduced verbatim where they
  carry the argument.

- Corpus: 19,011 firmware images in the `akiba` database (FirmLine 14,873 publicly
  available + OTACap 4,138 under NDA, see `dataset_identification_and_reconstruction/`).
  The evaluated subset — the images the pipeline stages 1–3 run on — is the 4,571
  `ARM:LE:32:v8T` raw-binary images distributed through the Google Drive link in
  `evaluation_samples/README.md`; by source dataset those are 3,869 OTACap images and
  702 FirmLine images (paper Table XII), and the paper lists the MD5 hashes of every
  sample it discusses in its Table XIII, so each id used below can be resolved to a file.
- Tools: FirmXRay (base-address inference), Fuzzware and its DMA branch (GDMA),
  MultiFuzz, Hoedur (fuzzing) and FirmRCA (root-cause candidates), driven by the
  akiba 3.1.2 pipeline (`evaluation_framework/`).
- Published numbers: the per-stage result tables are `evaluation_results/stage_1..4.csv`;
  the categories below quote database fields (`firmxray_results`, `firmxray_on_fuzzware_*`,
  `hoedur_*`, `multifuzz_results`, `firmrca_results`) by name so each claim can be
  re-derived from a run.
- The representative binaries are identified by their dataset id; they are part of the
  sample set above, not stored in this repository.

The detailed case audit that was previously kept as separate working files (case
portraits, DMA experiments, FirmRCA verdicts, Firmline architecture comparison) is
summarised in the last two sections of this document.  The raw files remain in the
repository's history (commit "reproducible evaluation testbed").

---

# Root-Cause Categories (P1–P12) with Representative Cases

This document accompanies the artifact for the firmware-fuzzing study. It defines
twelve root-cause categories (P1–P12) observed when applying firmware base-address
inference (FirmXRay) and three emulation-based fuzzers (Fuzzware/GDMA, MultiFuzz,
Hoedur) to a large corpus of Cortex-M firmware images. For each category we give
one representative case with the evidence chain (crash records, disassembly,
database entries) and the reason it is classified as a *fuzzer/tooling artifact*
rather than a genuine firmware bug. Firmware identifiers refer to the dataset
index; all binaries are available under the `bins/` directory of the artifact.

---

## P1 — Insufficient Information Sources

**Definition.** The base-address solver fails to produce *any* candidate because
the input binary does not satisfy the solver's assumptions (standard Cortex-M
vector table + contiguous code). Such binaries are container images, TOC/boot
images, raw code without a vector table, resource packs, or corrupted files.
In the database (`firmxray_results`) these rows have `base_address IS NULL` and
`err_msg = 'failed'` (44 firmware images total).

**Representative case: firmware #804** (4,091 bytes, garbage header).
- FirmXRay crashes inside Ghidra's auto-analysis with
  `java.lang.NullPointerException: ... SymbolDB.getID() because "s" is null`
  (recorded in `logs/error.txt`); `base.txt` has no entry, output is absent.
- Static inspection: the file begins with the words `0xFFFFE72F / 0x03FC0000 / 0x45454545`
  (garbage); capstone decoding of the first halfword as Thumb gives
  `0x0000: b #0xfffffe62` — a branch back into the header, i.e. no valid entry at all.
  The file is a corrupted/truncated image, not a Cortex-M firmware.
- The 44 `failed` images fall into two groups:
  - **A, 9 images with a real NPE** (804, 1545, 5588, 5839, 8000, 13643, 15590, 16138, 18853):
    `logs/error.txt` holds the NPE, `base.txt` has no entry, no output file is written.
  - **B, ~35 images marked `failed` with `base = 0`** (4159, 4342, 5168, 5225, 5674, 7719,
    8156, 9602, 9820, 11155, 12198, ...): FirmXRay ran, found no anchor and wrote `Base = 0`;
    the import script labels the row `failed`.
- What the A group actually looks like (capstone code density over the whole file):

  | image | size | file header | Thumb density | real format |
  |---|---|---|---|---|
  | 5588 / 5839 | 25 MB | `0x3E8` container header | 93.8% | packed image (ELF/container) |
  | 13643 / 15590 / 18853 | 40–50 MB | ASCII `TOC` / `BOOT` | 88.5% | TOC boot image |
  | 1545 | 3.2 MB | data header (`iwui_resources`) | **2.2%** | resource/data pack, almost no code |
  | 804 | 4 KB | `0xFFFF` / `0x45454545` garbage | 35.6% | corrupt file |
  | 8000 | 49 KB | valid vector table, Reset past EOF | 82.4% | truncated firmware |
  | 16138 | 8 KB | Thumb code (`push {r4,lr}`), no vector table | 66.2% | bare code segment |

- The B group is the same *input-assumption* failure in a different shape, verified on
  four images by disassembly (base 0):
  ```
  4159  (ARM exception vector table, 33,685 B)
        0x0000: b   #0x7808      ; ARM-mode vector table
        0x0004: b   #4           ; spin
        0x0018: b   #0x77e8      ; IRQ vector
        0x0020: mov r0, #1       ; code starts here
  5168  (ARM function code, 19,564 B)
        0x0000: cmp r0, #2       ; ARM code, no vector table at all
        0x0004: bne #0x14
        0x0018: bl  #0x4900
  7719  (NVIDIA BPMP firmware, 48,004 B)
        0x0000: "bpmp" (62 70 6d 70)
        0x0004: 00 00 00 00
        0x0008: 0x21             ; version field
  16138 (bare Thumb code, 8,192 B)
        0x0000: cmp r1, #7 ; push {r4,lr}
  ```
  All four are 32-bit ARM code, headers or truncated images: FirmXRay reads offset 0 as a
  Cortex-M (Thumb) vector table, finds no anchor and yields `base = 0`.
- Tool-version sensitivity: Ghidra **11.3.2** (the version FirmXRay was run with here)
  raises the NPE, while Ghidra **12.1.2** analyses the same file 804 and recovers 43
  functions — the crash is in the version's symbol creation, not in FirmXRay's own logic.
- Root cause: Ghidra 11.3.2's function analyzer calls `SymbolDB.getID()` on a
  null symbol while analyzing a non-standard file; FirmXRay itself contains no
  `getID()` call (verified by source grep). Base inference therefore yields no
  candidates at all.
- Classification: tooling limitation — no firmware defect is involved.

---

## P2 — Improper Candidate Weighting and Selection

**Definition.** Multiple plausible base candidates exist (vector-table anchors,
absolute-address/LDR pairings), but FirmXRay scores them with equal weight
(`addScore(+1)` per pairing). Coincidental pairings from data bytes can
outscore the true base, so a wrong (non-zero) base is selected and dynamic
validation reports `entry_valid = invalid`.

**Representative case from the paper: #2915.** 48 of the 57 IVT entries point to the same
address (0xC069), so counting matches alone makes FirmXRay infer **0x8A0C** as the base — the
value that matches the duplicates best — even though it resolves no other candidate.
Manual analysis yields the correct base **0x1000**.  This is the degenerate form of
equal-weight scoring: one repeated value dominates the histogram.

**Further verified cases: #2547, #677, #2797** (verified by static re-check; the score
ordering itself comes from equal-weight counting, `addScore(candidate)` +1 per pairing,
with `sortByValues(scores, true)` taking the highest count that satisfies the base-range
constraint).
- **#2547** (`com.wowwee.chip`, 122,808 B): FirmXRay reports `base = 226`,
  `entry_valid = invalid`. The image contradicts it from the static side — SP = 0x200021F0
  and Reset = 0x0001B1FD, so the vector table *is* at offset 0 and `base = 0` is
  self-consistent (0x1B1FC lies inside the file). The wrong small value wins over the
  self-consistent 0. Caveat: the exact pairing that produced 226 could **not** be
  reproduced statically (exhaustive LDR-absolute/branch/vector pairings contain no 226),
  so the mechanism is established but that one number is reported as observed only.
- **#677** (SEGGER component firmware, 111,932 B): FirmXRay reports `base = 2`.
  SP = 0x1FFFE668 — RAM at 0x1FFFxxxx, which no Cortex-M device in the corpus uses; the
  image contains `SEGGER`, `Muzzleloader`, `Flash Driver: Bank 1/2 CRC`, FreeRTOS
  (`port_keil/ARM_CM0/port.c`) and `Starting RTT COMM MODE`, i.e. a vendor boot/peripheral
  component whose memory layout falls outside the solver's assumptions (286 references in
  the 0x1FFF0000 region).
- **#2797** (`freedrum_dfu`, Nordic DFU, 185,180 B): FirmXRay reports `base = 0x1F000`.
  SP = 0x20010000, Reset = 0x00028F09, and *three* bases explain that reset:
  0x28F09 − 0x26000 = 0x2F09 (inside the file, Thumb code at 0x2F08), 0x28F09 − 0x1F000 =
  0x9F09 (also inside), 0x28F09 − 0 = 0x28F08 (also inside). The solver picks 0x1F000;
  the SoftDevice-app convention of this family points to 0x26000.
- Classification: tooling limitation — base is wrong but non-zero, so the whole
  downstream analysis is anchored incorrectly.

---

## P3 — Invalid IVT Placement Assumption

**Definition.** FirmXRay's vector-table constraint reads the vector table from
*file offset 0* (`StringUtil.getVector`). When the firmware does not start with
a vector table (vendor header, download header, magic, all-zeros), the header
fields are mistaken for vector entries and a wrong base is produced.

**Representative case: firmware #1819.**
- FirmXRay reports `base_address = 0x8008000` and `entry_valid = invalid`.
- Paper case #1819 is a resistance probe from CorTalk whose image starts with
  vendor-specific metadata instead of an IVT; reading bytes 4–7 as the reset handler
  therefore yields an invalid entry point (immediate crashes and verification failure).
  Header excerpt (paper Listing 1, verbatim):
  ```
  1 00000000      ds "R1ERj"
  2 00000005      db 0x00,0x00,0x00,0xf4,0xcc
  3 0000000a      db 0x01,0x00,0xbc,0xfd,0xe5
  4 0000000f      ds "UApplication for RMU1ER"
  ```
- Paper case #11175 (Marvell 88W8686) is an OTA image that begins with an OTA-download
  header rather than the IVT, so the second entry is a header field.
- FirmXRay results for three verified variants of this failure (offset 0 does not hold
  a vector table, so `StringUtil.getVector` reads header fields as vectors):
  ```
  11175  gspi8686_v9.bin, 126,652 B, base 40 (0x28), entry_valid invalid
         offset 0x00-0x2B = 44-byte download header:
           [0x01, 0x04000000, 0x0C, 0x8AB41DA2, 0x01, 0x4E20, 0xD13475CD,
            0x01, 0xC0001000, 0x200, 0x26486BE9]
         offset 0x2C onward = contiguous Thumb code (0xB530 push {r4,r5,lr})
         the field 0x04000000 at offset 0x04 is taken for the Reset vector
         identity: Marvell 88W8686 G-SPI main firmware (md5 matches linux-firmware)
  4553   activity.napp, 113,050 B, base 20 (0x14), entry_valid invalid
         offset 0x00 headerVersion = 1, offset 0x04 magic "NANO",
         offsets 0x0C / 0x28 vendor tag "Goog"   (Google nanoapp container)
  160    all-zero header, base 0   (empty "vector table")
  ```
- The inferred base lies 0x8000 (32 KiB) into the file: the first 32 KiB of the
  image are vendor information / boot metadata, and the actual vector table
  does not appear at file offset 0 as the solver assumes.
- SoftDevice/MBR interrupt forwarding is the second shape: the vector table *is* at
  offset 0, but every IRQ entry points at one trampoline, so the "handler − branch"
  pairing produces a nonsense base. #3164: SP = 0x200012E8, Reset = 0x00021D11, the IRQ
  entries point to 0x2F15 forty-eight times, SoftDevice code starts at 0xC00, the string
  " nRF5x" is present → FirmXRay reports 6,292. #2417 (`sd_bl.bin`, PowerBlue WC v210,
  184,384 B): the same structure with 0xC175 sixteen times → FirmXRay reports 864.
- A third shape is reported for completeness: #2439 (QCPRPlug, 398,704 B) where the base
  is actually *correct* (Reset = 0x86C41 = 0x26000 + 0x60C41, and the reset handler's
  target 0x86F0D is only inside the file for base 0x26000) while `entry_valid = invalid`;
  the dynamic-validation failure could not be reproduced statically, so this case carries
  less weight than the five above.
- Classification: tooling limitation — the "file offset 0 = vector table"
  assumption fails for any non-standard header.

---

## P4 — Unsupported ISA Instructions

**Definition.** The emulators use a Cortex-M model **without an FPU**
(Unicorn `UC_MODE_THUMB | UC_MODE_MCLASS`; icicle; QEMU default). Firmware
compiled for Cortex-M4F with `-mfloat-abi=hard` executes VFP/NEON floating-point
instructions (`vldr`, `vstr`, `vmov`, `vpush`, `vcvt`, ...) that decode as
`UC_ERR_INSN_INVALID` and crash.

**Representative case: firmware #3741** (Crazyflie 2.0, `cf2-2020.04`,
218,340 bytes).
- 475 crashes; crash PC concentrates at `0x08028554` (offset 0x28554).
- Disassembly (base 0x08000000):
  ```
  0x28548: strd r0, r1, [sp, #8]
  0x2854C: bne.w 0x2881e
  0x28550: vldr  d7, [pc, #0x2e4]      ; double-precision load
  0x28554: vstr  d7, [sp, #0x10]   <<<< crash: VFP store
  0x28558: ldrd  r2, r3, [sp]
  ```
- Evidence: the crash point itself is a VFP instruction; 14 further VFP
  encodings exist in the file. The firmware is the Crazyflie flight controller
  (floating-point attitude math), compiled with hard-float ABI.
- Root cause: entire FPU instruction set missing from the emulation model —
  any floating-point instruction crashes (≈3,769 crashes / ≈17 firmware images).
- Classification: emulator model limitation, not a firmware defect.

---


**Second case: firmware #229** (`syrp_genie_2_pan_tilt`, 35,416 bytes = 0x8A58).
- 609 crashes, crash PC concentrated at `0x080047BE` (offset 0x47BE).
- Disassembly (base 0x08000000):
  ```
  0x047BA: movw r3, #0x1024
  0x047BE: vldr  s18, [pc, #0x1c0]   <<<< crash: single-precision VFP load
  0x047C2: strb  r5, [r4, r3]
  0x047C4: bl    0x205c
  ```
- Evidence: the crash point is itself the VFP instruction (no decode lag); the same function
  later contains `vmov s15` (0x3D46), `vcvt.f32.s32 s15` (0x3D4C) and `vstr.32 s15` (0x3D5C),
  i.e. a complete floating-point sequence — the image really is FPU-compiled.
- Root cause: the pan/tilt gimbal's angle/acceleration math (`float`) hits `vldr` in a model
  without an FPU.

**Third case: firmware #3755** (Crazyflie 2.0, `cf2-2020.02`, 202,888 bytes).
- 371 crashes, crash PC concentrated at `0x08027A50` (offset 0x27A50).
- Disassembly (base 0x08000000):
  ```
  0x27A3C: vmov  d0, r0, r1          ; double-precision register transfer
  0x27A40: pop   {r3, r4, r5, pc}
  0x27A4E: vmov  d0, r2, r3      <<<< crash (reported PC lags 2 bytes)
  0x27A52: pop   {r3, r4, r5, pc}
  ```
- Evidence: a second `vmov d0` at 0x27A3C — a double-`vmov` sequence; 10 further VFP
  encodings in the file. Same source as #3741 (a different Crazyflie release).
- Note: #3755 also appears as a P7 case (its 25 MultiFuzz crashes are all inside interrupt
  handlers); the 371 `Invalid instruction` crashes here are the FPU gap, so one image can
  carry two independent root causes.

**The three instances differ in exactly the dimension the emulator model exposes:**

| sample | instruction | precision | reported lag | firmware |
|---|---|---|---|---|
| #229 | `vldr` | single (`s` registers) | 0 bytes | pan/tilt gimbal |
| #3741 | `vstr`/`vldr` | double (`d` registers) | 0 bytes | Crazyflie drone |
| #3755 | `vmov` | double (`d` registers) | 2 bytes | Crazyflie drone |

Class statistics and the key discrimination: of the 13,838 `UC_ERR_INSN_INVALID`-class crashes
across 74 images, 7,087 are the other flavour of "incomplete firmware" (execution wandering
into a missing flash region, decoding 0xFF), and only ≈3,769 (≈17 images) are genuine VFP/NEON
(an encoding in 0xEC00–0xEFFF within ±16 bytes of the crash PC). The remaining ≈2,982 are
`non_VFP` — jumps into data that decode as garbage — which are firmware defects, not P4.
Local replay with an explicit no-FPU Unicorn model reproduces the crashes 100% consistently,
and the same instructions execute on a default ARMv7+VFP model, which pins the cause to the
model rather than to the image.

## P5 — Inaccurate Memory Layout

**Definition.** Real hardware has external bus devices (FSMC NOR flash, SDRAM),
second flash banks, and high segments (0x10000000+). The emulators map only the
firmware file image; accesses to unmapped regions raise
`UC_ERR_READ_UNMAPPED/WRITE_UNMAPPED`. This is an incomplete address-space model,
not a firmware bug.

**Representative case: firmware #227** (syrp_genie_2 family).
- 428 crashes of the form `INVALID READ: addr = 0x00000000A0001000` —
  an external SDRAM/SRAM address (0xA0000000 region) that the emulator does not
  map.
- Evidence (sibling #224, same family, fully traced):
  ```
  0x03A96: ldr.w r3, [r4, #0x2c0]      ; r3 = 0xA0001000 (external RAM base)
  0x03A9E: ldr   r2, [r3]         <<<< crash: read [0xA0001000]
  ```
  The bootloader configures the FSMC external memory controller (literal pool
  contains 0x60000000-region values; SP = 0x20080000 large RAM) and then
  accesses the external SDRAM mapped at 0xA0000000.
- Further paper cases of the same gap:
  - **#12123**: the firmware legitimately accesses external RAM at 0x60000AFC, which the
    generic memory layout does not cover.
  - **#3519**: the image loads at 0x0 with a size of 131,072 bytes, yet its memory-fault ISR
    entry pointer is 0x1001CA01 — i.e. an additional executable region beyond the loaded
    image, so the fault path is directed outside the mapped range.
  - **#2715**: the firmware's ROM base is 0x20001F40, which overlaps the emulated RAM region
    at 0x20000000; the resulting permission conflict makes initialization fail under Hoedur,
    Fuzzware and MultiFuzz alike (the paper counts 25 such overlap cases, recorded in the
    unconfirmed row).
  - Tool-side variant: AidFuzzer maps only part of the system-peripheral region
    0xE0000000–0xFFFFFFFF, which makes it crash immediately for 99% of the tested samples
    that touch another system peripheral.
- Classification: emulator address-space modeling gap. The distinguishing
  criterion is an aligned access address (0x1000 granularity) with matching
  literal references in the firmware = real external-device access.

---

## P6 — Absolute Speed Gap

**Definition.** Firmware contains pure-CPU delay/wait loops (spin on counters or
flags) that never read fuzz input. The fuzzer cannot advance the firmware and
reports `InstructionLimit: UC_ERR_NO_FUZZ_CONSUMPTION` — the firmware waits for
real hardware (clock/peripheral) that the emulator does not provide.

**Representative case from the paper: firmware #7658.** During normal device
initialization `delay_millis` calls `count_us(999)` once per millisecond; each call executes
three basic blocks, so with `millis` = 1,500 the delay alone runs over three million basic
blocks. Hoedur and MultiFuzz treat executions beyond three million basic blocks as a
timeout, so fuzzing terminates before the firmware reaches any subsequent code.  The delay
loop itself (paper Listing 2, verbatim):
```c
1 void delay_millis(int millis) {
2     while (millis) {--millis; count_us(999);}
3 }
4 void count_us (int count) {
5     do --count;
6     while (count);
7 }
```
Corpus-level hang counts (paper §V): Hoedur 27,987 hangs from 1,065 samples, MultiFuzz 2,987
hangs from 1,301 samples — Hoedur does not deduplicate hangs, which is why its number is an
order of magnitude larger.

**Traced case: firmware #3034** (ICP_NRF52, 120,012 bytes = 0x1D4CC).
- FirmXRay: `base = 0x23000` (143,360), `entry_valid = valid` — the image itself is
  well-formed; only the timing model is wrong.
- 41 `InstructionLimit: UC_ERR_NO_FUZZ_CONSUMPTION` crashes at icount ≈ 1.3M with
  `active_irq = 18`; crash PCs 0x25106 (offset 0x2106), 0x285D2 (0x55D2), 0x2831A (0x531A).
- Disassembly at the crash point (Ghidra, base 0x23000):
  ```
  0x055BA: ldr r3, [sp, #0x14]
  0x055BC: subs r3, r2, r3
  0x055C0: ldr r3, [sp, #0x14]
  0x055C2: cmp r3, #0
  0x055C4: bne 0x55d2            ; inner loop
  0x055C6: ldr r3, [sp, #0x18]
  0x055C8: subs r3, #1
  0x055CE: cmp r3, #0
  0x055D0: beq 0x55da            ; outer exit
  0x055D2: ldr r3, [sp, #4]   <<<<< crash point
  0x055D4: cmp r3, #0
  0x055D6: bne 0x559e            ; back to the outer loop
  ```
  Two nested counters, loop body `ldr/cmp/bne` only: **no peripheral access, no fuzz
  consumption**. The crash PC 0x25106 belongs to the initialisation function (reads the
  status flag 0x20009F04, writes NVIC 0xE000E100): the firmware waits on a RAM flag that
  only real hardware sets and spins 1.3M instructions.
- Corpus statistics for the class (MultiFuzz, images with `actual_fuzz_time >= 3600`):
  `InstructionLimit` 2,987 records over 1,301 images — `UC_ERR_NO_FUZZ_CONSUMPTION` 1,376
  (P6 attribution 1,282 + 94 records belonging to the truncated-firmware class) and
  `UC_ERR_INTERRUPT_LIMIT` 1,611 (long loops that trip the emulator's interrupt-activation
  cap; the firmware has **no crash point**, a suspension-like outcome currently tallied with
  the unconfirmed row).
- Contrast that keeps the class boundary honest: #3412 (HARDWARIO USB Dongle, 44,220 B,
  base 0x08000000) also ends in `UC_ERR_INTERRUPT_LIMIT` (39 records, icount 8.5M,
  `active_irq = 0`, PC 0x08003A56), but its loop **does** touch hardware — `bl 0x3840`
  loads the SPI2 literal 0x40003800, polls SR bit 1 then bit 0 and writes DR — so bytes go
  out over SPI2 while the polled injection fires the enabled IRQs. That is an
  interrupt-injection effect, not a pure CPU delay, which is why it is not counted as P6.
- Classification: firmware behavior incompatible with the input-driven
  simulation model — the delay is legitimate on real hardware (it waits for a
  real clock), so the crash is unreachable in reality (FP).

---

## P7 — Incorrect Interrupt Timing

**Definition.** The fuzzer triggers interrupts by *polling*: every fixed number
of executed instructions (`every_nth_tick = 0x3e8` in `config.yml`,
round-robin), unrelated to real peripheral events. ISRs therefore run before
device structures are initialized, dereferencing uninitialized function
pointers / callback tables and crashing inside interrupt context.

**Representative case from the paper: firmware #2938.** All crashes of that sample occur on
entry to ISR number 21, which dereferences an uninitialized function pointer whose value
resolves to **0x20003C00** — an address inside RAM, so the first thing the handler does is
jump into data.  The paper's three conditions for a real interrupt (enabled in the
controller, the peripheral's own interrupt-enable bit set, *and* the hardware event having
happened) hold only in part for all fuzzers compared, which is exactly what makes the
infeasible ISR entry possible.

**Traced case: firmware #3755** (Crazyflie 2.0, cf2-2020.02,
202,888 bytes).
- All 25 MultiFuzz crashes occur *inside interrupts* (`active_irq = 83/87/47/56`
  i.e. IRQ67/71/31/40), while the main loop never crashes (`no_irq = 0`); the
  sibling GDMA run shows 72 crashes at the same instructions and Hoedur 891.
- Trace evidence (MultiFuzz `0x8009b9d_0x0_jump_null`):
  ```
  icount     pc         sp          last_read   last_value
  183739     0x8009b92  0x2001ffa3  0x50000018  0x100010   ; ISR chain → callback entry
  183739     0x8009b9a  (blx r3, r3=0)                     ; jump 0 → ExecViolation
  ```
  A second trace (`0x8006c9b_0x80093c0_read_error`) reaches 0x80093C0 and
  reads `[r3+8]` with r3 = NULL → ReadUnmapped at address 0x8.
- Disassembly (base 0x8004000):
  ```
  IRQ67 ISR: 0x800951C ldr r0,[pc,#4]        ; r0 = 0x20019130 (device object)
             0x800951E b.w 0x80090C8         ; ISR main processing
  jump-0:    0x8009B92 push {r3,lr}          ; callback (called by ISR chain)
             0x8009B94 ldr.w r3,[r0,#0x5e4]  ; callback-table pointer
             0x8009B98 ldr r3,[r3,#0x20]     ; callback function
             0x8009B9A blx r3                ; r3 = 0 (callback not registered)
  read-0x8:  0x80093BC ldr r3,[r3,#0x18]     ; sub-structure pointer = NULL
             0x80093C0 ldr r3,[r3,#8]        ; read [NULL+8] → ReadUnmapped
  ```
- Mechanism: on real hardware the peripheral events fire only *after* the
  driver initializes and registers its callbacks, so the ISR cannot execute
  before that point. The fuzzer's fixed-interval polled injection runs the ISRs
  (IRQ67/71/31/40) before the device object 0x20019130 is initialized → empty
  callback / NULL sub-structure → crash.
- Discriminators (all satisfied): crash inside interrupt context
  (`active_irq ≠ 0`); crash point is ISR-chain code; main loop never crashes
  (`no_irq = 0` — excludes uninitialized-pointer-in-any-context causes);
  multiple ISRs show the same pattern (systematic timing effect).
- Classification: fuzzer interrupt-injection artifact (FP).

---


**Corpus-level scale.** Interrupt-context crashes are the second-largest bucket in MultiFuzz:
17,584 crashes (25.9% of its valid crashes) carry a non-zero `active_irq`, versus 6,441 (3.0%)
for GDMA (`err_in_interrupt = true`). The ranking of the affected images is led by #1475
(17,529 crashes), #255 (13,439), #10345 (9,228) and #258 (9,183) — i.e. the class is dominated
by a handful of heavily interrupt-driven firmwares rather than spread uniformly.

**Discrimination from P10.** Both categories end in a NULL/empty callback or structure, so the
separating evidence is the *context*, not the faulting instruction:
- P7: the crash is inside an ISR (`active_irq ≠ 0`), the crash-point disassembly belongs to an
  ISR chain, and the main loop never crashes — a no-interrupt run of the same image stays
  clean (`no_irq = 0`). #3755 satisfies all three.
- P10: the uninitialized-pointer fault also happens with interrupts disabled, because the
  firmware dereferences the pointer on its own path.
This is why the ISR-context counts above are attributed to P7 and not to P10.

**Cases that were moved out after re-checking** (recorded here because the distinction matters):
- #2985 (Zepp BLE): the main-loop event-dispatch entry lacks the Thumb bit → `blx` to an even
  address → the icicle SP mutation described under P12; not an interrupt-timing effect.
- #2595 (UART_SRC): same dispatch-table family; 98 of its 108 crashes have `active_irq = 0`,
  which rules P7 out.
- #2666 (Nordic DFU `bca_release`): the ISR writes GPIO at 0x50000000 + idx*4 + 0x700 with a
  garbage index → out-of-range unmapped access; that is a peripheral-response/pointer class
  (P8/P10), even though it happens inside an ISR.

## P8 — Inaccurate Peripheral Responses

**Definition.** A peripheral model exists (the register region is mapped and
reads succeed) but returns fuzz bytes instead of protocol-consistent values
(status bits, chip IDs, flags). The firmware compares the returned value against
expected constants, takes the wrong branch, and later crashes.

**Representative case: firmware #1179** (STM32 SPI2 polled transfer).
- The firmware performs full-duplex SPI2 (0x40003800) transfers; disassembly
  shows two wait loops polling `SPI2_SR` (0x40003808): loop 1 waits for TXE
  (bit 1), loop 2 waits for RXNE (bit 0), then reads `SPI2_DR` (0x4000380C):
  ```
  0x0801B0C4: movs r1, #2            ; TXE bit
  0x0801B0C8: bl 0x801A942           ; read SR, test TXE
  0x0801B0CE: beq 0x801b0c4          ; spin while TXE clear
  0x0801B0D4: bl 0x801A942           ; read SR, test RXNE
  0x0801B0DA: beq 0x801b0d0          ; spin while RXNE clear
  ```
- Paper case #2691 (Listing 3, verbatim) shows the mechanism inside the USART ISR: the
  frame length is updated only when the corresponding receiving event happens, but the
  tools satisfy the status-register checks unconditionally, so the branch is taken with
  `frameLen == 0`, `idx` runs past `rxBuf`, and an invalid function pointer is dereferenced.
  ```c
  1 int IRQ_handler(void) {
  2    if((MEM[0x40002304]&4)!=0 && MEM[0x40002108]){
  3       *(rxBuf + idx++) = MEM[0x40002518];
  4    if (frameLen == idx) {
  5      frameLen = 0; ...
  ```
- Paper case #15998 belongs to the same class through a clock-control register: an
  unexpected value for the peripheral clock register causes an immediate persistent hang
  (the firmware polls a status bit that the model never sets).
- `config.yml` has zero `mmio_models` entries → the SR read returns fuzz input
  bytes; the TXE/RXNE bits are almost never set, so the firmware spins until
  the fuzzer's interrupt-activation limit terminates it
  (`UC_ERR_INTERRUPT_LIMIT`). On real hardware the SPI controller sets these
  flags by protocol and the poll passes immediately.
- Fully traced protocol-level case #11381 (STM32L152 I2C1 → MFX chip ID): the
  firmware expects the MFX chip ID 0x7B/0x79 from `mfxstm32l152_ReadID`; the
  emulator returns fuzz bytes (`0xffffffff` in the trace), the comparison fails,
  the driver initialization is skipped, and `BSP_IO_ConfigPin` dereferences the
  NULL `IoDrv` table → crash (649 GDMA crashes at 0x08006B1C).
- Classification: peripheral-response artifact (FP) — genuine driver code
  waiting for protocol-correct flags that the unmodeled peripheral never sets.

---

## P9 — Incomplete Peripheral Modeling

**Definition.** A peripheral model is missing or its registers are mapped
read/write without protocol semantics, so *pointer-valued* peripheral state
(buffer tables, descriptor pointers) becomes fuzz bytes. The firmware then uses
fuzz data as a pointer/function pointer and jumps out of code space.

**Representative case: firmware #3503** (bcf-gateway-core-module v1.5.0,
STM32L0 USB gateway, 133,528 bytes).
- The firmware is an STM32 USB device (HARDWARIO USB Dongle gateway, string
  evidence `bcf-usb-gateway`). The USB peripheral (0x40005C00) stores buffer
  descriptors in its **BTABLE at 0x40005C50** — a textbook "peripheral region
  holding pointers" layout.
- Crash: `0x800DCA4 blx r3` jumps to **0x40005800 (the USB peripheral region)**
  → ExecViolation; Hoedur reports 336 crashes for the same firmware.
- Trace evidence (MultiFuzz): USB registers are read as fuzz bytes, then a
  table lookup yields a function pointer pointing into the peripheral region:
  ```
  pc          sp          last_read   last_value
  0x800ad60  0x20004ec0  0x40005c40  0x12ef    ; USB register read (fuzz value)
  0x800bcdc  0x20004ec0  0x40005c44  0x4f      ; USB register read (fuzz value)
  0x800dca4  0x20004eb0  0x48270100  0xffffff80 ; blx r3 → jump 0x40005800 → EV
  ```
- Disassembly (base 0x8000000):
  ```
  0x800DC9C: ldr r3,[r0,r3]      ; r3 = [r0+0x214] (event/descriptor struct field)
  0x800DC9E: ldr r3,[r3,#0x1c]   ; r3 = [field+0x1C] (function pointer)
  0x800DCA4: blx r3              ; jump to 0x40005800 (USB peripheral) → EV
  ```
- Control flow closes: USB register reads (0x40005C40/0x40005C44) → status
  check → table lookup → function pointer = peripheral-region address
  (fuzz data / uninitialized pointer) → `blx` into the peripheral region.
- Paper case #820 (Listing 4, verbatim) is the textbook instance: address 0x40005C50 is
  `USB_BTABLE`, whose bits 15–3 specify where the buffer table lives, but Fuzzware treats
  the register as a generic input and supplies unconstrained fuzz data.  In the crashing
  execution it returns 0xF3F3F3F3, which the firmware interprets as address metadata and
  uses for an out-of-bounds read at 0x27E84822.
  ```
  1 ldr          r3,[DAT_08028d9c] ;= 40005C00h
  2 ldr          r2,[r3,#0x50]     ;=>DAT_40005c50
  3 addw         r3,r3,#0x43c
  4 lsls         r2,r2,#0x1
  5 ldr          r3,[r3,r2]
  ```
- Paper case #18803 (nRF52832) is the DMA flavour of the same gap: GDMA models the DMA
  transfers incorrectly and treats a pointer as DMA data, so supplying fuzzing input to the
  pointer eventually makes the firmware fetch an invalid instruction (the controlled
  experiment on this sample is in the DMA section below).
- **Corroborating case #3499** (same gateway family, v1.11.0, 144,576 bytes):
  identical mechanism — `0x800F8E8 blx r3` jumps to 0x10000000
  (a fuzz-mapped region) with GDMA 404 crashes and Hoedur 321; the USB
  descriptor bytes (0x10, a common descriptor length/type byte) are treated as
  a function pointer via `[r0+0x1F4] → [field+8]`. Two firmware versions of the
  same family crash in the same way — the mechanism is robust.
- **Corroborating case #1475** (G3_light): the purest form — a peripheral read
  value is used directly as a jump target: trace shows
  `0x446a read 0x5000C008 = 0x10000000` then `blx r0` jumps there (candidate
  case; the run is below the 1-hour sufficiency threshold).
- P8 vs P9 boundary: P8 = peripheral value is *compared* and the firmware takes
  a wrong branch; P9 = peripheral value / peripheral address *directly enters a
  pointer jump*.
- Classification: peripheral-model gap (FP) — "the peripheral stores pointers,
  it cannot be filled with arbitrary fuzz bytes."

---

## P10 — Shallow Root-Cause Diagnosis

**Definition.** Automated root-cause tracing (FirmRCA) performs data-flow
tracking only. It can trace where a crash value came from, but it cannot know
whether that data flow *should exist at all*; its verdicts therefore diverge
from the true root cause when the driving condition (interrupt timing,
peripheral values) is fabricated by the fuzzer.

**Representative case: firmware #3349.**
- Crash: `0x8006100` reads address `0xc` (NULL dereference) in a ring-buffer
  copy function; FirmRCA reports candidate `0x8009930 str r1,[r0,#0xc]` (the
  NULL write point) with score 199 and a complete data-flow chain
  (0x8004484 → 0x8009648 → 0x80060f2 → crash).
- True root cause: IRQ43 (USART1) is injected by polled interrupts; the handler
  reads CR1/ISR and gets fuzz bytes (firmware writes 0x0e, reads back
  0xffff0100; two reads of the same register differ), misjudges TXE=1, and uses
  the never-initialized channel-2 TX buffer pointer (NULL).
- Real-hardware reasoning: with CR1 = 0x0e, TXEIE = 0, the USART1 interrupt is
  unreachable, the handler never runs, and the crash path does not exist.
- Crash record (replay of `main004/fuzzers/fuzzer1/crashes/id:000892,sig:11,src:000524+002251,op:splice,rep:128`,
  `basic_block_cov = 0.124214`, base 0x8000000):
  ```
  pc  0x8006100    lr  0x800964f
  [ 0x08006100 ] INVALID READ: addr = 0x000000000000000c size = 4 data = 0
  Execution failed with error code: 6 -> Invalid memory read (UC_ERR_READ_UNMAPPED)
  ```
- FirmRCA candidates for that crash (backward taint, `reversenolog`, 158.6 s):
  ```
  score 199   0x8009930  str r1, [r0, #0xc]     ; the NULL write point
  score 100   0x800368a  str r5, [r2, #0xc]
  score  99   0x80060f2  movs r3, r0            ; the crashing function
  ```
  The data-flow chain is complete and correct: 0x8004484 (channel 2) → 0x8009648 (field
  read) → 0x80060f2 (argument) → crash.  What the tracer cannot see is that the chain is
  driven by an interrupt the fuzzer invented.
- Peripheral evidence for the driving condition: IRQ43 (USART1) is injected by the polled
  interrupt timer; the handler reads CR1/ISR and gets fuzz bytes — the firmware writes 0x0e
  to CR1 and reads back 0xffff0100, and two reads of the same register differ — so the
  handler believes TXE = 1 and uses the never-initialised channel-2 TX buffer pointer.
- The tool's own verdict and the audited verdict disagree, which is the point of this
  category: the automated classification stored for this case is `REAL_BUG` ("null/low
  address dereference, the FirmRCA candidate hits the crash PC ±16 B, the crash value's
  origin is clear — firmware defect"), while the reachability audit above turns it into an
  environment false positive.  The data flow was right; the conclusion drawn from it was not.
- Lesson: the data flow is *correctly* traced (score 199 matches perfectly),
  yet the root cause is an interrupt-timing/peripheral-value artifact outside
  the tracer's scope. FirmRCA's candidate is a legitimate initialization
  placeholder, not a bug-introduction point.
- Classification: tooling diagnosis limitation — data-flow hits are leads, not
  evidence; reachability (interrupt timing, peripheral write-read consistency)
  must be overlaid to reach the true root cause.

---


**Second instance: firmware #3517.** Same shape as #3349 — a candidate that matches the
crashing PC is promoted to "cause" although the crash itself is not reproducible on hardware:
crash PC `0x8002420`, introduced inside the handler of interrupt 43, with the top-ranked
candidate `0x80045a4` structurally identical to the one selected for #3349. The reachability
audit likewise overrides the machine verdict to FP (emulation distortion inside the interrupt
handler). The same audit groups the remaining LoRa-family samples with #3349/#3517, so the
"uninitialized pointer" verdict is a family-wide artefact of a modelling gap rather than
N individual firmware defects.

## P11 — Poor Scalability

The cost is dominated by Stage 1 Reconnaissance (near-brute-force base-address inference),
Stage 3 Security Testing (per-crash replay and tracing over three emulators) and Stage 4
Diagnosis (taint-based root-cause analysis).  The paper's measured evidence:

- **FirmLine memory explosions.** In several cases FirmLine spawned excessive
  sub-processes and consumed over 200 GB of memory.  The pipeline sets a
  memory-consumption threshold of 200 GB (190.73 GiB) per process and monitors the
  usage at runtime, killing a process tree once it exceeds it.  The representative
  cases (paper Table XIV):

  | sample | file size | runtime | peak memory |
  |---|---|---|---|
  | #125 | 37.73 KiB | 15 min 40 s | over 193 GiB |
  | #147 | 5.13 MiB | 15 min 18 s | over 193 GiB |
  | #761 | 5.12 MiB | 15 min 45 s | over 193 GiB |
  | #148 | 5.12 MiB | 15 min 51 s | over 193 GiB |
  | #1732 | 29.52 KiB | 39 min 24 s | 160 GiB |
  | #1037 | 270.64 KiB | 28 min 53 s | 143 GiB |
  | #531 | 11.05 KiB | 34 min 04 s | 142 GiB |
  | #3281 | 164.60 KiB | 28 min 27 s | 72 GiB |

  Note the sizes: a 11 KiB image can cost 34 minutes and 142 GiB — the cost is not a
  function of the firmware size.
- **Stage 2 Emulation — modelling.** For most samples the decoupled emulator–fuzzer tools did not
  finish the peripheral-modelling phase within one hour.
- **Stage 4 Diagnosis — taint explosion.** On long crash traces both FirmRCA's analysis time and its
  memory consumption grow almost exponentially with trace length.
- Consequently the corpus-level statistics in this study use bounded per-image budgets
  (fixed fuzzing durations, capped crash-replay counts); a complete per-crash analysis of
  all three emulator crash sets (211,775 / 67,893 / 15,220 crashes) is not feasible
  interactively, which is why the representative-case approach is used here.

---

## P12 — Implementation Limitations (Emulator ISA Gaps)

**Definition.** Emulator implementation defects beyond ISA *support* (P4):
semantic operations are silently ignored. The MultiFuzz emulator
(icicle-cortexm, a self-written SLEIGH translator) does **not implement
ARM/Thumb ISA mode switching**: the `setISAMode` operation injector is a no-op.

**Representative case: firmware #2985** (Zepp BLE; also #2595).
- Source evidence (`icicle-cortexm/src/arm.rs`):
  ```rust
  vm.add_op_injector("setISAMode", move |_: &Arch, _, _, _output, _b| {
      // Just a nop.
      false
  });
  ```
- When the firmware executes `blx` to an *even* address (target bit0 = 0,
  which on real hardware switches to ARM mode), icicle does not switch the
  decode mode and — as shown by the crash trace — **SP suddenly becomes 0x20**:
  ```
  0x1d5f8,0x2000f940,6027306,...   <- main loop before blx, SP normal
  0x1d0a8,0x20,6027310,...         <- after blx table entry, SP = 0x20!
  0x1d06a,0x20,6027313,...         <- subsequent Thumb code reads low memory
  ```
- The crash chain: event-dispatch table lookup (0x200023D0) → table entry is a
  code address without the Thumb bit (even) → `blx` → SP = 0x20 → subsequent
  Thumb instructions execute against low addresses → `READ_PROT` crash
  (97.4% of the 108 affected firmware / 2,431 traced crashes).
- The paper records the same mechanism from the other side: Hoedur's "Reset" crash type
  (15,203 crashes over 103 samples) is triggered by firmware setting the VECTRESET field of
  the AIRCR register, and QEMU, which implements ARM/Thumb switching, does not exhibit the
  tool-side distortion described here.
- Control: gdma (Unicorn/QEMU decoder) and Hoedur (QEMU) implement
  ARM/Thumb switching correctly; the same firmware crashes there as
  PopStack / fetch-into-garbage, without the SP = 0x20 distortion.
- Classification: emulator implementation limitation — the underlying firmware
  table entries (even addresses) are a genuine defect, but MultiFuzz distorts
  the crash mode into "SP = 0x20 + READ_PROT", which would mislead analysis
  without cross-emulator comparison.

---


**Source-level evidence (why it is an implementation gap and not an ISA gap).** MultiFuzz's
emulator `icicle-cortexm` registers the ARM/Thumb mode-switch injection as a no-op
(`icicle-cortexm/src/arm.rs` L134-140, verbatim):
```rust
vm.add_op_injector(
    "setISAMode",
    move |_: &Arch, _, _, _output: pcode::VarNode, _b: &mut BlockState| {
        // Just a nop.
        false
    },
);
```
The ARM SLEIGH specification performs `setISAMode` for `blx`/`bx` to an **even** address, so
the mode switch is silently dropped. The control group is decisive: GDMA runs on
fuzzware/Unicorn (`UC_ARCH_ARM, UC_MODE_MCLASS, UC_MODE_THUMB`) and Hoedur on QEMU — both full
QEMU decoders that implement the switch — and neither shows this crash pattern.

**Trace-level evidence.** MultiFuzz's `PathTracer` records `pc, sp, icount, fuzz_offset,
last_read, last_value` at every basic-block entry, so the SP mutation is directly visible
(all samples below have `active_irq = 0`, i.e. no interrupt is involved):
```
#2985  0x1d5f8, 0x2000f940, 6027306, ...   ; main loop, before blx — SP normal
       0x1d0a8, 0x20,       6027310, ...   ; after the blx to the table entry — SP = 0x20
       0x1d06a, 0x20,       6027313, ...   ; add sp,#0x10; pop reads a low address
#2985  0x2000f928 -> 0x20 at pc = 0x1d0fa   ; same pattern, second trace
#2595  0x2f872, 0x2000fac0, 982335,  ...   ; last step of the event dispatch
       0x301f2, 0x20,       982340,  ...   ; after blx to the table entry — SP = 0x20
       0x2fa6a, 0x20,       982343,  ...   ; pointer function read at [r0+r1]
```
After the `blx` to an even address the trace still shows Thumb instructions, i.e. the decoder
did not switch, and SP = 0x20 is deterministic across three independent traces of two
firmwares. (In #2595 a second trace with `active_irq = 33` shows the same SP = 0x20 segment
inside an interrupt handler, after which SP is restored to 0x2000FDC0 on exception return —
the mutation is independent of interrupt handling.)

## Summary

| P   | Category                                   | Representative case(s) |
|-----|--------------------------------------------|------------------------|
| P1  | Insufficient Information Sources           | #804 (Ghidra NPE, base NULL) |
| P2  | Improper Candidate Weighting and Selection | #2915 (48/57 IVT entries identical), #2547, #677, #2797 |
| P3  | Invalid IVT Placement Assumption           | #1819 (vendor header), #11175 |
| P4  | Unsupported ISA Instructions               | #3741 (Crazyflie VFP vldr/vstr) |
| P5  | Inaccurate Memory Layout                   | #227, #12123, #3519, #2715 |
| P6  | Absolute Speed Gap                         | #7658 (delay_millis/count_us), #3034 |
| P7  | Incorrect Interrupt Timing                 | #2938 (ISR 21 → 0x20003C00), #3755 |
| P8  | Inaccurate Peripheral Responses            | #2691 (USART frameLen), #1179 (SPI2 TXE/RXNE spin), #15998, #11381 |
| P9  | Incomplete Peripheral Modeling             | #820 (USB_BTABLE 0x40005C50), #3503, #3499, #1475, #18803 |
| P10 | Shallow Root-Cause Context                 | #3349 (FirmRCA data-flow mismatch) |
| P11 | Poor Scalability                           | — (see main text) |
| P12 | Implementation Limitations                 | #2985 (icicle no ARM/Thumb switch, SP=0x20) |

## Crash counts per category (mutually exclusive, one category per crash)

The counts come from the per-crash replay databases of the three emulators, with one rule
set applied per crash: instruction-invalid → P4; ARM/Thumb-mode distortion → P12; crash
inside an interrupt handler on a pointer below 0x10000 → P7; access inside a
peripheral/private-peripheral/RAM window → P8/9; access in an external-memory window → P5;
everything else → the unconfirmed row.  Fuzzware totals 211,775 reprocessed crashes (27
DMA-carrying images taken from the vanilla Fuzzware replay table, the rest from the GDMA
table), MultiFuzz 67,893 (images with `actual_fuzz_time >= 3600`, read-watch and halt
excluded) and Hoedur 15,220 (native crash types; QEMU implements ARM/Thumb switching, so
P12 cannot occur, and Hoedur exposes neither an interrupt-context field nor a way to
separate instruction-invalid crashes).

| P | Crash types | Fuzzware | MultiFuzz | Hoedur |
|---|---|---|---|---|
| P4 | UI | 12,315 (5.8%) | 1,549 (2.3%) | — |
| P5 | UR, UW, UF, EV, WV, others, NonExec, RomWrite | 148,448 (70.1%) | 38,192 (56.3%) | 5,771 (37.9%) |
| P7 | UR, UW, UF, WV, others | 6,127 (2.9%) | 2,710 (4.0%) | — |
| P8/9 | UR, UW, UF, EV, WV, RV | 12,201 (5.8%) | 18,561 (27.3%) | 921 (6.1%) |
| P12 | RV | 0 | 41 (0.1%) | — |
| Unconfirmed | UR, UW, UF, EV, WV, RV, others, NonExec, RomWrite | 32,684 (15.4%) | 6,840 (10.1%) | 8,528 (56.0%) |

Two caveats carried from the classification notes: MultiFuzz's `InvalidInstruction` code
also covers a fetch-protection mapping, and Hoedur's unconfirmed share is large because many
HardFaults report a placeholder PC (0xFFFFFFFE / 0x0) or crash inside code, where no address
window applies.

---

# Consolidated case-level audit

## Randomly reproduced crash set (100 cases)

Method: the reproduced crashes were re-audited with the interrupt-injection-timing /
MMIO write-read-consistency / crash-path-reachability methodology, i.e. every verdict
asks whether the same crash could happen on the real device.

| Verdict | Cases | Meaning |
|---|---|---|
| `FP` (emulation distortion) | 94 | crash path is not reachable on hardware: 61 in handlers entered by fuzzware's round-robin interrupt injection (`every_nth_tick=1000`), 10 with peripheral read-back that does not match what the firmware wrote (unmodelled MMIO returns fuzz bytes) |
| `FP_NVIC` | 2 | fuzzware's NVIC interrupt-exit stack frame handling fails |
| `SIM_DATASET` | 2 | the image is a partial flash image, referenced data tables are missing |
| `FP_INSN` | 2 | invalid instruction, PC polluted by fuzz data |
| `FW_BUG` | **0** | none of the crashes is a genuine firmware defect |

The four groups originally counted as firmware defects were all withdrawn after the
reachability audit, each with its chain re-derived:

| firmware | cases | original verdict | chain that withdraws it |
|---|---|---|---|
| #27 | 15 | conversion function returns NULL unchecked | the mode value 0x20002463 = 0x48 comes from `FUN_0801166e` reading **unmodelled MMIO 0x40006c68** (table base 0x80116c4 = 0x40006c2c, not a flash table); with a peripheral that reads back legally, `FUN_08016540` returns normally and no crash occurs |
| #1376 | 6 | unconditional `movs r0,#0` → `ldrb [0x0]` | the trigger chain is a periodic task, not an interrupt: task id 3 → task table[3] = `FUN_0801a61c` → `FUN_08019a60` → `FUN_080199f0(0)`; the crash point is a read of address 0, which **fuzzware leaves unmapped**, while on the real STM32F103 address 0 is a readable flash alias under every BOOT configuration |
| #668 | 1 | data written over the stack frame | the real firmware resets its output index every frame (0xf7 frame marker at 0x3e4c); the fuzz data has no frame marker, so the index runs away and the buffer overlaps the stack frame (SP = 0x20000864) |
| #5170 | 1 | task switch dereferences NULL | the trigger `0x100006a6 == 1` is a **fuzz byte** consumed by fuzzware's phr1 model; the real STM32F4 CCM RAM reads back 0 after a write, so the condition never becomes true |

The remainder of the 100 cases: 61 execute inside a handler entered by the polled interrupt
(`every_nth_tick = 1000`, round-robin) where the real interrupt is unreachable or arrives at
a different moment (e.g. #3517: CR1 = 0x0e → TXEIE = 0 → the USART interrupt can never
fire), and 10 crash in the main loop with a peripheral read-back that disagrees with what the
firmware wrote (unmodelled MMIO returning fuzz bytes: 227, 248, 1546, 3643, 3549, 3272, ...).

## Case classification of the 33-case FirmRCA sample

`FUZZER_INPUT` dominates: the fuzzer's polling interrupt fires a handler that reads
state the firmware has not initialised yet, which cannot happen on hardware where the
interrupt arrives only when the peripheral is ready.  The remaining classes are
`FP_MMIO` (unmodelled MMIO read-back feeds the crash address), `FP_ENV` (crash point is
a read of unmapped address 0), `FP_FRAME` (input buffer overlaps the stack frame),
`SIM_CFG`/`SIM_CFG_DATASET` (peripheral range or data table missing from the memory
map) and `SIM_NVIC` (interrupt-exit stack frame).

Two structural findings follow from the audit and are used in the paper's evaluation:

- FirmRCA's `reversenolog` candidates locate the **data-flow origin of the crashing
  value**, not the root cause: the candidate list treats emulator state as ground truth
  and does not judge reachability (documented on the limitation case, dataset id 3349).
- Root-cause judgement therefore requires the reachability analysis above (interrupt
  injection timing + MMIO model consistency + register semantics + hardware reasoning).

## DMA-induced false positives (GDMA experiments)

On firmware #18803 (nRF52832, UARTE0 EasyDMA; the DMA configuration resolves descriptor
`0x40002534` = UARTE0 base 0x40002000 + 0x534 = `UARTE0.RXD.PTR`, pointing at RAM address
0x20002bcd with known sizes [1, 0x93] — this matches the firmware's real DMA setup) a
controlled experiment over 6,816 crash inputs compared the DMA-aware model with the model
without DMA: **exactly two inputs crash only with DMA enabled**, while 132 further
candidates that initially looked DMA-caused turned out to be concurrency artifacts of the
harness's config race.

| input | size | signal | crash |
|---|---|---|---|
| `id:003301` | 89 B | sig:11 FETCH_PROT | pc 0x20006302, lr 0x2d8eb, r5 0x20002bcc |
| `id:004040` | 155 B | sig:04 INSN_INVALID | byte-level causality: the injected byte becomes the branch target |

Injection trace of the first input — UARTE0 fills the receive buffer 0x20002bcd–0x20002bd4
straight from the fuzz file (offsets 30 and 41):
```
0000: 2f85a 2c465 rx 30 1 0x20002bcd: ff    <- fuzz input offset 30 onward
0001: 2f87e 2f133 rx 30 1 0x20002bd0: 93
0002: 2f87e 2f133 rx 30 1 0x20002bd1: f1
0003: 2f87e 2f133 rx 30 1 0x20002bd2: ff
0004: 2f87e 2f133 rx 30 1 0x20002bd3: ff
0005: 2f81c 2f857 rx 41 1 0x20002bd4: fc
```
The paper's appendix tabulates the per-sample crash and coverage figures for exactly these
27 DMA-carrying images: #152, #1435, #1436, #1437, #1529, #1534, #1535, #1842, #1866,
#2454, #2928, #2929, #3432, #3584, #3585, #3610, #3612, #6922, #7591, #7675, #9138, #9668,
#11767, #13261, #17949, #18324, #18803.

The firmware-side chain was re-derived statically: the event object 0x20003610–0x2000362c is
zeroed at 0x37e50 (callback field 0x20003618 included), the UARTE receive area
0x20002b50–0x20002bcc is zeroed at 0x37dc8/0x37ddc, the dispatcher callback 0x2c43d is
registered at 0x2d5d4, the event handler 0x2f835 at 0x2c49e, and the interrupt
(every_nth_tick = 1000, round-robin) enters 0x2d8a4 with r4 = 0x20003614 and r5 = the UARTE
base — so the crash is caused by the DMA-delivered bytes, not by the firmware's logic.

## Dataset identification corner case

The identification pass does not recover vendor information for every image — the paper
records such samples as "unidentified" (its example is **#122**).  The identification
material therefore ships the raw MD5 list (`binaries_md5.csv`) rather than a
vendor-complete table, and the per-vendor counts in the paper's Table XII
(Nordic 878, TI 180, STM32 53, ESP 37, Dialog 33, ...) are the ones recovered by the
OTACap artifact signatures and the keyword signatures combined.

## Firmline architecture comparison

Firmline's `cpu_rec`-based architecture identification is unreliable: standard ARM
vector-table firmware is labelled `6502` (the 6502 decoder accepts almost any byte
sequence, and the scoring prefers it).  Architecture counts taken from `cpu_rec` on
this corpus must therefore be treated as lower bounds on the ARM share, which is why
the evaluated subset is selected by the database's `arch`/`format` columns rather than
by `cpu_rec`.
