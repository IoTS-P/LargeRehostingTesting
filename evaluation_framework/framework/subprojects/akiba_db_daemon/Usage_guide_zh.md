# Akiba 数据库守护程序 （Akiba Database Daemon）

## 1. 配置文件

### `config.json` 主配置文件

**你需要填充主配置文件中必须填充的一些配置，以使 Akiba Database Daemon 正常运行。**

```text
{
  // 控制台和文件日志的级别
  "consoleLogLevel": "INFO",
  "fileLogLevel": "DEBUG",
  // 数据库用户名
  "dbUserName": "test",
  // 数据库密码
  "dbPassword": "test123",
  // 数据库名
  "dbName": "akiba"
}
```

### 数据库文件

可以使用 `subprojects/akiba_db_daemon/src/main/resources/database_init.sql` 创建一个可以直接使用的数据库模板。所有字段的含义与功能在数据库脚本文件中的注释中定义。在默认编译的数据库守护程序压缩包中，`subprojects/akiba_db_daemon/src/main/resources/initialize_pg_local.sh` 脚本将在指定数据库进行初始化。在启动数据库守护程序时，默认会以配置中的 `dbUserName` 作为数据库用户名，`dbName` 作为数据库名，`dbPassword` 作为数据库密码进行数据库连接与初始化尝试。

数据库初始化会创建 4 个数据表、1 个视图、1 个索引：

- `binaries`: 原始二进制文件信息
- `processed_binaries`: 处理过的二进制文件信息
- `results`: 全局任务数据
- `db_backup_tree`: 数据库备份树（目前还未实现）
- `using_binaries`: 实际使用的二进制文件信息视图（即如果一个文件在 `processed_binaries` 中存在，则使用处理后的文件，否则使用原始文件）

在 `subprojects/akiba_db_daemon/src/main/resources/database_init.sql` 中，有上述 5 个表结构的定义。去除格式检查后，对应的中文注释如下：

```postgresql
-- binaries: 原始二进制文件信息
CREATE TABLE IF NOT EXISTS binaries (
    id              INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,   -- 二进制文件 ID，唯一标识符
    original_path   TEXT NOT NULL,          -- 导入时的文件原路径，所有二进制文件都将在导入后被重命名并统一复制到指定目录
    checksum        TEXT,                   -- 原文件的 MD5 摘要值
    size            INTEGER,                -- 原文件大小
    arch            TEXT,                   -- 导入时获取的二进制文件的处理器架构
    format          TEXT,                   -- 二进制文件格式，如 ELF，EXE，PE，Mach-O 等
    compiler_spec   TEXT                    -- 编译器规范，如 eabi，Visual Studio 等
);

-- processed_binaries: 处理过的二进制文件信息。一些二进制文件会被处理（目前仅包含针对文件的大块\x00减除），因此需要保存这些文件。
-- 注意：不是所有的二进制文件都会被处理，因此该表可能不包含所有 ID
CREATE TABLE IF NOT EXISTS processed_binaries (
    id              INTEGER REFERENCES binaries (id)
                            ON DELETE CASCADE
                            ON UPDATE CASCADE,
    original_path   TEXT,                   -- 处理后的文件原路径
    checksum        TEXT,                   -- 处理后文件的 MD5 摘要值
    size            INTEGER,                -- 处理后的文件大小
    load_properties JSONB,                  -- 文件片段信息，可与原文件进行对应。在处理过程中，大于 0x10000 字节的连续 \x00 会被减除
    arch            TEXT,                   -- 处理后的二进制文件的处理器架构
    format          TEXT,                   -- 二进制文件格式，如 ELF，EXE，PE，Mach-O 等
    compiler_spec   TEXT                    -- 编译器规范，如 eabi，Visual Studio 等
);

-- 全局分析结果
CREATE TABLE IF NOT EXISTS results (
    id              INTEGER REFERENCES binaries (id)
                            ON DELETE CASCADE
                            ON UPDATE CASCADE,
    err_msg         TEXT,          -- 全局错误信息
    global_data     TEXT,          -- 在分析时生成的全局数据
    FOREIGN KEY(id) REFERENCES binaries (id)
       ON DELETE CASCADE
       ON UPDATE CASCADE
);

-- 创建索引。由于`id` 在 `results` 中是唯一的，因此我们可以为它创建索引以提高 `JOIN` 命令的性能。
CREATE INDEX IF NOT EXISTS idx_results_id ON results(id);

-- using_binaries: 获取实际使用的二进制文件信息视图。如果一个文件在 `processed_binaries` 中存在，则使用处理后的文件，否则使用原始文件
CREATE OR REPLACE VIEW using_binaries AS
SELECT COALESCE(b.id, a.id) AS id,
       COALESCE(b.original_path, a.original_path) AS original_path,
       COALESCE(b.size, a.size) AS size,
       COALESCE(b.arch, a.arch) AS arch,
       COALESCE(b.format, a.format) AS format,
       COALESCE(b.compiler_spec, a.compiler_spec) AS compiler_spec,
       COALESCE(b.checksum, a.checksum) AS checksum,
       b.load_properties AS load_properties
FROM binaries a
         LEFT JOIN processed_binaries b
                   ON a.id = b.id;

-- 创建数据库备份树表（该表目前未实现，保留为空）
CREATE TABLE IF NOT EXISTS db_backup_tree (
    backup_id      UUID PRIMARY KEY,                                    -- 快照的独有 ID
    parent_id      UUID REFERENCES db_backup_tree(backup_id),           -- 父快照的 ID
    alias          TEXT UNIQUE,                                         -- 快照的别名，用于在配置文件中指定
    lsn_start      pg_lsn NOT NULL,                                     -- 快照的起始日志序列号
    lsn_end        pg_lsn NOT NULL,                                     -- 快照的结束日志序列号
    backup_type    TEXT CHECK (backup_type IN ('BASE', 'WAL_DELTA')),   -- 快照类型，是根级快照还是增量快照
    physical_path  TEXT NOT NULL,                                       -- 快照文件的物理路径
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()                   -- 快照的创建时间
);
```

