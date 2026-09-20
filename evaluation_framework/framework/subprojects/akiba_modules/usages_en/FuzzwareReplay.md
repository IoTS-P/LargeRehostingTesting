# FuzzwareReplay

## Category

- IoT Firmware
- Fuzzing
- Firmware Analysis
- Crash Analysis

## Description

Replay crash inputs discovered during Fuzzware fuzzing, analyze crash details, and calculate code coverage.

## Data and Module Interactions

### Temporary Data

- 🅘 `FuzzwareGatewayConfig fuzzware_basic_conf`: Fuzzware basic configuration information, including project path, virtual environment, etc.

### Module Dependencies

- 🔴 `org.iotsplab.akiba.process.FuzzwareGateway`: Provides Fuzzware environment activation and configuration generation functionality.

### Database Columns

- 🟢 `crash_replay_crashes TEXT`: JSON-formatted array of crash replay results, each element containing:
  - `path`: Crash input file path (relative to project root)
  - `errMsg`: List of error messages
  - `errInInterrupt`: Whether the error occurred in an interrupt handler
  - `errRegContext`: Register context at the time of error (PC, LR, etc.)
  - `functionCoverage`: Function coverage rate (0.0-1.0)
  - `basicBlockCoverage`: Basic block coverage rate (0.0-1.0)

- 🟣 `fuzzware_replay_crashes`: A view created by this module for convenient crash information querying
  - 🟣 `id INTEGER`: Record ID
  - 🟣 `path TEXT`: Crash input path
  - 🟣 `err_msg TEXT`: Error message (JSON format)
  - 🟣 `err_in_interrupt BOOLEAN`: Whether the error occurred in an interrupt
  - 🟣 `func_cov DOUBLE PRECISION`: Function coverage
  - 🟣 `basic_block_cov DOUBLE PRECISION`: Basic block coverage
  - 🟣 `pc BIGINT`: Program counter value
  - 🟣 `lr BIGINT`: Link register value

## Supplementary Explanation

Main functions and working principles of this module:

1. **Core Functions**:
   - Automatically discover all crash input files in Fuzzware Pipeline directory
   - Replay crash inputs one by one to reproduce crash scenarios
   - Capture and analyze error information at crash time
   - Calculate code coverage for each replay (function-level and basic block-level)

2. **Crash Discovery Workflow**:
   - Traverse `pipeline/main*/fuzzers/fuzzer*/crashes/` directories
   - Collect all crash files (excluding README files)
   - Support multiple main directories and multiple fuzzer instances

3. **Replay Execution Process**:
   - First activate Fuzzware environment (call `activateEnv`)
   - Generate corresponding configuration file based on firmware type (ELF or raw firmware)
   - Build and execute `fuzzware replay` command
   - Use `FuzzwareOutputMonitor` to monitor output

4. **Error Information Capture**:
   - Monitor standard output and standard error
   - Extract error message list
   - Detect whether error occurred in interrupt handler
   - Record register state at time of error (PC, LR, and other key registers)

5. **Coverage Calculation**:
   - **Function Coverage**: Number of functions visited / Total number of functions
   - **Basic Block Coverage**: Number of basic blocks visited / Total number of basic blocks
   - Obtain actually executed addresses by monitoring Fuzzware output
   - Compare with program structure in Ghidra for calculation

6. **Data Structures**:
   - `CrashReplayResult`: Serializable replay result data class
   - Results are stored in database table in JSON format

7. **Error Handling**:
   - Uses `@FailOnCancelled` annotation, fails immediately when task is cancelled
   - Catches exceptions and sets failure flag

## Notes

1. **Prerequisites**:
   - Must run `FuzzwareGateway` module first to obtain basic configuration
   - Need to run `FuzzwarePipeline` or other Fuzzware fuzzing workflow first to generate crash files
   - System must have Fuzzware and its dependencies installed

2. **System Requirements**:
   - Only supported on Linux systems
   - Requires Python virtualenvwrapper (workon command)
   - Requires installed Fuzzware toolchain

3. **NTFS File System Warning**: If binary files are stored on NTFS partitions, running Fuzzware is not recommended because NTFS doesn't support colons (:) in filenames, which are used by Fuzzware input files, causing initialization failures

4. **Timeout Setting**: Uses `@FailOnCancelled` annotation, no fixed runtime timeout limit

5. **Application Scenarios**:
   - Verify whether crashes discovered by Fuzzware are reproducible
   - Analyze root cause of crashes
   - Evaluate crash severity (whether occurred in interrupt)
   - Compare code coverage of different crash inputs

6. **Output Parsing**:
   - Recommend using `fuzzware_replay_crashes` view to query results
   - Can directly obtain crash path, error messages, and register context from the view
   - Coverage data can be used to evaluate test quality

7. **Performance Considerations**:
   - Each crash input starts an independent process
   - Large number of crash files may require longer processing time
   - Recommended to prioritize analyzing crash inputs with high coverage
