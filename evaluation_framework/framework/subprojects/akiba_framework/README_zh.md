**注意：请不要单独 clone 这个仓库，请使用下面的命令克隆整个 Akiba 项目：**

```shell
git clone https://github.com/IoTS-P/Akiba.git
cd Akiba
git submodule update --init --recursive
```

# Akiba 运行框架

Akiba 运行框架是 Akiba 工具的运行入口。

## 如何运行

### Ghidra 预处理

1. 在这里下载 Ghidra 11.3.2：[链接](https://github.com/NationalSecurityAgency/ghidra/releases/download/Ghidra_11.3.2_build/ghidra_11.3.2_PUBLIC_20250826.zip)
2. 解压 Ghidra.zip
3. (可选但一些模块可能需要) 解压一些 Ghidra 官方扩展 (zip 文件) 到 `/Ghidra/Features`，这样 Ghidra 在 Jar 打包时可以将这些扩展打包进 `ghidra.jar` （默认打包 `MachineLearning` 扩展）
4. 到 `/support` 目录运行 `buildGhidraJar` 脚本文件（在 Windows 上运行`buildGhidraJar.bat`）
5. 如果一切顺利，你可以在 `/support` 找到 `ghidra.jar` 文件，这就是 Akiba 需要的依赖之一，将其复制到 `/path/to/Akiba/lib`

### 编译

使用一行命令编译 Akiba 数据库守护程序、Akiba 框架和 Akiba 模块

```shell
# 在 Akiba 主目录下运行
./gradelw assemble  # Windows 系统使用 gradlew.bat
```

### 运行

```shell
cd build/distributions
unzip Akiba-<版本号>.zip
cd Akiba-<版本号>

# 将需要使用的模块复制到 modules 目录下，编写配置文件（重要！）

./bin/Akiba  # In Windows, use .\bin\Akiba.bat
```

## 命令行选项

### 基本选项

- `-c`/`--main-config <path>`：设置主配置文件路径。格式为 `<文件路径>@<JSON 路径>`，例如 `configs/config.json@/main`。默认值为 `configs/config.yaml@main`
- `--venv <dir>`：设置全局 Python 虚拟环境（virtual environment）根目录，默认为 `akiba-venv`
- `-h`/`--help`：显示帮助信息并退出
- `-V`/`--version`：输出版本号并退出

### 运行模式选项（互斥，必选其一）

Akiba 支持三种运行模式，这些模式互斥，每次运行只能选择其中一种：

#### 1. 正常分析模式（默认）

不添加任何模式选项时，Akiba 将按照主配置文件中的任务配置，对数据库中的二进制文件进行分析。

#### 2. 导入模式

- `-i`/`--import <config path>`：导入任务模式。指定该选项后，Akiba 将只执行文件导入任务，不进行任何分析工作。需要指定导入配置文件路径。

#### 3. 断点续传模式

- `-r`/`--restore <'latest'/timestamp>`：断点续传模式。当任务因 bug 或其他原因中断时，可使用该模式继续执行未完成的任务。
  - 参数可以是 `latest`（继续最新任务）或时间戳（如 `20250201140000`）
  - **注意**：指定 `-r` 后，`-c` 选项将被忽略。Akiba 会使用中断任务的原始配置（该配置在任务首次运行时已复制到 `log` 目录）
  - **警告**：除非明确知道目的，否则请勿修改 `log` 目录中的配置文件

断点续传模式还支持以下附加选项（与 `-r` 配合使用，互斥）：

- `-f`/`--fail-only`：仅重新处理标记为失败（failed）的程序
- `-e`/`--error-only`：仅重新处理标记为错误（error）的程序

### 子命令

Akiba 还提供以下子命令用于管理数据库实例：

#### 1. instance-create - 创建新的数据库实例

```shell
./bin/Akiba instance-create -n <名称> -u <用户> [其他选项]
```

**必需参数：**
- `-n`/`--name <名称>`：实例名称
- `-u`/`--user <用户名>`：Akiba 数据库用户名

**可选参数：**
- `-P`/`--password`：Akiba 用户密码。如不指定，将在命令行交互提示输入
- `-H`/`--host <主机>`：数据库守护程序主机地址，默认为 `127.0.0.1`
- `-p`/`--port <端口>`：数据库守护程序端口，默认为 `31777`
- `-h`/`--help`：显示帮助信息并退出

**示例：**
```shell
# 创建名为 test_instance 的实例，用户为 admin，使用默认主机和端口
./bin/Akiba instance-create -n test_instance -u admin

# 指定 Akiba db daemon 所在主机和端口
./bin/Akiba instance-create -n my_instance -u admin -H 192.168.1.100 -p 31777
```

#### 2. instance-backup - 备份数据库实例

```shell
./bin/Akiba instance-backup -i <实例名> -t <类型> -u <用户> [其他选项]
```

**必需参数：**
- `-i`/`--instance <实例名>`：要备份的实例名称
- `-t`/`--type <类型>`：备份类型，必须为 `full`（完整备份）或 `incr`（增量备份）
- `-u`/`--user <用户名>`：Akiba 数据库用户名

**可选参数：**
- `-a`/`--alias <别名>`：备份的别名（用于在配置文件中引用）
- `-d`/`--description <描述>`：备份的描述信息
- `-P`/`--password`：Akiba 用户密码。如不指定，将在命令行交互提示输入
- `-H`/`--host <主机>`：数据库守护程序主机地址，默认为 `127.0.0.1`
- `-p`/`--port <端口>`：数据库守护程序端口，默认为 `31777`
- `-h`/`--help`：显示帮助信息并退出

**示例：**
```shell
# 对 test_instance 进行完整备份，别名为 backup_2025
./bin/Akiba instance-backup -i test_instance -t full -u admin -a backup_2025

# 对 my_instance 进行增量备份，带描述
./bin/Akiba instance-backup -i my_instance -t incr -u admin-d "Daily incremental backup"
```

#### 3. instance-restore - 从备份恢复数据库实例

```shell
./bin/Akiba instance-restore -n <新实例名> -l <备份标签> -u <用户> [其他选项]
```

**必需参数：**
- `-n`/`--name <实例名>`：要创建的恢复目标实例名称
- `-l`/`--label <标签>`：备份的标签或别名（来自 instance-backup 的 `-a` 参数）
- `-u`/`--user <用户名>`：Akiba 数据库用户名

**可选参数：**
- `-P`/`--password`：Akiba 用户密码。如不指定，将在命令行交互提示输入
- `-H`/`--host <主机>`：数据库守护程序主机地址，默认为 `127.0.0.1`
- `-p`/`--port <端口>`：数据库守护程序端口，默认为 `31777`
- `-h`/`--help`：显示帮助信息并退出

**示例：**
```shell
# 从 backup_2025 备份恢复到新实例 restored_instance
./bin/Akiba instance-restore -n restored_instance -l backup_2025 -u admin

# 从指定主机恢复
./bin/Akiba instance-restore -n new_instance -l daily_backup -u admin -H 192.168.1.100
```

其他配置文件需要保存到 `src/main/resources/configs`。具体内容请查看 [Usage_guide_zh.md](Usage_guide_zh.md)