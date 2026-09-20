# Akiba 模块开发指南

Akiba 模块是运行在 Akiba 框架上的可拆卸组件，由于 Akiba 使用 Kotlin 编写，因此 Akiba Module 目前仅支持使用 Kotlin 编写（使用 Java 编写在理论上可行，但并未经过实际验证）。

Akiba 模块支持：

- Maven 库导入及使用：在 build.gradle.kts 中添加需要的 Maven 依赖即可使模块能够访问任意 Maven 软件包。
- 依赖其他 Akiba 模块：Akiba 模块可依赖其他模块（不可循环依赖），在 build.gradle.kts 中可进行配置。
- 数据交互：Akiba 提供数据交互接口，可用于保存模块输出的数据到 Akiba 数据库（基于 PostgreSQL）。
- 与其他模块数据交互：Akiba 模块在运行过程中可生成不保存到数据库的临时数据，临时数据使用 Map 保存，后续运行的模块可通过 Key 访问前序模块产生的临时数据。
- 批量多线程运行：Akiba 支持对大规模二进制文件进行相同流水线操作，不同文件的模块数据互相隔离，但不同文件的处理模块并行运行，相同文件的处理模块串行运行。

## 1. Akiba 模块主类

### 1.1 Akiba 模块主类定义

Akiba 模块主类必须为 `AkibaModule`（`org.iotsplab.akiba.module.AkibaModule`）的子类：

```kotlin
abstract class AkibaModule (
    private val configPath: String? = null,
    private val defaultConfig: Any? = null,
    val id: Int = -1,
    protected val program: Program? = null,
    protected val properties: Map<String, String?> = mapOf(),   // deprecated
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = null,
)
```

参数说明：
- `configPath`：模块配置文件路径，该路径用于保存本模块的配置项内容。该路径格式应为`<文件名>@<JSON路径>`，其中 JSON 路径使用 Jackson 格式，即与 UNIX 文件路径类似，如：`config.json@/module_data`表示`config.json`中 JSON 路径为 `/module_data` 的 JSON 对象。你可以将 Akiba 主配置与模块配置写在一个文件中，此时文件名可省略为`@`，如`@@/module_data`。
- `defaultConfig`：模块默认配置项，当模块路径不存在时，模块会使用该配置项。
- `id`：待处理文件 ID，用于标识当前处理的二进制文件，唯一。
- `program`：待处理文件的 Ghidra Program 上下文，所有的逆向分析操作都需要在该上下文中进行。
- `properties`(deprecated)：模块属性，用于保存模块属性数据。
- `consoleLogLevel`：控制台日志级别，默认为`INFO`。
- `fileLogLevel`：文件日志级别，默认为`INFO`。
- `tableName`：与该模块对应的数据表名，即该模块产生的数据最终会保存到哪个数据表。

如果一个模块不需要配置，没有配置类，则继承时可不写`configPath`、`defaultConfig`参数。

对于配置类的类型，需要使用 `@WithConfigClass` 修饰模块主类以指定：

```kotlin
// 所属包：org.iotsplab.akiba.utils
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class WithConfigClass(val clazz: KClass<*>)
```

如果模块类需要自定义序列化/反序列化器，则需要使用 `@WithConfigSerializer` 、`@WithConfigDeserializer` 修饰模块主类以指定，在 Akiba 运行该模块前，会使用指定的反序列化器进行反序列化得到模块配置类对象：

```kotlin
// 所属包：org.iotsplab.akiba.utils
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class WithConfigSerializer(val serializer: KClass<out JsonSerializer<*>>)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class WithConfigDeserializer(val deserializer: KClass<out JsonDeserializer<*>>)
```

### 1.2 Akiba 运行时

在主配置文件中，你可以为每个模块定义其配置项所在位置、控制台日志级别、文件日志级别、数据表名、最大运行时间：

```json
{
  "tasks": [
    {
      "mainClassName": "org.iotsplab.akiba.module.AkibaExample",
      "timeout": 600,
      "consoleLogLevel": "debug",
      "fileLogLevel": "debug",
      "tableName": "example_table"
    }
  ]
}
```

在默认情况下，如果模块运行超时，则该模块会立即被取消，随即运行下一个模块。如果模块在运行时出现异常，则该模块会被判定为失败，且后续模块将不会被运行。

如果一个模块希望在其超时后被判定为失败，可以使用 `@FailOnCancelled` 修饰：

```kotlin
// 所属包：org.iotsplab.akiba.utils
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class FailOnCancelled
```

如果一个模块无论何时都不需要超时限制，则可以使用 `@IgnoreRuntimeTimeout` 修饰：

```kotlin
// 所属包：org.iotsplab.akiba.utils
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class IgnoreRuntimeTimeout
```

