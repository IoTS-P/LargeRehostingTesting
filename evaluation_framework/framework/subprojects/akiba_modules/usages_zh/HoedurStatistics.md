# Hoedur 模糊测试结果数据统计模块 —— HoedurStatistics

# 分类

- 物联网固件

# 描述

在 Hoedur 模糊测试完成后，统计各类崩溃数量等数据。

## 数据与模块交互

### 临时数据

- 🅘 `Long <conf.rebaseSource>`: 基地址值，如果`conf.rebaseSource`不为空，则会在数据统计前首先重新设置基地址值。

### 配置文件

配置文件类：`org.iotsplab.akiba.process.HoedurStatisticsConfig`

```json
{
  "cmdPrefix": ["/bin/sh", "-c"],     // 命令前缀，后面只需要输入一个字符串即可，而不需要隔开
  "cmdPredo": "",                     // 前置命令
  "cargoPath": "~/.cargo/bin/cargo",  // cargo 路径
  "hoedurRoot": null,                 // Hoedur 根目录，必须指定不能为空
  "pythonPath": "/bin/python3",       // Python 路径
  "crashArchiveRoot": "hoedur-fuzz-output",   // Hoedur 的崩溃数据目录，相对于主配置中的 general/binariesRoot 字符串
  "hoedurLogConfigPath": null,        // Hoedur 日志等级配置文件路径，如果为空则默认为 info
  "rebaseSource": null                // 是否需要重新设置基地址值，如果为空则认为不需要重新设置，否则将以该字符串寻找临时变量
}
```

### 数据表列

- 🟢 `crash_count INTEGER`: 发现的崩溃输入数量。
- 🟢 `timeout_count INTEGER`: 发现的超时输入数量。
- 🟢 `exit_count INTEGER`: 发现的退出输入数量。
- 🟢 `fuzzware_err_count INTEGER`: 发现的 Fuzzware 错误数量。
- 🟢 `unique_bugcomb_count INTEGER`: 发现的去重其他错误数量。
- 🟢 `unique_crash_count INTEGER`: 发现的去重 Crash 类型崩溃数量。
- 🟢 `unique_nonexec_count JSONB`: 发现的去重 NonExecutable 类型崩溃数量。
- 🟢 `unique_writeprot_count INTEGER`: 发现的去重 RomWrite 类型崩溃数量。
- 🟢 `full_crash_info JSONB`: 完整崩溃输出。
- 🟢 `coverage_info TEXT`: 各个输入的基本块覆盖率。
- 🟢 `fuzz_cov DOUBLE PRECISION`: 所有输入的基本块并集覆盖率。
- 🟢 `total_execs_done BIGINT`: 运行的总输入数量。
- 🟢 `total_execs_per_second DOUBLE PRECISION`: 平均每秒运行输入数量。
- 🟣 `hoedur_replay_data`: Hoedur 崩溃输入重放数据视图。
  - 🟣 `input_id INTEGER`: 崩溃输入的 id。
  - 🟣 `err_type TEXT`: 崩溃类型。
  - 🟣 `err_data JSONB`: 崩溃信息数据，一般包括 pc、lr 等。
  - 🟣 `err_timestamp_from_fuzz_start INTEGER`: 找到该错误的时间距离模糊测试开始的时间差，单位 s。

## 补充解释

**1. Hoedur 调试**

Hoedur 的崩溃类型与 Fuzzware、Multifuzz 不同，且难以跟踪。要进行指令/基本块级调试，首先需要使用 Rust 语言自行编写 hook.rn 脚本文件，并在运行时指定：

```shell
hoedur-arm --debug --trace --trace-file <TRACE_FILE.rn> ...(subcommands, suboptions)
```

下面是一个示例：

