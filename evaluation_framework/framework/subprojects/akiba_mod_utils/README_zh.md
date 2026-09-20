**注意：请不要单独 clone 这个仓库，请使用下面的命令克隆整个 Akiba 项目：**

```shell
git clone https://github.com/IoTS-P/Akiba.git
cd Akiba
git submodule update --init --recursive
```

# Akiba 实用模块 ( Akiba Mod Utils )

Akiba 实用模块中包含多个类与方法，封装 Ghidra 的不同部分，可在一定程度上简化模块开发。构建可得到 amod-AkibaUtils-<版本号>.jar，将该 JAR 文件放入模块开发项目的 `modules` 目录中，即可为所有待开发模块引入 AkibaUtils。

实用模块包含以下几个方面的类与函数封装：

- Ghidra 反汇编对象（汇编代码、函数、P-code、内存、字符串）高级搜索与匹配
- Ghidra ELF 格式便捷操作
- Ghidra 仿真器简易封装
- Ghidra 函数相关操作封装
- Ghidra 高级函数分析封装
- 外部进程调用（Python 等）

该模块预计将不断扩充，以支持更多功能。

## 构建

你需要克隆 `Akiba` 主仓库后拉取所有子仓库，随后使用 Gradle 构建：

```shell
./gradlew akiba_mod_utils:moduleJar-AkibaUtils
```

构建得到的文件为 `build/libs/amod-AkibaUtils-<version>.jar`

## 文档

Akiba Mod Utils 使用 Dokka 构建文档，使用下面的命令可在本地开放文档 Web 端口（默认为 63342 端口）：

```shell
./gradlew akiba_mod_utils:dokkaGenerateHtml
# http://localhost:63342/Akiba/subprojects/akiba_mod_utils/build/docs/html/
```