## 2. HTTP 路径接口访问

Akiba 数据库守护程序默认开启 0.0.0.0:31777 的 HTTP 服务。目前所有数据库操作均通过 POST 方法或 WebSocket 方法访问，请求参数通过 JSON 格式发送。在 Akiba Framework 中，已经完成对所有路径的接口实现。

### 2.1 认证与授权

大部分接口需要在 HTTP Header 中添加 `Authorization` 字段，该字段的值为登录时获取的 UUID Token。

**登录流程：**
1. 调用 `/instance/login` 接口获取 Token
2. 在后续请求的 Header 中添加 `Authorization: <token>`
3. 调用 `/instance/logout` 退出登录

---

### 2.2 查询接口 (Queries)

#### GET /test
测试接口，用于检查服务器是否正常运行。

**响应示例：**
```
The server works well
```

#### POST /test
测试接口，用于检查服务器是否正常运行（POST 方式）。

**响应示例：**
```
The server works well
```

#### POST /get/id/sql
通过 SQL 语句获取二进制文件 ID 列表。

**注意：** 由于安全原因，该接口默认禁用。

**请求参数：**
```json
{
  "sql": "WHERE u.ID < 100"
}
```

**响应：** ID 列表
```json
[1, 2, 3, ...]
```

#### POST /get/metadata
获取指定二进制文件的元数据。

**请求参数：** 二进制文件 ID
```json
123
```

**响应：** 元数据对象
```json
{
  "id": 123,
  "originalPath": "/path/to/binary",
  "arch": "ARM",
  "format": "ELF",
  "compilerSpec": "eabi",
  "checksum": "md5_hash",
  "processedPath": "/path/to/processed",
  "processedChecksum": "md5_hash",
  "loadProperties": [
    {
      "oldOffset": 0,
      "newOffset": 0,
      "length": 65536
    }
  ]
}
```

#### POST /get/module_data
从模块表中获取模块数据。

**请求参数：**
```json
{
  "tableName": "test_module_results",
  "id": 123,
  "columns": ["column1", "column2"]  // 可选，不填则查询所有列
}
```

**响应：**
```json
{
  "results": [
    {"name": "column1", "type": "int4", "value": 42},
    {"name": "column2", "type": "bool", "value": true}
  ]
}
```

---

### 2.3 插入接口 (Insertions)

#### POST /insert/check_md5
检查 MD5 值是否已存在于数据库中。

**请求参数：** MD5 字符串
```json
"d41d8cd98f00b204e9800998ecf8427e"
```

**响应：** 布尔值
```json
true
```

#### POST /insert/insert_bin
插入二进制文件信息到数据库。