如果一个模块不需要运行任何任务，该模块中包含的公开类与方法依然可以被依赖它的其他模块使用，充当工具模块的作用。需要添加 `@PureDependency` 注解类进行修饰。

示例：

```kotlin
@WithTableColumn("strings", "JSONB")
class AkibaExample(
    id: Int,
    program: Program,
    consoleLogLevel: Level = Level.INFO,
    fileLogLevel: Level = Level.INFO,
    tableName: String? = "example_table"
): AkibaModule(
    id = id,
    program = program,
    consoleLogLevel = consoleLogLevel,
    fileLogLevel = fileLogLevel,
    tableName = tableName     // Because we use PostgreSQL to save data, the table name should be small cased
) {
    // ......
}
```

## 2. Akiba 功能代码

Akiba 的功能代码位于 `AkibaModule` 的 `startProcess`，所有模块都会以此为入口开始工作。

在 `AkibaModule` 中，提供了一些实用方法：

```kotlin
// 通过 Key 获取/设置该二进制文件的临时数据。对于`getTaskData`，如果输入的 Key 为 `null`，则返回所有临时数据。
suspend fun getTaskData(key: String?): Any?
suspend fun setTaskData(key: String, value: Any?)

// 获取该二进制文件的基本信息数据（`BinaryMetadata`），该类的定义如下：
suspend fun getMetadata()

@Serializable
data class BinaryMetadata(
    val id: Int,           // 待处理文件 ID
    val originalPath: String,      // 待处理文件原始路径
    val processedPath: String? = null, // 待处理文件经过剪切处理后的路径
    val arch: String? = null,      // 待处理文件的处理器架构
    val format: String? = null,    // 待处理文件的文件格式（ELF、exe等）
    val compilerSpec: String? = null,  // 待处理文件的编译器规范
    val loadProperties: List<ImportManager.FileSegment> = listOf(),    // 待处理文件的导入规则
    val checksum: String,          // 待处理文件的校验和
    val processedChecksum: String? = null  // 待处理文件经过剪切处理后的校验和
)

// 从 Jar 文件中提取文件，在一些情况下，我们可能需要将一些其他文件打包到模块 Jar 包中，可以通过该方法进行提取。
protected fun extractFileInJar(inPath: String, outPath: Path)

// 向数据表更新数据，如果列不存在则会创建，否则会覆盖原有数据。
protected fun updateData(data: Map<String, Any?>)

// 向数据表更新错误信息，如果列不存在则会创建，否则会覆盖原有错误信息。
protected fun updateErr(msg: String)

// 清空错误信息。
protected fun clearErr()
```

## 3. Akiba 模块 API

Akiba 支持模块暴露自己的 API，在一个串行任务流中，先行运行的模块可以暴露自己的 API，由后续运行的模块调用。

暴露 API 可在方法上添加 `@TaskInterface` 注解：

```kotlin
// 所属包：org.iotsplab.akiba.utils
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class TaskInterface
```

调用其他模块的 API 使用下面的方法实现：

```kotlin
// function 指定要调用的函数，args 为参数列表
protected suspend fun callTaskAPI(function: KFunction<*>, vararg args: Any?): Any?
```

注意：在批量串行任务流中，如果有一个类 A 暴露了 API，则对于拥有指定 id 的后续模块类 B，其调用 A 暴露的 API 实际上等同于调用具有相同 id 的 A 实例中的 API，如下例所示：

```kotlin
class A(...): AkibaModule(...) {
    @TaskInterface
    fun getData(): Int { return id + 100 }
}

class B(...): AkibaModule(...) {
    suspend fun startProcess() {
        val data = callTaskAPI(A::getData)
        println(data)
    }
}
```

对于上面定义的两个模块主类，若一次批量任务需要运行 `id` 为 1 和 2 的两个二进制文件，且先运行 A 再运行 B，则在 id = 1 的 B 实例中，输出的值只能是 101，而 id = 2 的 B 输出的值只能是 102。

## 4. Akiba 数据库

### 4.1 Akiba 数据库概述

