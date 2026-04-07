# Changelog

All notable changes to **IDE Database MCP** are documented in this file.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

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