```rust
pub fn main(api) {
    // 注册初始化钩子
    api.on_init(|| log::info!("[Hook] print_info.rn initialized"));
  
    // 注册运行前钩子
    api.on_prepare_run(|| log::info!("[Hook] Starting new run"));
  
    // 注册运行后钩子
    api.on_post_run(|| log::info!("[Hook] Run completed"));
  
    // 注册基本块钩子 - 打印所有基本块信息
    api.on_basic_block(None, |pc| {
        log::info!("[BasicBlock] PC: 0x{:08x}", pc);
    });
  
    // 注册指令钩子 - 打印所有指令执行信息
    api.on_instruction(None, |pc| {
        log::info!("[Instruction] Executing at PC: 0x{:08x}", pc);
        log::debug!("[Registers] Current state:");
        for name in register::list() {
            if let Ok(value) = register::read(name) {
                log::debug!("  {:<4} = 0x{:08x}", name, value);
            }
        }
    });
  
    // 注册中断（异常）钩子 - 打印所有异常信息及寄存器状态
    api.on_interrupt(None, None, |pc, interrupt| {
        log::info!("[Exception] Triggered at PC: 0x{:08x}, Interrupt: {}", pc, interrupt);
    });
  
    // 注册退出钩子 - 打印退出点信息
    api.on_exit_hook(|pc| {
        log::info!("[ExitHook] Triggered at PC: 0x{:08x}", pc);
    });
  
    // 注册MMIO读取钩子 - 打印所有MMIO读取信息
    api.on_mmio_read(None, None, |pc, address, size, data| {
        log::info!("[MMIO Read] Address: 0x{:08x}, PC: 0x{:08x}, Size: {}, Data: 0x{:08x}", 
                   address, pc, size, data);
    });
  
    // 注册MMIO写入钩子 - 打印所有MMIO写入信息
    api.on_mmio_write(None, None, |pc, address, size, data| {
        log::info!("[MMIO Write] Address: 0x{:08x}, PC: 0x{:08x}, Size: {}, Data: 0x{:08x}", 
                   address, pc, size, data);
    });
  
    // 注册RAM读取钩子 - 打印所有RAM读取信息
    api.on_ram_read(None, None, |pc, address, size, data| {
        log::info!("[RAM Read] Address: 0x{:08x}, PC: 0x{:08x}, Size: {}, Data: 0x{:08x}", 
                   address, pc, size, data);
    });
  
    // 注册RAM写入钩子 - 打印所有RAM写入信息
    api.on_ram_write(None, None, |pc, address, size, data| {
        log::info!("[RAM Write] Address: 0x{:08x}, PC: 0x{:08x}, Size: {}, Data: 0x{:08x}", 
                   address, pc, size, data);
    });
  
    // 注册ROM读取钩子 - 打印所有ROM读取信息
    api.on_rom_read(None, None, |pc, address, size, data| {
        log::info!("[ROM Read] Address: 0x{:08x}, PC: 0x{:08x}, Size: {}, Data: 0x{:08x}", 
                   address, pc, size, data);
    });
  
    // 注册ROM写入钩子 - 打印所有ROM写入信息
    api.on_rom_write(None, None, |pc, address, size, data| {
        log::info!("[ROM Write] Address: 0x{:08x}, PC: 0x{:08x}, Size: {}, Data: 0x{:08x}", 
                   address, pc, size, data);
    });
}
```

**2. Hoedur 获取崩溃输入**

Hoedur 会将一次模糊测试的所有崩溃输入保存到一个压缩文件中，这个文件一般以`corpus.bin.zst`结尾。在压缩文件中，`/crash`保存所有去重的崩溃输入，`/timeout`保存所有超时的崩溃输入，`/exit`保存所有退出的崩溃输入。

**3. Hoedur 获取覆盖率**

使用`hoedur-coverage-list`命令可以获取一次模糊测试的覆盖率信息压缩文件，在压缩文件中，有`coverage-superset.txt`，表示所有输入遍历到的基本块超集，统计行数并除以基本块总数即可获取覆盖率值。

```shell
cargo run --bin hoedur-coverage-list --output <Coverage file output path> {Report file path} --no-filter
```

除此之外，在压缩文件中还包括所有去重输入的覆盖率信息，在压缩包中路径为`coverage/input-???.txt`，其中`???`表示输入 id。

**4. Hoedur 获取模糊测试性能**

使用`hoedur-eval-executions`子命令可以获取本次模糊测试的性能，包括一共运行完成的输入数量，以及平均每秒运行的输入数量。

```shell
cargo run --bin hoedur-eval-executions <Performance file output path> <Corpus file path>
```

相关信息会输出到`<Performance file output path>`路径的文件中。
