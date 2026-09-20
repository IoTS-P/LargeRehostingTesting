# Fuzzware 实用模块 —— FuzzwareGateway

## 分类

- 物联网固件

## 描述

为 Fuzzware 模糊测试流程生成配置文件并提供辅助功能，是运行 Fuzzware 的前置准备模块。

## 数据与模块交互

### 临时数据

- 🅞 `FuzzwareGatewayConfig fuzzware_basic_conf`: Fuzzware 基础配置信息，包含项目路径、虚拟环境等配置

### 模块依赖

- 🔴 `org.iotsplab.akiba.process.ARMBaseFinder`: 中断向量表相关数据结构定义。

### 提供的 API

- 🟠 `suspend fun activateEnv(): Boolean`: 激活 Fuzzware 环境，检查必要的命令和虚拟环境是否存在。
- 🟠 `suspend fun generateFuzzwareConfig(baseAddress: Long, ivtStart: Long, forHoedur: Boolean, taskMonitor: TaskMonitor): Path`: 为原始固件生成 Fuzzware 配置文件。
- 🟠 `suspend fun generateFuzzwareConfigForELF(forHoedur: Boolean): Path`: 为 ELF 文件生成 Fuzzware 配置文件。
- 🟠 `fun getCovInfo(proj: Path): CoverageInfo`: 获取 Fuzzware 覆盖率信息。

### 配置文件

配置文件类：`org.iotsplab.akiba.process.FuzzwareGatewayConfig`

```json
{
  "cmdPrefix": ["/bin/sh", "-c"],            // 命令执行前缀
  "cmdPredo": "",                            // 前置命令（如 source 脚本）
  "regenerateConfig": true,                  // 是否重新生成配置文件
  "projectRoot": "fuzzware-projects",        // Fuzzware 项目根目录名称
  "venv": "fuzzware",                        // Python 虚拟环境名称
  "doRebase": true                           // 是否对内存块进行重基址操作
}
```

### 数据表列

本模块不创建数据库表（@DoNotCreateTable）。

## 补充解释

该模块的主要功能和工作原理：

1. **核心功能**：
   - 为 Fuzzware 模糊测试引擎生成 YAML 配置文件
   - 提供环境激活和检查功能
   - 支持从原始固件文件和 ELF 文件生成配置
   - 提取覆盖率信息

2. **生成的配置内容**：
   - 内存映射（memory_map）：包括文本段、RAM、MMIO、NVIC 等区域
   - 符号表（symbols）：所有函数地址和名称
   - IVT（中断向量表）偏移量
   - 中断触发器配置

3. **两种配置生成模式**：
   - **普通固件模式** (`generateFuzzwareConfig`)：适用于原始二进制固件文件，需要基址和 IVT 起始地址
   - **ELF 文件模式** (`generateFuzzwareConfigForELF`)：适用于标准 ELF 文件，自动解析段信息

4. **环境检查功能** (`activateEnv`)：
   - 检查 `workon` 命令是否可用（virtualenvwrapper）
   - 检查指定的 Python 虚拟环境是否存在
   - 检查 `fuzzware` 命令是否已安装
   - 检查 `arm-none-eabi-objcopy` 工具链是否可用

5. **覆盖率信息提取** (`getCovInfo`)：
   - 调用 Fuzzware 的覆盖率分析命令
   - 解析找到的符号和基本块信息
   - 返回结构化的覆盖率数据

6. **内存重定位**：
   - 支持对 Ghidra 中的内存块进行重基址操作
   - 确保只能执行一次重定位，避免地址错误

7. **ELF 文件处理**：
   - 自动解析 ELF 段头，合并相邻段
   - 区分可加载段和未初始化段（如 .bss）
   - 生成优化的二进制文件和对应的 YAML 配置

## 备注

1. **系统要求**：
   - 仅在 Linux 系统上完全支持
   - 需要安装 Fuzzware 及其依赖
   - 需要 ARM GCC 工具链（arm-none-eabi-objcopy）
   - 需要 Python virtualenvwrapper

2. **NTFS 文件系统警告**：如果二进制文件存储在 NTFS 分区上，不建议运行 Fuzzware，因为 NTFS 不支持文件名中包含冒号（:），而 Fuzzware 的输入文件使用冒号，会导致初始化失败

3. **超时设置**：使用 `@IgnoreRuntimeTimeout` 注解，不设置运行时超时限制

4. **配置重用**：默认会重新生成配置文件，可通过 `regenerateConfig` 参数控制

5. **重定位安全**：多次重定位可能导致基本块地址错误，模块会检测并阻止此行为

6. **应用场景**：主要用于 ARM Cortex-M 系列微控制器固件的模糊测试准备工作

7. **Fuzzware diff**：原 Fuzzware 仓库代码需要微调：
> 在 /emulator/harness/fuzzware_harness/native/native_hooks.c 第1107行，需要将_exit改为exit，否则重定向输出将无法捕获最后一行关键错误信息的输出。改完之后在同目录下make即可。