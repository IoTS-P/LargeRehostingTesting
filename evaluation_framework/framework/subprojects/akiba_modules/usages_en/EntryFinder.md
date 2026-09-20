# EntryFinder

## Category

- IoT Firmware

## Description

Obtains a set containing possible entry points of the firmware with minimal elements.

## Data and Module Interactions

### Temporary Data

- 🅞 `List<Function> entry_point_candidates`: Candidate set of entry points.

### Provided API

- 🟠 `suspend fun updateResult`: Re-acquire the candidate set of entry points (function definitions may change, so the final set may differ).

## Supplementary Explanation

The entry point of a binary file must not have any cross-references pointing to it, therefore we can first find all functions that are not called by any other functions. However, due to the existence of indirect calls, the number of such functions may be very large, so we need some additional criteria to further narrow down the range:

1. Functions without any return: The function containing the entry point mostly does not return, so we can remove those functions that have returns.
2. Functions containing function calls: The function containing the entry point must call other functions, so we can remove those functions that do not call any functions. (A jump to another location at the end of a function is also considered a call)
3. Functions without any parameters: The function containing the entry point must not contain any parameters (cannot be passed), so we can remove those functions that have parameters. (Ghidra deep analysis can infer function prototypes; although it cannot guarantee 100% accuracy, Ghidra in most cases will not incorrectly identify a function without parameters as having parameters)

After filtering with the above conditions, the final set contains very few functions (testing shows that the number of functions included is no more than 1% of all functions).

## Notes

If the entry point is not correctly recognized as code and disassembled, the final set will not contain the correct entry point.
