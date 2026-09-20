# Fuzzware 统计模块 —— FuzzwareStat

## 分类

- 物联网固件
- 模糊测试
- 固件分析

## 描述

分析 Fuzzware 模糊测试的统计信息，包括基本块覆盖率和模糊测试器性能指标。

## 数据与模块交互

### 临时数据

- 🅘 `Long <conf.rebaseSource>`: 基地址，如果指定，则会在计算覆盖率时调整基地址值，以防止因基地址错误而导致的覆盖率计算结果错误。

### 配置文件

配置文件类：`org.iotsplab.akiba.process.FuzzwareStatConfig`

```json
{
  "projectRoot": "fuzzware-projects",     // Fuzzware 项目根目录名称
  "rebaseSource": null                    // 重基址数据源（可选），指定从哪里基址
}
```

### 数据表列

- 🟢 `total_cov DOUBLE PRECISION`: 总覆盖率（所有 fuzzer 访问的基本块数 / 总基本块数）
- 🟢 `input_covs TEXT`: JSON 格式的输入覆盖率映射，键为 fuzzer/trace 文件名，值为覆盖率
- 🟢 `total_execs_done BIGINT`: 所有 fuzzer 完成的总执行次数
- 🟢 `total_execs_per_second DOUBLE PRECISION`: 平均每秒执行次数

## 补充解释

该模块的主要功能和工作原理：

1. **核心功能**：
   - 分析 Fuzzware Pipeline 生成的覆盖率数据
   - 计算总体覆盖率和每个输入的覆盖率
   - 统计所有 fuzzer 的性能指标

2. **基本块覆盖率计算**：
   - 收集程序中所有的函数基本块起始地址
   - 遍历 Pipeline 目录下所有 fuzzer 的 traces 文件（bblset 文件）
   - 解析十六进制格式的基本块地址
   - 过滤出在已映射的可执行内存区域内的地址
   - 计算覆盖率 = 访问的基本块数 / 总基本块数

3. **性能指标统计**：
   - 读取每个 fuzzer 的 `fuzzer_stats` 文件
   - 提取 `execs_done`（完成的执行次数）和 `execs_per_sec`（每秒执行次数）
   - 计算总的执行次数和平均执行速度

4. **重基址支持**：
   - 通过 `rebaseSource` 配置可以指定从其他任务获取基址
   - 使用 MemoryUtil 对内存块进行重定位以匹配 fuzzing 时的地址空间
   - 分析完成后恢复原始地址

5. **数据输出**：
   - `total_cov`：所有 fuzzer 访问的基本块并集占总基本块数的比例
   - `input_covs`：JSON 对象，记录每个 trace 文件的覆盖率
   - `total_execs_done`：所有 fuzzer 的执行次数总和
   - `total_execs_per_second`：根据各 fuzzer 的执行时间和次数计算的加权平均速度

## 备注

1. **前置条件**：需要先运行 FuzzwarePipeline 模块生成 Pipeline 目录和相关数据

2. **目录结构要求**：
   - 期望的目录结构：`{projectRoot}/{id}/pipeline/main*/fuzzers/fuzzer*/traces/bblset*`
   - 每个 fuzzer 目录下应有 `fuzzer_stats` 文件

3. **内存过滤**：只统计在已加载且可执行的内存块中的基本块地址，忽略无效地址

4. **超时设置**：使用 `@IgnoreRuntimeTimeout` 注解，不设置运行时超时限制

5. **应用场景**：用于评估模糊测试的效果，比较不同输入种子的覆盖率，分析 fuzzer 性能