**请求参数：**
```json
{
  "originalPath": "/path/to/binary",
  "processedPath": "/path/to/processed",  // 可选
  "checksum": "md5_hash",
  "processedChecksum": "md5_hash",        // 可选
  "size": 1024,
  "processedSize": 512,                   // 可选
  "loadProperties": "[{\"oldOffset\":0,\"newOffset\":0,\"length\":1024}]",  // 可选
  "arch": "ARM",
  "format": "ELF",
  "compilerSpec": "eabi"
}
```

**响应：** 插入的文件 ID
```json
123
```

---

### 2.4 模块管理接口 (Modules)

#### POST /module/create_table
创建模块专用数据表。

**请求参数：**
```json
{
  "name": "test_module_results",
  "columns": {
    "function_number": "integer",
    "has_flag": "boolean",
    "result_text": "text"
  }
}
```

**支持的数据类型：**
- `integer`, `bigint`
- `double precision`
- `text`
- `timestamptz`
- `interval`
- `boolean`
- `jsonb`
- `bytea`

**保留列名（不可使用）：**
- `id`, `start_timestamp`, `finish_timestamp`, `execute_time`, `err_msg`

**响应：** HTTP 状态码

#### POST /module/create_view
创建数据库视图。

**请求参数：**
```json
{
  "viewName": "my_view",
  "viewSQL": "SELECT * FROM binaries WHERE arch = 'ARM'",
  "overwrite": false
}
```

**响应：** HTTP 状态码

#### POST /module/lock_table
锁定指定表，防止并发访问冲突。

**请求参数：**
```json
{
  "tableName": "test_module_results"
}
```

**响应：** HTTP 状态码

#### POST /module/unlock_table
解锁已锁定的表。

**请求参数：**
```json
{
  "tableName": "test_module_results"
}
```

**响应：** HTTP 状态码

#### POST /module/update
更新模块数据表中的数据。（注意：对于一个文件 id，必须首先发起 `/module/start` 请求启动该模块任务后，才能进行更新操作）

**请求参数：**
```json
{
  "tableName": "test_module_results",
  "id": 123,
  "data": {
    "function_number": 42,
    "has_flag": true,
    "result_text": "success"
  }
}
```

**响应：** HTTP 状态码

#### POST /module/start
标记任务开始执行。

**请求参数：**
```json
{
  "tableName": "test_module_results",
  "id": 123
}
```

**功能：** 设置 `start_timestamp` 为当前时间，清除 `finish_timestamp`、`execute_time` 和 `err_msg`

**响应：** HTTP 状态码

#### POST /module/finish
标记任务执行完成。

**请求参数：**
```json
{
  "tableName": "test_module_results",
  "id": 123
}
```

**功能：** 设置 `finish_timestamp` 为当前时间，计算 `execute_time = finish_timestamp - start_timestamp`

**响应：** HTTP 状态码

---

### 2.5 控制接口 (Controls)

#### POST /control/enable
启用指定的 HTTP 路由。

**请求参数：**
```json
{
  "route": "/get/id/sql"
}
```

**响应：** HTTP 状态码

#### POST /control/disable
禁用指定的 HTTP 路由。

**请求参数：**
```json
{
  "route": "/get/id/sql"
}
```

**响应：** HTTP 状态码

#### POST /heartbeat
心跳接口，用于保持会话活跃。

**响应：** HTTP 204 No Content

---

### 2.6 实例管理接口 (PGInstances)

#### POST /instance/login
客户端登录，获取认证 Token。

**请求参数：**
```json
{
  "username": "test",
  "password": "test123"
}
```

**响应：**
```json
{
  "token": "550e8400-e29b-41d4-a716-446655440000"
}
```

#### POST /instance/logout
客户端登出，释放资源。

**响应：** HTTP 状态码

#### WebSocket /ws/instance/create
创建新的 PostgreSQL 实例（WebSocket接口）。

**请求消息：**
```json
{
  "token": "550e8400-e29b-41d4-a716-446655440000",
  "instanceName": "my_instance"
}
```

**响应消息：**
```json
{
  "msg": "Instance my_instance created"
}
```

#### POST /instance/connect
连接到指定的 PostgreSQL 实例。如果实例未启动，会自动启动。

**请求参数：**
```json
{
  "instanceName": "my_instance"
}
```

**响应：** HTTP 状态码

#### POST /instance/disconnect
断开与 PostgreSQL 实例的连接（不关闭实例）。

**请求参数：**
```json
{
  "instanceName": "my_instance"
}
```

**响应：** HTTP 状态码

#### POST /instance/shutdown
关闭 PostgreSQL 实例。

**请求参数：**
```json
{
  "instanceName": "my_instance"
}
```

**响应：** HTTP 状态码

