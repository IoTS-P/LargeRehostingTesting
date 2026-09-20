# Multifuzz 模糊测试对接模块 —— Multifuzz

# 分类

- 物联网固件

# 描述

对接模糊测试工具 Multifuzz 并完成模糊测试、测试后数据统计等工作。

## 数据与模块交互

### 临时数据

- 🅘 `FuzzwareGatewayConfig fuzzware_basic_conf`: Fuzzware 基础配置信息，包含项目路径、虚拟环境等配置
- 🅘 `Long <conf.rebaseSource>`: 基地址值（不是ELF文件时使用），设置 Fuzzware 配置文件基地址值。
- 🅘 `Long <conf.ivtStartSource>`: 基地址值（不是ELF文件时使用），如果`conf.ivtStartSource`不为空，则设置 Fuzzware 配置文件中断向量表值。

### 模块依赖

- 🔴 `org.iotsplab.akiba.process.FuzzwareGateway`: 用于生成 Fuzzware 配置文件。
- 🔴 `org.iotsplab.akiba.process.HoedurFuzz`: 转换模糊测试超时时间格式需要，Multifuzz 和 Hoedur 都是使用如 "60m"、"8h" 类似时间，与 Fuzzware 使用的时间格式（一般为 "08:00:00"）不同。

### 配置文件

配置文件类：`org.iotsplab.akiba.process.MultifuzzConfig`

```json
{
  "multifuzzRoot": null,              // Multifuzz 根目录，必须指定
  "cargoPath": "~/.cargo/bin/cargo",  // cargo 路径，默认为 ~/.cargo/bin/cargo，Multifuzz 使用 Rust 开发
  "runFor": "60m",                    // 模糊测试运行时长，默认 60m
  "baseAddressSource": "arm_base_finder_results.base_address",  // 基地址值来源
  "ivtStartSource": "arm_base_finder_results.ivt_start",        // 中断向量表首地址来源
  "doFuzz": true,                     // 是否运行模糊测试，默认 true
  "doReplay": true,                   // 是否重放数据输入以统计崩溃信息，默认 true
  "replayThread": 8                   // 重放数据输入运行最大线程数，默认 8
}
```

### 数据表列

- 🟢 `set_fuzz_time INTEGER`: 设置的模糊测试时间，单位 s。
- 🟢 `actual_fuzz_time INTEGER`: 实际的模糊测试时间，单位 s。当基本信息错误或存在其他问题时，模糊测试可能提前结束或无法启动。
- 🟢 `basic_blocks_visited INTEGER`: 本轮模糊测试共访问到的基本块数量。
- 🟢 `coverage DOUBLE PRECISION`: 本轮模糊测试的基本块覆盖率，数值在 0-1 之间
- 🟢 `crash_count INTEGER`: 本轮模糊测试中崩溃数量。
- 🟢 `hang_count INTEGER`: 本轮模糊测试中超时数量。
- 🟢 `replay_data JSONB`: 输入重放时产生的崩溃信息汇总。

## 补充解释

**1. Multifuzz diff**

为了方便 replay 时自定义 trace 文件的保存位置与文件名，修改`hail-fuzz/src/debugging/replay.rs`第 255 行：

```diff
diff --git a/hail-fuzz/src/debugging/replay.rs b/hail-fuzz/src/debugging/replay.rs
index 3bed998..55d3881 100644
--- a/hail-fuzz/src/debugging/replay.rs
+++ b/hail-fuzz/src/debugging/replay.rs
@@ -252,7 +252,11 @@ fn replay_trace(mut vm: Vm, mut target: CortexmMultiStream) -> anyhow::Result<()
     }
     if icicle_fuzzing::parse_bool_env("SAVE_TRACE")?.unwrap_or(true) {
         let symbolize = icicle_fuzzing::parse_bool_env("SYMBOLIZE_TRACE")?.unwrap_or(false);
-        path_tracer.save_trace(&mut vm, "trace.txt".as_ref(), symbolize);
+        let trace_path = match std::env::var("TRACE_PATH").ok() {
+            Some(path) => path,
+            None => "trace.txt".to_string(),
+        };
+        path_tracer.save_trace(&mut vm, trace_path.as_ref(), symbolize);
     }
     if icicle_fuzzing::parse_bool_env("SAVE_MMIO_READS")?.unwrap_or(false) {
         trace::save_mmio_reads("mmio_reads.txt".as_ref(), &path_tracer.get_mmio_reads(&mut vm));
```

