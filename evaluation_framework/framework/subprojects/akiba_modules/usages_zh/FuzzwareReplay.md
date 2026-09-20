# Fuzzware 崩溃输入重放模块 —— FuzzwareReplay

## 分类

- 物联网固件
- 模糊测试
- 固件分析
- 崩溃分析

## 描述

重放 Fuzzware 模糊测试过程中发现的崩溃输入，分析崩溃详情并计算代码覆盖率。

## 数据与模块交互

### 临时数据

- 🅘 `FuzzwareGatewayConfig fuzzware_basic_conf`: Fuzzware 基础配置信息，包含项目路径、虚拟环境等配置
- 🅘 `Long arm_base_finder_results.base_address`: 由 ARMBaseFinder 模块获取的基地址值，当文件类型不是 ELF 时使用
- 🅘 `Long arm_base_finder_results.ivt_start`: 由 ARMBaseFinder 模块获取的 IVT 地址值，当文件类型不是 ELF 时使用

### 模块依赖

- 🔴 `org.iotsplab.akiba.process.FuzzwareGateway`: 提供 Fuzzware 环境激活和配置生成功能。

### 数据表列

- 🟢 `crash_replay_crashes TEXT`: JSON 格式的崩溃重放结果数组，每个元素包含：
  - `path`: 崩溃输入文件路径（相对于项目根目录）
  - `errMsg`: 错误消息列表
  - `errInInterrupt`: 是否在中断处理程序中发生错误
  - `errRegContext`: 错误发生时的寄存器上下文（PC、LR 等）
  - `functionCoverage`: 函数覆盖率（0.0-1.0）
  - `basicBlockCoverage`: 基本块覆盖率（0.0-1.0）

- 🟣 `fuzzware_replay_crashes`: 本模块创建的视图，用于方便地查询崩溃信息
  - 🟣 `id INTEGER`: 记录 ID
  - 🟣 `path TEXT`: 崩溃输入路径
  - 🟣 `err_msg TEXT`: 错误消息（JSON 格式）
  - 🟣 `err_in_interrupt BOOLEAN`: 是否在中断中发生错误
  - 🟣 `func_cov DOUBLE PRECISION`: 函数覆盖率
  - 🟣 `basic_block_cov DOUBLE PRECISION`: 基本块覆盖率
  - 🟣 `pc BIGINT`: 程序计数器值
  - 🟣 `lr BIGINT`: 链接寄存器值

## 补充解释

该模块的主要功能和工作原理：

1. **核心功能**：
   - 自动发现 Fuzzware Pipeline 目录中的所有崩溃输入文件
   - 逐个重放崩溃输入，重现崩溃场景
   - 捕获并分析崩溃时的错误信息
   - 计算每次重放的代码覆盖率（函数级和基本块级）

2. **崩溃发现流程**：
   - 遍历 `pipeline/main*/fuzzers/fuzzer*/crashes/` 目录
   - 收集所有崩溃文件（排除 README 文件）
   - 支持多个 main 目录和多个 fuzzer 实例

3. **重放执行过程**：
   - 首先激活 Fuzzware 环境（调用 `activateEnv`）
   - 根据固件类型生成相应的配置文件（ELF 或原始固件）
   - 构建并执行 `fuzzware replay` 命令
   - 使用 `FuzzwareOutputMonitor` 监控输出

4. **错误信息捕获**：
   - 监控标准输出和标准错误
   - 提取错误消息列表
   - 检测是否在中断处理程序中发生错误
   - 记录错误发生时的寄存器状态（PC、LR 等关键寄存器）

5. **覆盖率计算**：
   - **函数覆盖率**：访问的函数数量 / 总函数数量
   - **基本块覆盖率**：访问的基本块数量 / 总基本块数量
   - 通过监控 Fuzzware 输出获取实际执行的地址
   - 与 Ghidra 中的程序结构进行对比计算

6. **数据结构**：
   - `CrashReplayResult`：序列化的重放结果数据类
   - 结果以 JSON 格式存储到数据库表中

7. **错误处理**：
   - 使用 `@FailOnCancelled` 注解，任务取消时立即失败
   - 捕获异常并设置失败标志

## 备注

1. **前置条件**：
   - 必须先运行 `FuzzwareGateway` 模块获取基础配置
   - 需要先运行 `FuzzwarePipeline` 或其他 Fuzzware 模糊测试流程生成崩溃文件
   - 系统必须安装 Fuzzware 及其依赖

2. **系统要求**：
   - 仅在 Linux 系统上支持
   - 需要 Python virtualenvwrapper（workon 命令）
   - 需要已安装的 Fuzzware 工具链

3. **NTFS 文件系统警告**：如果二进制文件存储在 NTFS 分区上，不建议运行 Fuzzware，因为 NTFS 不支持文件名中包含冒号（:），而 Fuzzware 的输入文件使用冒号，会导致初始化失败

4. **超时设置**：使用 `@FailOnCancelled` 注解，不设置固定的运行时超时限制

5. **应用场景**：
   - 验证 Fuzzware 发现的崩溃是否可重现
   - 分析崩溃的根本原因
   - 评估崩溃严重性（是否在中断中发生）
   - 比较不同崩溃输入的代码覆盖范围

6. **输出解析**：
   - 推荐使用 `fuzzware_replay_crashes` 视图查询结果
   - 可以直接从视图中获取崩溃路径、错误信息和寄存器上下文
   - 覆盖率数据可用于评估测试质量

7. **性能考虑**：
   - 每个崩溃输入都会启动独立的进程
   - 大量崩溃文件可能需要较长的处理时间
   - 建议优先分析覆盖率高的崩溃输入