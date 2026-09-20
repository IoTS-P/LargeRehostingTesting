# ConvertFirmToELF

## Category

- IoT Firmware
- Firmware Analysis

## Description

Convert firmware files to ELF file format for easier execution in various fuzzing tools.

## Data and Module Interactions

### Module Dependencies

- 🔴 `org.iotsplab.akiba.process.iotgs.BasicLoadMetadata`: Provides metadata structure required for firmware loading
- 🔴 `net.fornwall.jelf.ElfFile`: ELF file parsing library for obtaining ELF constant definitions

### Configuration File

Configuration file class: `org.iotsplab.akiba.process.ConvertFirmToELFConfig`

```json
{
  "useSource": "existing"  // Source of metadata: "existing" means using existing load_metadata, "firmxray" means parsing from FirmXRay
}
```

### Database Columns

- 🟢 `elf_path TEXT`: ELF file path that this module will output to the database

## Supplementary Explanation

Main functions and workflow of this module:

1. **Supported Architectures**: ARM (32/64-bit), MIPS (32/64-bit), RISC-V (32/64-bit), x86 (32/64-bit)

2. **Working Principle**:
   - Obtain firmware metadata (architecture, word size, endianness, memory sections, entry point, etc.) from input data or FirmXRay
   - Encode metadata into binary format and write to a temporary file
   - Call C++ ELF Builder (ELFBuilder) to generate standard ELF file
   - Save the generated ELF file path to the database

3. **Memory Section Information**: Each memory section needs to include base address, file offset (if exists), size, and protection flags

4. **Two Data Source Modes**:
   - `existing` mode: Use existing `load_metadata` data (output from other modules)
   - `firmxray` mode: Parse metadata from FirmXRay module results

## Notes

1. **System Requirements**: Only supported on Linux systems
2. **Timeout Setting**: Uses `@IgnoreRuntimeTimeout` annotation, no runtime timeout limit
3. **Failure Handling**: If the ELF building process fails, the task will be marked as failed
4. **Temporary Files**: Uses temporary files in `/tmp` directory to exchange data with C++ ELF parser, automatically deleted after completion
5. **Output Location**: Generated ELF files are saved to `{binariesRoot}/parsed_elfs/{id}.elf`
