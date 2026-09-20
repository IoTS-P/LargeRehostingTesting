# FunctionFinder

## Category

- IoT Firmware

## Description

Attempts to find functions that Ghidra missed and supplement their definitions.

## Data and Module Interactions

### Module Dependencies

- 🔴 (optional) `org.iotsplab.akiba.process.ARMBaseFinder`: Used for ARM Cortex firmware to skip the interrupt vector table at the beginning of the file.

## Supplementary Explanation

This module includes several methods for defining functions:

1. Brute-force search for all direct function call instruction machine codes (because Ghidra uses a recursive disassembly algorithm rather than a linear disassembly algorithm, only by searching machine codes can we find missed function call instructions), then let Ghidra automatically analyze.
2. There may be some undefined bytes between functions; in actual situations, they are most likely functions. Therefore, we can try to disassemble the undefined bytes between functions to supplement function definitions.
3. In ARM Cortex series firmware, some unimplemented interrupt functions may be filled with infinite loops. Sometimes these unimplemented functions occupy most of the functions in the interrupt vector table. We can find and define them by searching for `\xFE\xE7`, which can improve the accuracy of the `ARMBaseFinder` module.
4. Undefine all hanging assembly code segments that do not belong to any function; most of them should actually be data.
