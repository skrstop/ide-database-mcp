# Changelog

All notable changes to **IDE Database MCP** are documented in this file.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [0.1.8] - 2026-04-28

### Added

- **多项目数据源支持**：`database_list_datasources` 现在支持多项目合并，返回结果新增 `project`（项目名）和 `projectPath`
  （项目路径）字段，方便 AI 区分同名数据源的来源项目。
- **自定义工具数据源精准绑定**：自定义 SQL 工具的数据源选择下拉框新增 scope 标签（`【全局】` / `【项目】`）和项目名显示，格式为
  `【全局/项目】- 数据源名 -（项目名）`；选中后同时持久化 `projectHint`（项目名）与 `projectPath`
  （项目路径），执行时优先使用路径精准路由，彻底解决同名数据源/同名项目导致的错误路由问题。

### Changed

- **同名数据源歧义检测**：`listDatabases`、`executeSql` 等所有需要定位数据源的操作，当 `project`
  参数为空且多个项目存在同名数据源时，主动抛出歧义错误并列出所有候选项目名及路径，引导用户/AI 补充 `project` 参数。
- **同名项目歧义检测**：当 `project` 参数是项目名且存在多个同名不同路径的项目时，主动报错并列出所有路径，提示改用项目路径作为参数以实现精准定位。

---

## [0.1.7] - 2026-04-27

### Added

- 自定义工具（Custom Tools）新增**复制功能**：在列表工具栏 `+` / `−` 按钮后追加"复制"按钮，一键深拷贝当前选中工具（含所有参数配置），自动生成不重名的唯一方法名。

### Changed

- 自定义工具测试预览行数改为沿用「最大行数」字段的配置值（未配置时默认 100），不再强制限制为固定值；预览面板高度增大，方便查阅更多结果行。
- 自定义工具测试成功提示文案精简，去掉"预览仅显示前 N 行"的冗余说明。
- 去除设置页参数提示中"运行时使用 PreparedStatement 绑定以避免注入"的技术实现说明（属内部实现细节，无需面向用户展示）。

### Fixed

- 新增、删除、启用或禁用自定义工具后，点击 Apply / OK 保存时，工具窗口的**方法调用统计表**
  立刻同步反映变更：已删除或已禁用的条目从统计表移除，新增或启用的工具预置 0 计数条目立即出现。

---

## [0.1.6] - 2026-04-17

### Fixed

- 修复若干已知问题，提升整体稳定性。
- 全面优化性能，降低 CPU 占用并提升 ToolWindow 响应速度。

---

## [0.1.5] - 2026-04-07

### Changed

- Plugin description updated with explicit network, privacy, and AI tool usage disclosures
  per JetBrains Marketplace Approval Guidelines.
- Added `<change-notes>` section to `plugin.xml` with full version history.

### Added

- `database_list_datasources` 工具返回新增 `databaseVersion` 字段，展示各数据源的数据库版本信息。
- 数据源类型推断新增三参数版本，优先通过 IntelliJ Database API（反射）获取产品类型，降级为 JDBC URL / 驱动类推断。
- 数据库版本多优先级解析：Database API → JDBC URL 版本参数 → 驱动类名启发推断。
- Log rotation support: configurable max file size, max rotated log files.
- Configurable log read buffer size for performance tuning.
- Project-level settings scope (GLOBAL / PROJECT) in the Settings UI.
- Runtime log viewer panel in the Database MCP tool window.
- Improved MCP server graceful shutdown on IDE exit.
- `LICENSE` (MIT) and `CHANGELOG.md` added to the repository.

---

## [0.1.4] - 2026-03-26

### Added

- Dual-variant build strategy:
    - `modern` variant (IDE 2025.2+): includes optional `mcpToolset` extension for `com.intellij.mcpServer`.
    - `legacy` variant (IDE 2022.3–2025.1): standalone local HTTP MCP server, no MCP plugin dependency.
- Optional `mcpToolset` extension auto-activated when `com.intellij.mcpServer` is present in the IDE.
- Tool window: Method call counter with per-tool avg/total latency statistics.
- Tool window: Runtime log viewer with search and soft-wrap support.
- Multi-window-safe startup using app-level state locking to prevent duplicate server instances.

---

## [0.1.3] - 2026-03-24

### Added

- NoSQL tools: `database_execute_nosql_query` and `database_execute_nosql_write_delete`.
- `database_list_databases` tool for listing catalogs/schemas under a data source.
- Data source type inference from JDBC URL and driver class (MySQL, PostgreSQL, MongoDB, etc.).
- Data source scope control: `GLOBAL` / `PROJECT` / `ALL`.
- `project` parameter on all tools for multi-project session disambiguation.

---

## [0.1.2] - 2026-03-22

### Added

- Settings UI under `Settings | Tools | Database MCP`.
- Auto-start option: MCP service can start automatically when IDE opens.
- Service status display (Running / Stopped) in settings and tool window.
- Manual start / stop controls in both settings and tool window.
- Service address copy button.

---

## [0.1.1] - 2026-03-21

### Changed

- Split the single SQL execute tool into three distinct tools:
    - `database_execute_sql_query` — read/query operations.
    - `database_execute_sql_dml` — INSERT/UPDATE/DELETE operations.
    - `database_execute_sql_ddl` — CREATE/ALTER/DROP operations.

### Added

- Health check endpoint at `GET /health` returning `ok`.
- `maxRows` parameter on query tools (default 200, max 10000).

---

## [0.1.0] - 2026-03-20

### Added

- Initial release.
- Local HTTP/JSON-RPC MCP-compatible service exposing JetBrains Database data sources.
- Tools: `database_list_datasources`, `database_execute_sql_query`.
- Requires `com.intellij.database` plugin (bundled in IntelliJ IDEA Ultimate).
- Compatible with IntelliJ Platform 2022.3+.
