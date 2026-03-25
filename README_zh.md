# IDE Database MCP

本仓库包含一个 JetBrains IDE 插件，旨在通过本地 MCP 兼容服务将 IDE 管理的 Database 数据源暴露给外部工具（默认英文版在
README.md）。
开发说明：本项目 95%+ 的代码由 GitHub Copilot、Claude 与 Codex 生成，并由人工审阅和整合。

---

## 模块结构

- `ide-database-mcp-base`：`223+` 兼容基础模块，包含本地 HTTP MCP 服务、设置页和数据库桥接能力。
- `ide-database-mcp-mcpserver-ext`：高版本可选扩展模块，在存在 `com.intellij.mcpServer` 时注册 `mcpToolset`。
- 根工程：打包聚合模块，负责将以上两个模块组装为一个插件产物。

当 IDE 内存在官方 MCP Server 插件（`com.intellij.mcpServer`）时，可选 `mcpToolset` 会自动加载；当该插件不存在时，本插件仍可通过内置
`/mcp` 本地服务正常运行。

## 源起与目的

该插件的目标是让 IDE 内管理的 Database 数据源可以被外部进程/工具通过轻量 HTTP/JSON-RPC MCP 兼容桥接访问，方便自动化工具或代理调用
IDE 内的数据库能力。

项目基于 IntelliJ 平台（since build 223+）实现，适用于已提供并启用 `com.intellij.database` 插件的 JetBrains IDE。

## 主要功能

- 将 Database 能力封装为 MCP 工具：
    - `database_list_datasources` — 列出已配置数据源。
    - `database_list_databases` — 在可获取元数据时列出 catalog/schema。
    - `database_execute_sql_query` — SQL 查询用途工具。
    - `database_execute_sql_dml` — SQL 数据变更用途工具。
    - `database_execute_sql_ddl` — SQL 结构变更用途工具。
    - `database_execute_nosql_query` — NoSQL 查询用途工具。
    - `database_execute_nosql_write_delete` — NoSQL 写入/删除用途工具。
- 执行约束以工具说明和参数说明为准：调用方需自行选择正确工具；服务端不再做 SQL/NoSQL 关键字判断或写死 DB 类型拦截。
- 插件设置页面：`Settings | Tools | Database MCP`
- 支持本地 MCP 服务自动启动
- 支持数据源范围控制：`GLOBAL` / `PROJECT` / `ALL`
- 多窗口安全启动：基于应用级状态与锁实现

## 运行环境要求

- 基于 IntelliJ Platform 的 JetBrains IDE（`223+`）
- 目标 IDE 中已安装并启用 `com.intellij.database` 插件
- Java `17`（基础模块）；仅可选 `mcpserver` 扩展模块在编译阶段使用 Java `21`
- Gradle `9.4.1`（仓库包含 wrapper）

## 插件安装

可选择以下任一安装方式。

### 方案 A：从 JetBrains Marketplace 安装（推荐）

1. 打开目标 IDE。
2. 进入 `Settings/Preferences | Plugins | Marketplace`。
3. 搜索 `IDE Database MCP`。
4. 点击 `Install` 并重启 IDE。

### 方案 B：从本地 ZIP 安装

1. 先构建插件 ZIP（或直接使用 `build/distributions/` 下已有 ZIP）。
2. 在目标 IDE 打开 `Settings/Preferences | Plugins`。
3. 点击齿轮图标，选择 `Install Plugin from Disk...`。
4. 选择插件 ZIP 并重启 IDE。

示例构建命令：

```bash
./gradlew buildPlugin
```

## 插件使用

### 1）确认前置条件

1. 确认 IDE 中已启用 `com.intellij.database`。
2. 确认 Database 工具窗口中至少配置了一个数据源。

### 2）配置插件参数

1. 打开 `Settings/Preferences | Tools | Database MCP`。
2. 配置以下内容：
    - 服务端口（默认值见 UI）
    - 数据源范围（`GLOBAL`、`PROJECT`、`ALL`）
    - 是否自动启动
3. 点击应用保存。

### 3）启动并验证服务

1. 打开 `Database MCP` 工具窗口。
2. 启动 MCP 服务（如已运行可重启）。
3. 访问健康检查接口：

```bash
curl -s http://127.0.0.1:8765/health
```

### 4）调用 MCP 接口

通过 `http://127.0.0.1:<port>/mcp` 发送 JSON-RPC 请求。

```bash
# 初始化
curl -s http://127.0.0.1:8765/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize"}'

# 列出工具
curl -s http://127.0.0.1:8765/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# 列出数据源
curl -s http://127.0.0.1:8765/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"database_list_datasources","arguments":{}}}'
```

### 4.1）数据库列举兼容性

`database_list_databases` 依赖 IDE 数据源提供的 JDBC 元数据。若连接器未暴露 catalog/schema，建议使用执行工具手动查询。

### 5）执行 SQL 示例

将 `YOUR_DS_NAME` 替换为 `database_list_datasources` 返回的数据源名称。

```bash
curl -s http://127.0.0.1:8765/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"database_execute_sql_query","arguments":{"dataSource":"YOUR_DS_NAME","sql":"SELECT 1","maxRows":100}}}'
```

### 6）执行 NoSQL 示例

```bash
curl -s http://127.0.0.1:8765/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"database_execute_nosql_query","arguments":{"dataSource":"YOUR_DS_NAME","statement":"db.users.find({})","maxRows":100}}}'

curl -s http://127.0.0.1:8765/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"database_execute_nosql_write_delete","arguments":{"dataSource":"YOUR_DS_NAME","statement":"db.users.deleteMany({inactive:true})"}}}'
```

说明：SQL 执行依赖 IDE 内配置的 JDBC 连接，插件不会把凭据导出到外部存储。

## MCP 接口与协议

- 基础地址：`http://127.0.0.1:<port>/mcp`（端口可在插件设置中配置，默认见 UI）
- 健康检查：`http://127.0.0.1:<port>/health`
- 协议：JSON-RPC 2.0
- 常用方法：`initialize`、`tools/list`、`tools/call`

## 构建与调试（开发）

```bash
git clone <this-repo-url>
cd ide-database-mcp
chmod +x ./gradlew
# 运行测试（可能需要联网下载 IntelliJ 平台工件）
./gradlew test
# 运行沙箱 IDE，实际验证插件行为
./gradlew runIde
```

如果本机还没有 wrapper，可先使用系统 gradle 生成一次：

```bash
gradle wrapper --gradle-version 9.4.1
chmod +x ./gradlew
```

## 故障排查

- 若 `./gradlew test` 或 `runIde` 下载 IntelliJ 依赖失败，请检查网络/TLS 访问 JetBrains 仓库的能力。
- 若提示 Database API 不可用，请确认 `com.intellij.database` 插件已安装并启用。
- 若未返回数据源，请确认 IDE 已配置数据源，且当前范围选择正确。

## 其他说明

- 在 `ALL` 模式下，如遇到重复的数据源名称会优先使用全局（IDE）定义而非项目级定义。
- 插件在运行时会检查 Database 插件是否存在并能正常访问 API；若缺失或 API 不可用，会向用户显示友好错误提示。

## 许可与致谢

本项目受社区项目启发，特别感谢：

- jone（早期反馈与建议）
- `ide-agent-for-copilot`（作者 catatafishen）：https://github.com/catatafishen/ide-agent-for-copilot

许可信息请参见仓库中的 LICENSE（如有）。

---

英文原文 / English version: [README.md](./README.md)

