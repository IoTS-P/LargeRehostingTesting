# AddressSpaceAnalyzer

## Category

- IoT Firmware

## Description

Given a base address, entry point, master stack pointer, and memory segments, this module uses Akiba's wrapper around Ghidra's built-in emulator `StepInEmulator` for emulation. The module will analyze all direct memory-to-memory data copy flows and aggregate results to attempt to locate the data segment position in the firmware file.

For ARM Cortex architecture firmware, this module can also track interrupt vector table copy operations.

## Data and Module Interactions

### Temporary Data

- 🅞🅘 `BasicLoadMetadata load_metadata`: Memory segment information used to load the firmware file.
- 🅘 (optional) `ArmcmIVT ivt`: Used for ARM Cortex firmware to track possible interrupt vector table copy behavior.

### Module Dependencies

- 🔴 `org.iotsplab.akiba.process.IoTGeneralStructures`: Data structure definitions for IoT firmware.
- 🔴 `org.iotsplab.akiba.process.ARMBaseFinder`: Used for ARM Cortex firmware to define interrupt vector table data structures.

### Database Columns

- 🟢 `data_file_offset INTEGER`: The offset of the found `.data` segment in the firmware.
- 🟢 `data_size INTEGER`: The length of the found `.data` segment.
- 🟢 `data_map_start INTEGER`: The starting address of the `.data` segment mapped in memory at runtime.

## Supplementary Explanation

For a small firmware file that can be entirely burned into a Flash memory, its `.data` segment is generally located immediately after the code segment, and needs to be copied to the correct RAM memory location during firmware power-on initialization. Additionally, the `.bss` segment needs to be initialized during firmware power-on, which requires covering the `.bss` segment in RAM with `\x00` values. During the power-on initialization process, the covering behavior generally proceeds sequentially from low addresses to high addresses. This module monitors all memory copy behaviors and `\x00` covering behaviors to attempt to locate the possible positions of the firmware's `.data` and `.bss` segments.

## Notes

This module records `\x00` memory covering behavior, but does not record this behavior in the database. Because during actual testing, it was found that there are often multiple such covering behaviors, making it impossible to determine which one is the `.bss` segment.
