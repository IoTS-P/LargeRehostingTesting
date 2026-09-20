**注意：请不要单独 clone 这个仓库，请使用下面的命令克隆整个 Akiba 项目：**

```shell
git clone https://github.com/IoTS-P/Akiba.git
cd Akiba
git submodule update --init --recursive
```

# Akiba 数据库守护程序

Akiba 数据库守护程序是一个轻量级 Web 服务程序，可在本地构建多 PostgreSQL 数据库实例并与 Akiba 框架进行交互与数据管理。

考虑到 Akiba 可能需要处理数量庞大的二进制文件，在分析过程中可能会产生大量数据，因此一个鲁棒的数据管理系统是必须的。

Akiba 使用 PostgreSQL 存储数据，支持多数据库实例；另外使用 pgbackrest 进行数据库备份与恢复。Akiba 通过开放 HTTP 接口与 Websocket 接口与外界进行数据交互。

可通过下面的命令构建 Akiba 数据库守护程序的 Docker 镜像：

```shell
docker build -t akiba-db-daemon .
```

数据库守护程序的 Web 接口端口默认为 31777，目前支持以下几类操作，详细的参数说明参见[使用说明](Usage_guide_zh.md)：

- 用户认证（目前功能尚未完成，无密码校验）
- 文件数据插入
- 任务模块管理以及数据插入、查询、更新
- 数据库实例管理
- 数据库备份与恢复（相关测试还不完善，可能存在 bug，谨慎使用）