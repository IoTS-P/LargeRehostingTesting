# <模块名> <模块主类>

# 分类

- <分类1>
- <分类2>

# 描述

这里用简洁的语言描述本模块的主要功能。

## 数据与模块交互

### 临时数据

- 🅘 `<数据类型> <变量名>`: 前面有🅘的为需要输入的临时数据，在这里说明变量功能。如果变量名是固定的，则两边不添加尖括号，如果不是固定的（一般通过配置文件传入），则两边需要添加尖括号，变量名填写`getTaskData`实际传入的参数，如`conf.somedata`。如果需要交互的是数据库内的数据，变量名一般固定，格式为`表名.列名`，如`some_table.some_column`。
- 🅞 `<数据类型> <变量名>`: 前面有🅞的为需要输出的临时结构，在这里说明变量功能。
- 🅘🅞 (可选) `<数据类型> <变量名>`: 前面有🅘🅞的为需要输入后再输出的临时结构，可选表示本模块可能不使用这个变量，具体在什么情况下不使用需要根据模块自身逻辑确定。

### 模块依赖

- 🔴 `org.someorg.somemodule.somepath.ClassName1`: 依赖的模块中的主类，以及该依赖的功能。
- 🔴 `org.someorg.somemodule.somepath.ClassName2`: 依赖的模块中的主类，以及该依赖的功能。

### 配置文件

配置文件类：`org.someorg.somemodule.somepath.SomeConfig`

```json
{
  "some_config_key": "some_config_value", // 描述这个配置项
  "some_config_key2": 42                  // 描述这个配置项
}
```

### 提供的 API

- 🟠 `<方法签名>`: 方法的说明。
- 🟠 `<方法签名>`: 方法的说明。

### 数据表列

- 🟢 `<column name> <column type>`: 本模块将输出到数据库的数据
- 🟢 `<column name> <column type>`: 本模块将输出到数据库的数据
- 🟢 `<column name> <column type>`: 本模块将输出到数据库的数据
- 🟣 `<view name>`: 本模块会创建的视图及视图功能
  - 🟣 `<column name> <column type>`: 视图列功能
  - 🟣 `<column name> <column type>`: 视图列功能
  - 🟣 `<column name> <column type>`: 视图列功能

## 补充解释

这里可以使用详细的文字说明本模块的功能、运行原理等。

## 备注

这里需要说明使用本模块的注意事项。