# Fuzzware 模糊测试模块 —— FuzzwarePipeline

## 分类

- 物联网固件

## 描述

执行 Fuzzware 模糊测试流水线，自动运行模糊测试并收集覆盖率统计信息。

## 数据与模块交互

### 临时数据

- 🅘 `FuzzwareGatewayConfig fuzzware_basic_conf`: Fuzzware 基础配置信息，包含项目路径、虚拟环境等配置
- 🅘 `Long arm_base_finder_results.base_address`: 由 ARMBaseFinder 模块获取的基地址值，当文件类型不是 ELF 时使用
- 🅘 `Long arm_base_finder_results.ivt_start`: 由 ARMBaseFinder 模块获取的 IVT 地址值，当文件类型不是 ELF 时使用

### 模块依赖

- 🔴 `org.iotsplab.akiba.process.FuzzwareGateway`: 提供 Fuzzware 环境激活、配置生成和覆盖率信息提取功能

### 配置文件

配置文件类：`org.iotsplab.akiba.process.FuzzwarePipelineConfig`

```json
{
  "maxTimeout": "00:00:10:00",        // 最大超时时间（格式：DD:HH:mm:ss，默认 10 分钟）
  "otherArguments": "",               // 其他传递给 Fuzzware 的参数
  "withGDMA": false                   // 是否启用 GDMA（通用 DMA）支持
}
```

### 数据表列

- 🟢 `set_fuzz_time INTEGER`: 预设的模糊测试时间（秒）。
- 🟢 `actual_fuzz_time INTEGER`: 实际的模糊测试时间（秒）。
- 🟢 `basic_blocks_visited INTEGER`: 访问的基本块数量。
- 🟢 `basic_blocks_newfound INTEGER`: 新发现的基本块数量。
- 🟢 `basic_blocks_total INTEGER`: 基本块总数。
- 🟢 `coverage REAL`: 覆盖率（0.0-1.0 之间的小数）。

## 补充解释

该模块的主要功能和工作原理：

1. **核心功能**：
   - 调用 FuzzwareGateway 激活 Fuzzware 环境
   - 根据文件类型（ELF 或原始固件）生成相应的配置文件
   - 启动 Fuzzware Pipeline 进行模糊测试
   - 实时监控模糊测试进度和覆盖率
   - 超时后自动终止并清理进程

2. **工作流程**：
   - 检查并激活 Fuzzware 虚拟环境
   - 调用 FuzzwareGateway 生成配置文件（区分 ELF 和普通固件）
   - 启动 `fuzzware pipeline` 命令，设置超时时间
   - 实时读取输出日志，记录首次访问的基本块数量
   - 超时后强制终止所有相关进程（包括 Redis 子进程）
   - 调用 FuzzwareGateway 提取详细的覆盖率信息
   - 更新数据库中的统计数据

3. **覆盖率统计**：
   - 从 Fuzzware 的输出中提取翻译块覆盖信息
   - 记录首次报告的基本块访问数（firstCov）
   - 最终统计总的访问基本块数（totalCov）
   - 计算覆盖率 = 访问的基本块数 / 总基本块数

4. **动态反汇编**：
   - 对于覆盖率信息中未识别的函数（UNKN），自动进行反汇编
   - 使用 DisasmHelper 在未知地址处创建新函数
   - 合并所有基本块信息，去重后得到总数

5. **进程管理**：
   - 支持超时自动终止（通过 Shutdown requested 检测）
   - 清理孤立的 Fuzzware 和 Redis 进程
   - 记录实际运行时间

6. **时间格式**：
   - 支持 DD:HH:mm:ss 格式的超时时间配置
   - 自动转换为 Duration 对象进行精确控制

## 备注

1. **前置依赖**：必须先运行 FuzzwareGateway 模块以生成必要的配置和环境

2. **系统要求**：
   - 仅在 Linux 系统上支持
   - 需要安装 Fuzzware 及其依赖（包括 Redis）
   - 需要 Python virtualenvwrapper

3. **超时设置**：使用 `@IgnoreRuntimeTimeout` 注解，但模块内部有自己的超时机制

4. **文件格式支持**：
   - 支持 ELF 格式文件（直接调用 `generateFuzzwareConfigForELF`）
   - 支持原始固件格式（需要 ARMBaseFinder 提供的基址和 IVT 信息）

5. **GDMA 支持**：通过 `withGDMA` 参数可以启用通用 DMA 支持，用于更复杂的硬件模拟

6. **性能考虑**：
   - 基本块数量的变化是流水线性能变化的潜在因素
   - 长时间运行会占用大量系统资源
   - 建议根据实际需求调整超时时间

7. **日志监控**：模块会实时输出 Fuzzware 的运行日志到 Akiba 的日志系统（TRACE 级别）
