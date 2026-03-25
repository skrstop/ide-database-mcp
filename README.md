# IDE Database MCP

English (default) — this repository contains a JetBrains IDE plugin that exposes the IDE's Database data sources through
a local MCP-compatible service.
Development note: 95%+ of this project's code was generated with GitHub Copilot, Claude, and Codex, then reviewed and
integrated manually.

If you prefer 中文（Chinese），see the linked translation: [README_zh.md](./README_zh.md)

---

## Module layout

- `ide-database-mcp-base`: compatibility module for `223+`, contains local HTTP MCP server, settings, and database
  bridge logic.
- `ide-database-mcp-mcpserver-ext`: optional high-version extension module that contributes `mcpToolset` when
  `com.intellij.mcpServer` exists.
- root project: packaging module that assembles both modules into one plugin distribution.

When the IDE provides the official MCP Server plugin (`com.intellij.mcpServer`), the optional `mcpToolset` extension is
loaded automatically; when it is absent, the plugin still runs with the built-in local `/mcp` service.

## Source and purpose

This plugin was developed to make IDE-managed Database data sources accessible to external tools through a lightweight
HTTP/JSON-RPC MCP-compatible bridge. It is intended for tooling that expects an MCP-style service to discover and call
database capabilities available inside the IDE.

The project is implemented against the IntelliJ Platform (since build 223+) and is intended for JetBrains IDEs where the
bundled `com.intellij.database` plugin is available and enabled.

## Key features

- Exposes Database capabilities as MCP tools:
    - `database.list_data_sources` — list configured data sources. Each item includes the inferred `type` label (MySQL,
      PostgreSQL, MongoDB, etc.) derived from the JDBC URL/driver so the client understands the flavor of the
      connection; when neither field identifies a recognizable vendor the tool reports `type` as `Custom`.
    - `database.list_databases` — list catalogs/schemas in one data source. Works with mainstream SQL engines (MySQL,
      PostgreSQL, Oracle, SQL Server, SQLite, CockroachDB, H2) and metadata-aware NoSQL connectors (MongoDB, Apache
      Cassandra, Redis, etc.). When metadata is missing fall back to `database.execute_query` for manual inspection.
    - `database.execute_query` — execute read-only SQL
    - `database.execute_dml` — execute DML statements
    - `database.execute_ddl` — execute DDL statements
- Settings UI: `Settings | Tools | Database MCP`
- Auto-start option for the local MCP service
- Data source scope control: `GLOBAL` / `PROJECT` / `ALL`
- Multi-window-safe startup using app-level state and locking

## Requirements

- JetBrains IDE based on IntelliJ Platform `223+`
- `com.intellij.database` plugin installed and enabled in the target IDE
- Java `17` (base module); Java `21` is used only to compile the optional `mcpserver` extension module.
- Gradle `9.4.1` (wrapper included)

## Plugin installation

Choose one installation path.

### Option A: Install from JetBrains Marketplace (recommended)

1. Open the target IDE.
2. Go to `Settings/Preferences | Plugins | Marketplace`.
3. Search for `IDE Database MCP`.
4. Click `Install`, then restart the IDE.

### Option B: Install from a local ZIP package

1. Build the plugin ZIP (or use an existing ZIP from `build/distributions/`).
2. In the target IDE, open `Settings/Preferences | Plugins`.
3. Click the gear icon, choose `Install Plugin from Disk...`.
4. Select the plugin ZIP and restart the IDE.

Example build command:

```bash
./gradlew buildPlugin
```

## Plugin usage

### 1) Verify prerequisites

1. Ensure `com.intellij.database` is enabled in the IDE.
2. Ensure at least one database data source exists in the IDE Database tool window.

### 2) Configure plugin settings

1. Open `Settings/Preferences | Tools | Database MCP`.
2. Configure:
    - Service port (default shown in UI)
    - Data source scope (`GLOBAL`, `PROJECT`, or `ALL`)
    - Auto-start behavior
3. Apply settings.

### 3) Start and verify the service

1. Open the `Database MCP` tool window.
2. Start the MCP service (or restart if needed).
3. Check health endpoint:

```bash
curl -s http://127.0.0.1:8765/health
```

### 4) Call MCP endpoints

Use JSON-RPC over `http://127.0.0.1:<port>/mcp`.

```bash
# initialize
curl -s http://127.0.0.1:8765/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize"}'

# list tools
curl -s http://127.0.0.1:8765/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# list data sources
curl -s http://127.0.0.1:8765/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"database.list_data_sources","arguments":{}}}'

The data source entry returned by `database.list_data_sources` includes a `type` label derived from the JDBC URL or driver metadata. When neither field exposes a recognizable vendor, the value defaults to `Custom`.
```

### 4.1) Database listing compatibility

`database.list_databases` relies on JDBC metadata exposed by the IDE data source. Supported engines include both
mainstream SQL databases and metadata-aware NoSQL connectors:

- **SQL engines:** MySQL, PostgreSQL, Oracle, SQL Server, SQLite, CockroachDB, H2
- **Metadata-aware NoSQL:** MongoDB, Apache Cassandra, Redis

If a connector is not listed above or it does not expose catalogs/schemas through JDBC metadata, fall back to
`database.execute_query` to inspect the store manually.

### 5) Run a SQL example

Replace `YOUR_DS_NAME` with a real data source name from `database.list_data_sources`.

```bash
curl -s http://127.0.0.1:8765/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"database.execute_query","arguments":{"dataSource":"YOUR_DS_NAME","sql":"SELECT 1","maxRows":100}}}'
```

Note: SQL execution uses JDBC connections defined inside the IDE. Credentials are not exported to external storage by
this plugin.

## MCP endpoints and protocol

- Base URL: `http://127.0.0.1:<port>/mcp`
- Health endpoint: `http://127.0.0.1:<port>/health`
- Protocol: JSON-RPC 2.0
- Common methods: `initialize`, `tools/list`, `tools/call`

## Build and run (development)

Clone the repository and use the included Gradle wrapper:

```bash
git clone <this-repo-url>
cd ide-database-mcp
chmod +x ./gradlew
./gradlew test
./gradlew runIde
```

If your environment does not yet have a wrapper, you can generate one once:

```bash
gradle wrapper --gradle-version 9.4.1
chmod +x ./gradlew
```

## Troubleshooting

- If IntelliJ artifacts fail to resolve during `./gradlew test` or `runIde`, verify network/TLS access to JetBrains
  repositories.
- If the plugin reports Database API unavailable, check that `com.intellij.database` is installed and enabled.
- If no data source is returned, verify data sources are configured in the IDE and the selected scope is correct.

## Other notes

- In `ALL` scope mode, duplicate names are resolved by preferring global (IDE-level) entries before project-level
  entries.
- The plugin performs runtime checks for Database plugin/API availability and reports user-friendly errors when missing.

## License and attribution

This project was inspired by work from others in the community. Special thanks to:

- jone — early feedback and ideas
- The project `ide-agent-for-copilot` by catatafishen: https://github.com/catatafishen/ide-agent-for-copilot

Please see the repository LICENSE (if present) for licensing details.

---

中文版本 / Chinese translation: [README_zh.md](./README_zh.md)
