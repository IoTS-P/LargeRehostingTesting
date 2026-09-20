# Ghidra 环境初始化模块 ProgramInitialization

## 分类

- 物联网固件

## 描述

用于对一个物联网分析环境（Ghidra 称为 program）进行初始化。

## 补充解释

需要完成的工作：

1. 环境前置检查：不会分析大于 10MiB 的文件。
2. 完成 Ghidra 自动分析流程。（默认开启 aggressive instruction finder，选择该选项将使 Ghidra 使用更激进的策略查找汇编指令）
3. 如果自动分析无法找到不少于 10 个函数，则该二进制文件将被记录为无效文件，即 `Not a valid firmware`，并记录到数据库，任务将标记为失败。