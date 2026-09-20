# 物联网内存分析模块 —— AddressSpaceAnalyzer

## 分类

- 物联网固件

## 描述

根据给定的基地址、入口地址、主栈指针和内存段，使用 Akiba 封装 Ghidra 内置仿真器的 `StepInEmulator` 进行仿真。本模块将分析所有直接的内存-内存数据复制流，并聚合结果，以尝试寻找固件文件中的数据段位置。

对于 ARM Cortex 架构的固件，本模块还可以跟踪中断向量表的复制操作。

## 数据与模块交互

### 临时数据

- 🅞🅘 `BasicLoadMetadata load_metadata`: 用于加载固件文件的内存段信息。
- 🅘 (可选) `ArmcmIVT ivt`: 用于 ARM Cortex 固件，用于跟踪可能的中断向量表复制行为。

### 模块依赖

- 🔴 `org.iotsplab.akiba.process.IoTGeneralStructures`: 物联网固件相关数据结构定义
- 🔴 `org.iotsplab.akiba.process.ARMBaseFinder`: 用于 ARM Cortex 固件，用于定义中断向量表数据结构

### 数据表列

- 🟢 `data_file_offset INTEGER`: 寻找到的 `.data` 段在固件中的偏移值。
- 🟢 `data_size INTEGER`: 寻找到的 `.data` 段的长度。
- 🟢 `data_map_start INTEGER`: 运行时 `.data` 段在内存中映射的首地址。

## 补充解释

对于一个能够被整体烧写到一个 Flash 内存的小型固件文件，其 `.data` 段一般位于代码段的正后方，且在固件上电初始化时需要被复制到正确的 RAM 内存位置。另外，固件上电初始化时还需要初始化 `.bss` 段，这需要将 `\x00` 值覆盖 RAM 中的 `.bss` 段，在上电初始化过程中，覆盖行为一般是从低地址向高地址顺序覆盖。本模块会监控所有的内存复制行为以及 `\x00` 覆盖行为，尝试寻找固件 `.data` 段和 `.bss` 段的可能位置。

## 备注

本模块会记录 `\x00` 内存覆盖行为，但不会将该行为记录到数据库中。因为在实际测试过程中，发现此类覆盖行为常常有不止一段，无法确定哪个是 `.bss` 段。