现在可以指定环境变量`TRACE_PATH`为 trace 文件的输出路径。

**2. Multifuzz 使用方法**

Multifuzz 配置很简单，只需要设置一些环境变量即可。在 README 中已经基本解释清楚。

**Pipeline**

```rust
WORKDIR=<数据输出目录> COVERAGE_MODE=blocks RUN_FOR=1h cargo run --release -- <源文件目录>
```

**Replay**

```rust
REPLAY=<输入文件路径> cargo run --release -- <源文件目录>
```

上面的命令可以在当前目录生成一个 trace.txt，其中包含所有基本块 trace 信息，一行 6 个数字：
pc,sp,icount,fuzz_offset,last_read,last_value

用这里的 pc 获取本 crash 的运行基本块集合，进而计算基本块覆盖率

**Cov**

```rust
GEN_BLOCK_COVERAGE=blocks cargo run --release -- ./firmware
```
上面的命令可以生成当前的总体覆盖到的基本块。这部分数据实际上也可以通过 workdir/cur_coverage.txt 读取，但上面的命令可以输出 json，而 cur_coverage.txt 是按行读取。

另外，有一些环境变量在 README.md 中没有说明，通过查找`parse_bool_env`与`std::env::var`调用可以获取这些环境变量的获取位置。

**3. 重放崩溃信息输出格式**

下面是一个崩溃信息的输出格式：

```text
[icicle] exited with: UnhandledException(code=ReadUnmapped, value=0x3bc00) (icount = 24444), active_irq = 0
[icicle] callstack:
0x000001d856: FUN_0001d810+0x46
0x000001d84b: FUN_0001d810+0x3a
0x000001b15a: FUN_0001b01c+0x13d

[icicle] last blocks:
0x1d852: FUN_0001d810+0x42
0x1d84a: FUN_0001d810+0x3a
0x20470: FUN_00020464+0xc
0x20464: FUN_00020464
0x1d840: FUN_0001d810+0x30
0x1d838: FUN_0001d810+0x28
0x20b60: FUN_00020b40+0x20
0x20b92: FUN_00020b40+0x52
0x20b80: FUN_00020b40+0x40
0x20b80: FUN_00020b40+0x40

registers:
r0   = 0x00000000 r1   = 0x0000000a r2   = 0x20002f52 r3   = 0x0003bc00
r4   = 0x461cdec4 r5   = 0x00000000 r6   = 0x00000000 r7   = 0x00000000
r8   = 0x00000000 r9   = 0x00000000 r10  = 0x00000000 r11  = 0x00000000
r12  = 0x20004000 sp   = 0x20003fe0 lr   = 0x0001d84b pc   = 0x0001d856
cpsr = 0x00000000
CY   = 0x00 ZR   = 0x00 NG   = 0x00 OV   = 0x00
```

下面是一个 trace 文件的格式：

```csv
pc,sp,icount,fuzz_offset,last_read,last_value
0x1b200,0x20004000,0,0,0x0,0x0
0x21f28,0x20004000,6,0,0x0,0x0
0x21f54,0x20003ff8,12,0,0x0,0x0
0x21f82,0x20003ff8,16,0,0x0,0x0
0x21fa6,0x20003ff8,20,0,0x0,0x0
......
```

本模块输出到数据库的 `replay_data` 包含：
- path: 崩溃输入路径。
- errMsg: 崩溃信息。
- coverage: 本次崩溃输入运行的覆盖率。
- pc: 本次崩溃输入最后崩溃时 pc 的值。
- lr: 本次崩溃输入最后崩溃时 lr 的值。
- stackTrace: 崩溃输入崩溃时的栈帧结构。