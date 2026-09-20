# ARMBaseFinder

## Category

- IoT Firmware

## Description

Attempts to determine the base address, entry point, and interrupt vector table address of an ARM Cortex series firmware.

## Data and Module Interactions

### Temporary Data

- 🅞 `ArmcmIVT ivt`: The found interrupt vector table structure.

### Configuration File

Configuration file class: `org.iotsplab.akiba.process.ARMBaseFinderConfig`

```json
{ 
  "totalMatchThreshold": 10,        // Minimum match count threshold, i.e., the minimum occurrence count of a difference 
  "totalMatchRatioThreshold": 0.7,  // Minimum match ratio threshold, i.e., the minimum proportion of the difference 
  "uniqueMatchThreshold": 10,       // Minimum unique match count threshold, i.e., the deduplicated count of original address values corresponding to the difference 
  "scoreThreshold": 5,              // Minimum score threshold, i.e., the threshold for matching scores 
  "compulsoryBaseAlignment": 256,   // Base address alignment requirement 
  "compulsoryIVTAlignment": 256,    // Interrupt vector table alignment requirement
  "checkTopMatchesCount": 5,        // Maximum number of candidate base addresses for dynamic verification 
  "tryDifferentAlignment": false,   // Try different alignment methods (add 1, 2, 3 bytes to the beginning of the file to adjust alignment) 
  "matchThreadNumber": 1,           // Number of matching threads "compulsoryCheckForEntry": false // Whether to verify the entry point 
}
```

### Module Dependencies

- 🔴 `org.iotsplab.akiba.process.IoTGeneralStructures`: Data structure definitions for IoT firmware.
- 🔴 `org.iotsplab.akiba.process.ProgramServer`: Pre-builds required data mappings to accelerate the matching process.

### Database Columns

- 🟢 `base_address INTEGER`: The obtained base address value.
- 🟢 `entry_point INTEGER`: The obtained entry point value.
- 🟢 `ivt_start INTEGER`: The starting address of the interrupt vector table.

## Supplementary Explanation

The principle of the base address matching algorithm is **function address matching**. ARM Cortex series has a special structure—the **Interrupt Vector Table (`IVT`)**, which contains multiple function addresses. Therefore, our matching strategy can be summarized in the following steps:

1. Find all candidate `IVT`s (IVTs have certain characteristics; this module designs several heuristic pattern matching algorithms to identify these features) and extract all function pointers from them as set $F_i$.
2. Extract all functions $F$ from the firmware after Ghidra's automatic analysis completes.
3. For $\forall f_i \in F_i$, $\forall f \in F$, calculate the candidate base address $f_i - f$. Obtain all differences as a repeatable set $S$.
4. Get the most frequently occurring value in $S$, and go through several rounds of filtering (static judgment + dynamic verification) to ultimately obtain the most likely candidate base address values.
5. Use a scoring mechanism to filter the final candidates, select the one with the highest score, and obtain the `IVT`, base address, and entry point.

## Notes

❗ **Performance Warning**: When the number of functions in the firmware is large, this module may consume significant memory and time.