Akiba 基于 PostgreSQL 实现数据存储，在实现上，Akiba 使用一个数据库守护程序启动 HTTP 服务，该服务会提供若干接口用于数据操作，包括数据读写、PostgreSQL 实例创建、数据备份等。所有接口的文档参见 [Akiba DB Daemon Usage Guide](https://github.com/IoTS-P/Akiba-DB-Daemon/blob/master/Usage_guide_zh.md)。

在模块运行层，用户只需在主配置文件指定 Akiba 用户名、密码及使用的实例名，在 Akiba 运行时，模块即可与指定的数据库实例交互。**目前，Akiba 数据库守护程序的身份认证功能还未完全实现，在给定的 Docker 服务中，请统一使用用户名 akiba 和密码 akiba 完成所有功能，身份认证功能将尽快开发完成。**

目前，Akiba 提供了三个子命令，可用于操作数据库实例：

- `instance-create`：数据库实例创建
- `instance-backup`：数据库实例备份
- `instance-restore`：数据库实例恢复

具体参数参见 [Akiba Framework README](https://github.com/IoTS-P/Akiba-Framework/blob/master/README_zh.md)。

### 4.2 Akiba 数据库实例结构

数据库初始化会创建 4 个数据表、1 个视图、1 个索引：

- `binaries`: 原始二进制文件信息
- `processed_binaries`: 处理过的二进制文件信息
- `results`: 全局任务数据
- `db_backup_tree`: 数据库备份树（目前还未实现）
- `using_binaries`: 实际使用的二进制文件信息视图（即如果一个文件在 `processed_binaries` 中存在，则使用处理后的文件，否则使用原始文件）

具体定义参见 [Akiba DB Daemon Usage Guide](https://github.com/IoTS-P/Akiba-DB-Daemon/blob/master/Usage_guide_zh.md)。

### 4.3 Akiba 模块数据表结构

在模块类定义中，可以自定义数据表列，在默认情况下，属于一个模块的数据表会拥有 5 列：

- `id`：二进制文件 ID，表明该行数据属于哪一个二进制文件
- `start_timestamp`：最近一次运行该模块的开始时间
- `finish_timestamp`：最近一次运行该模块的结束时间
- `execute_time`：最近一次运行该模块所花费的时间
- `err_msg`：最近一次模块运行过程中出现的错误信息（如果前序模块运行过程中出现错误，而后续模块重新运行成功，则该列会清空错误信息）

除上述 5 列外，模块可以自定义其他数据列，使用注解类 `@WithTableColumn` 实现：

```kotlin
// 所属包：org.iotsplab.akiba.utils
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
annotation class WithTableColumn(val name: String, val type: String)
```

目前支持的数据类型有：
- `integer`, `bigint`
- `double precision`
- `text`
- `timestamptz`
- `interval`
- `boolean`
- `jsonb`
- `bytea`

该注解类可以重复使用，即一个模块可以定义多个数据列。在下面的示例中，该模块所属的数据表一共会拥有 8 列，即模块定义的 5 列和 3 个自定义列：

```kotlin
@WithTableColumn(name = "my_column1", type = "text")
@WithTableColumn(name = "my_column2", type = "bytea")
@WithTableColumn(name = "program_string_count", type = "integer")
class MyModule(): AkibaModule() {
    //...
}
```

如果一个模块需要创建数据视图，则可以使用注解类 `@WithView` 实现：

```kotlin
// 所属包：org.iotsplab.akiba.utils
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
annotation class WithView(val viewName: String, val creationSql: String)
```

该注解类可以重复使用，即一个模块可以定义多个数据视图。**注意：由于该注解类传入 SQL 语句作为数据视图创建语句，因此该注解类是不安全的，使用其他人开发的模块时需要注意相关安全问题。**

如果一个模块不需要创建数据表，连默认的 5 列数据都不需要，则使用注解类 `@DoNotCreateTable` 实现。注意：Akiba 一旦发现该模块不需要创建数据表，则该模块将不会创建数据表，也不会创建数据视图，即使其使用了 `@WithTableColumn` 或 `@WithView`。

```kotlin
// 所属包：org.iotsplab.akiba.utils
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class DoNotCreateTable
```

## 5. 二进制文件剪切

二进制文件剪切是 Akiba 针对固件文件所做的优化，用于提高处理效率。在很多情况下，固件文件会包含大段全为 `'\x00'` 的字节段落，在一些情况下，这种大段字节段落甚至能够达到 100MB 以上，会严重影响部分工具及分析进程的效率，且会增加不必要的内存消耗。因此，Akiba 提供了二进制文件剪切功能，该功能会自动将二进制文件剪切为多个小段落，并将小段落与原先文件中的偏移一一对应，保存到数据库 `binaries` 表的 `load_properties` 列中。下面的示例表示，我们剪切后的二进制文件仅有 1024 字节长，剪切后的文件偏移为 0 的长度为 1024 的段落，在原文件中的首部偏移为 1000000。（原先位于原文件第 1000000 个字节开始，长度为 1024 的字节段落现在从新文件第 0 个字节开始）

```json
{"oldOffset": 1000000, "newOffset": 0, "length": 1024}
```

Akiba 只有在无法识别文件类型时才会尝试进行剪切操作，对于包含明确格式的文件（如ELF、EXE等），剪切操作将不会被执行。