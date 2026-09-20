# Hoedur 模糊测试对接模块 —— HoedurFuzz

# 分类

- 物联网固件

# 描述

与模糊测试工具 Hoedur 对接的主要模块，用于启动 Hoedur 模糊测试。

## 数据与模块交互

### 临时数据

- 🅘 `FuzzwareGatewayConfig fuzzware_basic_conf`: Fuzzware 基础配置信息，包含项目路径、虚拟环境等配置
- 🅘 `Long <conf.baseAddressSource>`: 输入基地址值，当文件类型不是 ELF 时使用。
- 🅘 `Long <conf.ivtStartSource>`: 输入 IVT 地址值，当文件类型不是 ELF 时使用。

### 模块依赖

- 🔴 `org.iotsplab.akiba.process.FuzzwareGateway`: Fuzzware 相关配置。

### 配置文件

配置文件类：`org.iotsplab.akiba.process.HoedurFuzzConfig`

```json
{
  "hoedurRoot": null,                 // Hoedur 工具根目录，必须指定
  "cargoPath": "~/.cargo/bin/cargo",  // cargo 路径，默认为 ~/.cargo/bin/cargo
  "maxTimeoutMinutes": "60m"          // 模糊测试超时时间，默认 60 分钟
  "crashArchiveRoot": "hoedur-fuzz-output", // 崩溃文件保存目录，默认 hoedur-fuzz-output
  "hoedurLogConfigPath": null,        // Hoedur 日志配置文件路径，默认 null（表示 INFO 等级日志）
  "baseAddressSource": "arm_base_finder_results.base_address",  // 模糊测试使用的基址来源，必须指定，默认 arm_base_finder_results.base_address
  "ivtStartSource": "arm_base_finder_results.ivt_start" // 模糊测试使用的 IVT 起始地址来源，可不指定（不指定时将文件开头视为 IVT 起始地址），默认 ivt_start_finder_results.ivt_start
}
```

### 数据表列

- 🟢 `set_fuzz_time`: 设置的最长模糊测试超时时间
- 🟢 `actual_fuzz_time`: 实际的模糊测试时间（若基本信息错误，可能导致模糊测试无法启动或提前结束）

## 补充解释

本模块使用`fuzz.py`脚本文件启动模糊测试。由于 Hoedur 与 Fuzzware 使用的配置文件格式不同，需要使用 Hoedur 的可执行文件`hoedur-convert-fuzzware-config`转换配置文件

## 备注

1. **Hoedur diff**

Hoedur 的默认根目录为 Hoedur 工具的根目录，需要修改修改`scripts/fuzz_common.py`第157行为：

```python
  target_dir = '{}'.format(target)
```

修改scripts/fuzz-plot-data.py第109行：

```python
  # f'{HOEDUR_TARGETS}/arm/{target}/valid_basic_blocks.txt',
  f'{target}/valid_basic_blocks.txt'
```

修改scripts/fuzz-coverage-list.py第80行：

```python
  # f'{HOEDUR_TARGETS}/arm/{target}/valid_basic_blocks.txt',
  f'{target}/valid_basic_blocks.txt',
```

2. **Hoedur 各个脚本的功能**

- eval-bug-combinations.py：应该是输出所有崩溃的信息。输出整合在一个yml文件中：

```yaml
---
- - Crash:
    pc: 104998
    ra: 162901
- time: 13
  source:
  input: 2
  report: TARGET--data-hongyuan-all-results-combined-hoedur-fuzz-output-67-FUZZER-hoedur-RUN-01-DURATION-1h-MODE-fuzzware.report.bin.zst
```

- - python3 scripts/eval-bug-combinations.py <corpus根目录> --output <输出目录> --targets <corpus根目录>
  - 经过源代码级别调试发现，hoedur会以崩溃时的pc和lr为依据进行自动分类，从而达到去重的效果。
  - ❗Hoedur将异常分为4类：组合异常、崩溃（crash）、不可执行（non-executable）、非法写（rom-write），并按照这个类型进行分类：

```rust
pub enum CrashReason {
    BugCombination(Vec<String>),
    Crash { pc: Address, ra: Address },
    NonExecutable { pc: Address },
    RomWrite { pc: Address, addr: Address },
}
```

- eval-bug-reproducer.py：
- eval-coverage.py：计算覆盖率，但是需要首先有valid_basic_blocks.txt：
  - python3 scripts/eval-coverage.py <输出目录> <corpus根目录，即xxx/hoedur-fuzz-output/数字> --targets <corpus根目录（因为只有这一个target，所以写这个就行了）>
  - 在corpus根目录中一定要有valid_basic_blocks.txt
- eval-crash-time.py：计算崩溃时间，输出一个json文件
  - python3 scripts/eval-crash-time.py <corpus根目录> --output-json 输出文件
- eval-executions.py：获得简单的执行结果输出，输出包括一个summary文件和多个target文件，summary文件中保存所有target执行的每秒emu次数，target文件中有3个值：fuzz持续时间、总fuzz的emu数量、每秒的fuzz数量。
  - python3 scripts/eval-executions.py <输出目录> <summary文件路径> <corpus根目录> --targets <corpus根目录>
- eval-merge-group.py：对结果分组
  - python3 scripts/eval-merge-group.py <corpus根目录> --output-dir <输出目录> --targets <corpus根目录>