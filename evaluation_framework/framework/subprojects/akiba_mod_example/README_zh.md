**注意：请不要单独 clone 这个仓库，请使用下面的命令克隆整个 Akiba 项目：**

```shell
git clone https://github.com/IoTS-P/Akiba.git
cd Akiba
git submodule update --init --recursive
```

# Akiba 示例模块 ( Akiba Mod Example )

Akiba 示例模块包含一个简单的模块，用于获取一个二进制文件中的所有字符串值，并将这些值以 JSON 数组格式保存到数据库。

你可以在该模块基础上进行开发，替换 `AkibaExample.kt` 中的原有功能代码，并开发新的功能，而无需大幅修改 `build.gradle.kts` 等用于构建或项目配置的其他文件。

该模块依赖 `AkibaUtils` 模块。[链接](https://github.com/IoTS-P/Akiba-Mod-Utils)，在该模块仓库中，包含已经打包完成的 `AkibaUtils` Jar 文件（位于 modules）。

## 构建与测试运行

你需要克隆 `Akiba` 主仓库后拉取所有子仓库，随后使用 Gradle 构建：

```shell
./gradlew akiba_mod_example:moduleJar-AkibaModExample
```

构建得到的文件为 `build/libs/amod-AkibaModExample-<version>.jar`

构建好的 Jar 文件已经保存到 `Akiba` 主仓库的 `dockerfile-needed` 目录中，使用下面的命令启动 Docker 服务后，运行测试脚本即可导入一个示例二进制文件并启动本示例模块：

```shell
# 在 Akiba 主仓库根目录运行
docker-compose up --build

# 下面的命令使用另一个终端运行
docker exec -it akiba_app_1 /bin/bash
../binaries/test_run.sh
```

在一切正常的情况下，测试脚本将会首先启动一次 Akiba Framework 并将示例文件的基本信息导入到本地数据库示例，随后再次启动一次 Akiba Framework 并运行该示例模块。该示例模块将在数据库中创建一个名为 `example_table` 的表，并写入对应的数据。最后，`test_run.sh` 将会运行 `psql` 命令检查数据是否存在。正确数据示例如下所示：

```text
 id |              original_path              |             checksum             | size  |       arch        |               format                | compiler_spec 
----+-----------------------------------------+----------------------------------+-------+-------------------+-------------------------------------+---------------
  1 | /home/akiba/binaries/import_example.elf | 06beddb7382b86fa3a96c12607c98838 | 16536 | x86:LE:64:default | Executable and Linking Format (ELF) | unknown
(1 row)

 id |        start_timestamp        |       finish_timestamp        |  execute_time   | err_msg |                 strings                  
----+-------------------------------+-------------------------------+-----------------+---------+------------------------------------------
  1 | 2026-03-21 07:20:15.090135+00 | 2026-03-21 07:20:16.747139+00 | 00:00:01.657004 |         | ["please input", "ok,bye!!!", "/bin/sh"]
(1 row)
```

其中第一次查询结果用于检查示例二进制文件是否被成功导入，第二次查询结果用于检查本示例模块是否正常运行。