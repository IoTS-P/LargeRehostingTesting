# StartupDynamicChecker

## Category

- IoT Firmware

## Description

Determines the validity of a firmware entry point by simulating execution starting from the firmware entry point. If a sufficient number of instructions and functions are executed, the entry address and entry point will be considered valid.

## Data and Module Interactions

### Temporary Data

- 🅘 `Address load_metadata`: Metadata containing 4 fields, including the firmware's base address, entry point, initial stack pointer value, and memory segments to load.

### Module Dependencies

- 🔴 `org.iotsplab.akiba.process.IoTGeneralStructures`: Data structure definitions for IoT firmware.

### Database Columns

- 🟢 `entry_valid TEXT`: Fill in `"valid"` if the test is valid, otherwise fill in `"invalid"`.

## Supplementary Explanation

Only tests the validity of the firmware entry point based on whether a sufficient number of deduplicated instructions and functions can be executed. The default test criteria are not strict: 20 deduplicated instructions and 2 deduplicated functions. We count deduplicated instructions because sometimes execution may fall into an infinite loop.
