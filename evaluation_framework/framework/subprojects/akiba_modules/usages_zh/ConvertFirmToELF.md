# 固件转 ELF 模块 —— ConvertFirmToELF

## 分类

- 物联网固件
- 固件分析

## 描述

将固件文件转换为 ELF 文件格式，以便在各种模糊测试工具中运行。

## 数据与模块交互

### 模块依赖

- 🔴 `org.iotsplab.akiba.process.iotgs.BasicLoadMetadata`: 提供固件加载所需的元数据结构
- 🔴 `net.fornwall.jelf.ElfFile`: ELF 文件解析库，用于获取 ELF 常量定义

### 配置文件

配置文件类：`org.iotsplab.akiba.process.ConvertFirmToELFConfig`

```json
{
  "useSource": "existing"  // 元数据来源："existing" 表示使用已存在的 load_metadata，"firmxray" 表示从 FirmXRay 解析
}
```

### 数据表列

- 🟢 `elf_path TEXT`: 本模块将输出到数据库的 ELF 文件路径

## 补充解释

该模块的主要功能和工作流程：

1. **支持的架构**：ARM (32/64 位)、MIPS (32/64 位)、RISC-V (32/64 位)、x86 (32/64 位)

2. **工作原理**：
   - 从输入数据或 FirmXRay 获取固件的元数据（架构、字长、端序、内存段、入口点等）
   - 将元数据编码为二进制格式并写入临时文件
   - 调用 C++ ELF 构建器（ELFBuilder）生成标准的 ELF 文件
   - 将生成的 ELF 文件路径保存到数据库

3. **内存段信息**：每个内存段需要包含基地址、文件偏移量（如果存在）、大小和保护标志

4. **两种数据源模式**：
   - `existing` 模式：使用已有的 `load_metadata` 数据（来自其他模块的输出）
   - `firmxray` 模式：从 FirmXRay 模块的结果中解析元数据

## 备注

1. **系统要求**：仅在 Linux 系统上支持运行
2. **超时设置**：使用 `@IgnoreRuntimeTimeout` 注解，不设置运行时超时限制
3. **失败处理**：如果 ELF 构建过程失败，任务将标记为失败状态
4. **临时文件**：使用 `/tmp` 目录下的临时文件与 C++ ELF 解析器交换数据，完成后自动删除
5. **输出位置**：生成的 ELF 文件保存在 `{binariesRoot}/parsed_elfs/{id}.elf`
