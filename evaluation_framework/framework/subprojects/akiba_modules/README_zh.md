**注意：请不要单独 clone 这个仓库，请使用下面的命令克隆整个 Akiba 项目：**

```shell
git clone https://github.com/IoTS-P/Akiba.git
cd Akiba
git submodule update --init --recursive
```

# Akiba 官方功能模块 ( Akiba Modules )

Akiba 最初是用于固件大规模分析，目前，官方功能模块中包含多个固件分析模块，以及与多个工具的对接模块。

**🔴 注意：本仓库下的所有模块暂处于闭源状态，请勿开源。**

使用 Gradle 命令可构建所有模块，目前 build.gradle.kts 存在 bug，若发现有部分模块未能构建成功，请尝试多次构建，一般在构建 6-7 次时，所有模块均可构建成功。若多次构建后问题仍未解决，请提出 issue 或 PR 说明细节及可能的解决方案。

```shell
# 在 Akiba 主仓库下运行
./gradlew akiba_mod_utils:moduleJar-AkibaUtils
# 将 AkibaUtils 构建得到的模块 JAR 文件移动到 akiba_modules 中的 modules 目录，使这里的所有模块都可以依赖 AkibaUtils
mv subprojects/akiba_mod_utils/build/libs/amod-AkibaUtils-*.jar subprojects/akiba_modules/modules
./gradlew akiba_modules:moduleJar-ALL
```

构建得到的所有模块 JAR 文件位于 `build/libs`。