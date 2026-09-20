# FuzzwarePipeline

## Category

- IoT Firmware
- Fuzzing
- Firmware Analysis

## Description

Execute Fuzzware fuzzing pipeline, automatically run fuzzing tests and collect coverage statistics.

## Data and Module Interactions

### Module Dependencies

- 🔴 `org.iotsplab.akiba.process.FuzzwareGateway`: Provides Fuzzware environment activation, configuration generation, and coverage information extraction functionality

### Configuration File

Configuration file class: `org.iotsplab.akiba.process.FuzzwarePipelineConfig`

```json
{
  "maxTimeout": "00:00:10:00",        // Maximum timeout (format: DD:HH:mm:ss, default 10 minutes)
  "otherArguments": "",               // Other arguments to pass to Fuzzware
  "withGDMA": false                   // Whether to enable GDMA (General DMA) support
}
```

### Database Columns

- 🟢 `set_fuzz_time INTEGER`: Preset fuzzing time (seconds)
- 🟢 `actual_fuzz_time INTEGER`: Actual fuzzing time (seconds)
- 🟢 `basic_blocks_visited INTEGER`: Number of basic blocks visited
- 🟢 `basic_blocks_newfound INTEGER`: Number of newfound basic blocks
- 🟢 `basic_blocks_total INTEGER`: Total number of basic blocks
- 🟢 `coverage REAL`: Coverage rate (decimal between 0.0-1.0)

## Supplementary Explanation

Main functions and working principles of this module:

1. **Core Functions**:
   - Call FuzzwareGateway to activate Fuzzware environment
   - Generate corresponding configuration files based on file type (ELF or raw firmware)
   - Start Fuzzware Pipeline for fuzzing
   - Monitor fuzzing progress and coverage in real-time
   - Automatically terminate and clean up processes after timeout

2. **Workflow**:
   - Check and activate Fuzzware virtual environment
   - Call FuzzwareGateway to generate configuration files (distinguishing between ELF and raw firmware)
   - Start `fuzzware pipeline` command with timeout setting
   - Read output logs in real-time, record the first reported basic block count
   - Forcefully terminate all related processes (including Redis subprocesses) after timeout
   - Call FuzzwareGateway to extract detailed coverage information
   - Update statistics in database

3. **Coverage Statistics**:
   - Extract translation block coverage information from Fuzzware output
   - Record the first reported basic block visit count (firstCov)
   - Finally count the total visited basic block count (totalCov)
   - Calculate coverage = visited basic blocks / total basic blocks

4. **Dynamic Disassembly**:
   - Automatically disassemble unrecognized functions (UNKN) in coverage information
   - Use DisasmHelper to create new functions at unknown addresses
   - Merge all basic block information and deduplicate to get total count

5. **Process Management**:
   - Support automatic termination on timeout (detected via "Shutdown requested!")
   - Clean up orphaned Fuzzware and Redis processes
   - Record actual running time

6. **Time Format**:
   - Supports DD:HH:mm:ss format for timeout configuration
   - Automatically converted to Duration object for precise control

## Notes

1. **Prerequisite Dependency**: Must run FuzzwareGateway module first to generate necessary configurations and environment

2. **System Requirements**:
   - Only supported on Linux systems
   - Requires Fuzzware and its dependencies (including Redis) to be installed
   - Requires Python virtualenvwrapper

3. **Timeout Setting**: Uses `@IgnoreRuntimeTimeout` annotation, but the module has its own internal timeout mechanism

4. **File Format Support**:
   - Supports ELF format files (directly calls `generateFuzzwareConfigForELF`)
   - Supports raw firmware format (requires base address and IVT information from ARMBaseFinder)

5. **GDMA Support**: GDMA (General DMA) support can be enabled via the `withGDMA` parameter for more complex hardware emulation

6. **Performance Considerations**:
   - Changes in basic block count are a potential factor in pipeline performance variation
   - Long-running processes will consume significant system resources
   - It is recommended to adjust timeout settings according to actual needs

7. **Log Monitoring**: The module outputs Fuzzware runtime logs to Akiba's logging system in real-time (TRACE level)
