# ProgramInitialization

## Category

- IoT Firmware

## Description

Used to initialize an IoT analysis environment (referred to as a program by Ghidra).

## Supplementary Explanation

Tasks that need to be completed:

1. Pre-environment check: Will not analyze files larger than 10MiB.
2. Complete the Ghidra automatic analysis process. (Aggressive instruction finder is enabled by default; selecting this option will make Ghidra use more aggressive strategies to search for assembly instructions)
3. If automatic analysis fails to find no less than 10 functions, the binary file will be recorded as an invalid file, i.e., `Not a valid firmware`, and recorded in the database, and the task will be marked as failed.
