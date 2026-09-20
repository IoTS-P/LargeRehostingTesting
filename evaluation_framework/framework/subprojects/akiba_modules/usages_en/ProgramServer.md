# ProgramServer

## Category

- IoT Firmware

## Description

Optimizes the performance of two function APIs when functions are not modified.

## Data and Module Interactions

### Provided API

- 🟠 `fun getFunctionContaining(address: Long): Function?`: Gets a function containing a given address, faster than Ghidra API.
- 🟠 `fun getFunctionStart(function: Function): Long`: Gets the starting address of a given function, faster than Ghidra API.

## Supplementary Explanation

By building a hashmap of function addresses, we can more quickly obtain a function containing a given address, as well as obtain the starting address of a given function, because Ghidra needs to dynamically search for functions.