#### POST /instance/delete
删除 PostgreSQL 实例。

**请求参数：**
```json
{
  "instanceName": "my_instance"
}
```

**响应：** HTTP 状态码

---

### 2.7 备份管理接口 (Backups)

#### WebSocket /ws/backup/create
创建数据库备份（WebSocket接口）。

**请求消息：**
```json
{
  "isFull": true,
  "token": "550e8400-e29b-41d4-a716-446655440000",
  "instance": "my_instance",
  "alias": "backup_2025",        // 可选
  "description": "Full backup"   // 可选
}
```

**响应消息：**
```json
{
  "label": "backup_label_123"
}
```

#### POST /backup/peek
查看实例的备份树信息。

**请求参数：** 实例名称
```json
"my_instance"
```

**响应：** 备份树的 JSON 表示

#### WebSocket /ws/backup/restore
从备份恢复数据库（WebSocket接口）。

**请求消息：**
```json
{
  "token": "550e8400-e29b-41d4-a716-446655440000",
  "instance": "my_instance",
  "aliasOrLabel": "backup_2025"
}
```

**响应消息：**
```json
{
  "msg": "Backup restoration completed"
}
```

---

### 2.8 错误码说明

| HTTP 状态码 | 含义 | 常见场景 |
|------------|------|----------|
| 200 OK | 请求成功 | 操作成功完成 |
| 204 No Content | 请求成功但无返回内容 | 心跳接口 |
| 400 Bad Request | 请求参数错误 | 参数格式不正确、Token 无效 |
| 401 Unauthorized | 未授权 | 缺少 Authorization 头 |
| 404 Not Found| 资源不存在 | 表不存在、实例不存在 |
| 409 Conflict | 资源冲突 | 表已锁定、实例已被占用 |
| 423 Locked | 资源被锁定 | 表被其他客户端锁定 |
| 500 Internal Server Error | 服务器内部错误 | 数据库操作失败 |

---

### 2.9 使用示例

#### 完整流程示例

```bash
# 1. 登录获取 TOKEN
TOKEN=$(curl -X POST http://localhost:31777/instance/login \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"test123"}' \
  | jq -r '.token')

# 2. 连接实例
curl -X POST http://localhost:31777/instance/connect \
  -H "Content-Type: application/json" \
  -H "Authorization: $TOKEN" \
  -d '{"instanceName":"my_instance"}'

# 3. 锁定表
curl -X POST http://localhost:31777/module/lock_table\
  -H "Content-Type: application/json" \
  -H "Authorization: $TOKEN" \
  -d '{"tableName":"test_module_results"}'

# 4. 创建数据表
curl -X POST http://localhost:31777/module/create_table\
  -H "Content-Type: application/json" \
  -H "Authorization: $TOKEN" \
  -d '{"name":"test_module_results","columns":{"result":"integer"}}'

# 5. 开始任务
curl -X POST http://localhost:31777/module/start \
  -H "Content-Type: application/json" \
  -H "Authorization: $TOKEN" \
  -d '{"tableName":"test_module_results","id":1}'

# 6. 更新数据
curl -X POST http://localhost:31777/module/update \
  -H "Content-Type: application/json" \
  -H "Authorization: $TOKEN" \
  -d '{"tableName":"test_module_results","id":1,"data":{"result":42}}'

# 7. 完成任务
curl -X POST http://localhost:31777/module/finish \
  -H "Content-Type: application/json" \
  -H "Authorization: $TOKEN" \
  -d '{"tableName":"test_module_results","id":1}'

# 8. 解锁表
curl -X POST http://localhost:31777/module/unlock_table \
  -H "Content-Type: application/json" \
  -H "Authorization: $TOKEN" \
  -d '{"tableName":"test_module_results"}'

# 9. 断开连接
curl -X POST http://localhost:31777/instance/disconnect \
  -H "Content-Type: application/json" \
  -H "Authorization: $TOKEN" \
  -d '{"instanceName":"my_instance"}'

# 10. 登出
curl -X POST http://localhost:31777/instance/logout \
  -H "Authorization: $TOKEN"
```

---

**注意事项：**
1. 所有需要认证的接口必须在 HTTP Header 中包含有效的 `Authorization` 字段
2. WebSocket接口需要在建立连接后首先发送认证 Token
3. 表操作前必须先锁定表，操作完成后必须解锁
4. 实例操作需要确保实例的所有权正确
5. 备份操作耗时较长，建议使用 WebSocket接口以获得更好的进度反馈