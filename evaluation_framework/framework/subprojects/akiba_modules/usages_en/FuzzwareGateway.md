# FuzzwareGateway

## Category

- IoT Firmware
- Fuzzing
- Firmware Analysis

## Description

Generate configuration files and provide auxiliary functions for Fuzzware fuzzing workflow; this is a prerequisite module for running Fuzzware.

## Data and Module Interactions

### Provided API

- 🟠 `suspend fun activateEnv(): Boolean`: Activate Fuzzware environment, check if necessary commands and virtual environment exist.
- 🟠 `suspend fun generateFuzzwareConfig(baseAddress: Long, ivtStart: Long, forHoedur: Boolean, taskMonitor: TaskMonitor): Path`: Generate Fuzzware configuration file for raw firmware.
- 🟠 `suspend fun generateFuzzwareConfigForELF(forHoedur: Boolean): Path`: Generate Fuzzware configuration file for ELF files.
- 🟠 `fun getCovInfo(proj: Path): CoverageInfo`: Get Fuzzware coverage information.

### Temporary Data

- 🅘🅞 `FuzzwareGatewayConfig fuzzware_basic_conf`: Fuzzware basic configuration information, including project path, virtual environment, etc.

### Module Dependencies

- 🔴 `org.iotsplab.akiba.process.ARMBaseFinder`: Data structures of IVT.

### Configuration File

Configuration file class: `org.iotsplab.akiba.process.FuzzwareGatewayConfig`

```json
{
  "cmdPrefix": ["/bin/sh", "-c"],            // Command execution prefix
  "cmdPredo": "",                            // Pre-command (such as source script)
  "regenerateConfig": true,                  // Whether to regenerate configuration file
  "projectRoot": "fuzzware-projects",        // Fuzzware project root directory name
  "venv": "fuzzware",                        // Python virtual environment name
  "doRebase": true                           // Whether to perform rebase operation on memory blocks
}
```

### Database Columns

This module does not create a database table (@DoNotCreateTable).

## Supplementary Explanation

Main functions and working principles of this module:

1. **Core Functions**:
   - Generate YAML configuration files for Fuzzware fuzzing engine
   - Provide environment activation and checking functionality
   - Support configuration generation from both raw firmware files and ELF files
   - Extract coverage information

2. **Generated Configuration Content**:
   - Memory map: including text segment, RAM, MMIO, NVIC, and other regions
   - Symbol table: all function addresses and names
   - IVT (Interrupt Vector Table) offset
   - Interrupt trigger configuration

3. **Two Configuration Generation Modes**:
   - **Raw Firmware Mode** (`generateFuzzwareConfig`): For raw binary firmware files, requires base address and IVT start address
   - **ELF File Mode** (`generateFuzzwareConfigForELF`): For standard ELF files, automatically parses section information

4. **Environment Checking Functionality** (`activateEnv`):
   - Check if `workon` command is available (virtualenvwrapper)
   - Check if the specified Python virtual environment exists
   - Check if `fuzzware` command is installed
   - Check if `arm-none-eabi-objcopy` toolchain is available

5. **Coverage Information Extraction** (`getCovInfo`):
   - Call Fuzzware's coverage analysis command
   - Parse found symbols and basic blocks information
   - Return structured coverage data

6. **Memory Relocation**:
   - Support rebasing memory blocks in Ghidra
   - Ensure relocation can only be performed once to avoid address errors

7. **ELF File Processing**:
   - Automatically parse ELF section headers and merge adjacent sections
   - Distinguish between loadable sections and uninitialized sections (like .bss)
   - Generate optimized binary files and corresponding YAML configurations

## Notes

1. **System Requirements**:
   - Fully supported only on Linux systems
   - Requires Fuzzware and its dependencies to be installed
   - Requires ARM GCC toolchain (arm-none-eabi-objcopy)
   - Requires Python virtualenvwrapper

2. **NTFS File System Warning**: If binary files are stored on NTFS partitions, running Fuzzware is not recommended because NTFS doesn't support colons (:) in filenames, which are used by Fuzzware input files, causing initialization failures

3. **Timeout Setting**: Uses `@IgnoreRuntimeTimeout` annotation, no runtime timeout limit

4. **Configuration Reuse**: By default, configuration files will be regenerated; this can be controlled via the `regenerateConfig` parameter

5. **Relocation Safety**: Multiple relocations may cause incorrect basic block addresses; the module will detect and prevent this behavior

6. **Application Scenarios**: Mainly used for preparation work for fuzzing ARM Cortex-M series microcontroller firmware

7. **Fuzzware Diff**: The original Fuzzware repository code needs minor adjustments:
> In `/emulator/harness/fuzzware_harness/native/native_hooks.c` at line 1107, change `_exit` to `exit`, otherwise redirecting output will not capture the last critical error message. After making this change, run `make` in the same directory.
