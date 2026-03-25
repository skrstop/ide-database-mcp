package com.skrstop.ide.databasemcp.mcp;

import java.util.List;
import java.util.Map;

/**
 * Centralized MCP tool definitions. McpProtocolRouter is the canonical source for tool metadata;
 * this class provides constants for tool names and a method to obtain the tools list so other
 * modules (including Kotlin code) can reference the same definitions.
 */
public final class McpToolDefinitions {
    private McpToolDefinitions() {}

    public static final String TOOL_LIST_DATA_SOURCES = "database_list_data_sources";
    public static final String TOOL_LIST_DATABASES = "database_list_databases";
    public static final String TOOL_EXECUTE_QUERY = "database_execute_query";
    public static final String TOOL_EXECUTE_DML = "database_execute_dml";
    public static final String TOOL_EXECUTE_DDL = "database_execute_ddl";


    public static final String DESC_LIST_DATA_SOURCES =
            "Discover available IntelliJ Database data sources. Each entry includes name, url, driverClass and a `type` " +
                    "(MySQL, PostgreSQL, MongoDB, etc.) inferred from the JDBC URL/driver. Call this first when you need a valid " +
                    "dataSource name for other database tools.";
    public static final String DESC_LIST_DATABASES =
            "List databases/catalogs/schemas under one IntelliJ-managed data source. Works with SQL engines (MySQL, " +
                    "PostgreSQL, Oracle, SQL Server, SQLite, CockroachDB, H2) and metadata-aware NoSQL connectors (MongoDB, " +
                    "Apache Cassandra, Redis, etc.). When metadata is unavailable fall back to `database_execute_query` for " +
                    "manual inspection.";
    public static final String DESC_EXECUTE_QUERY =
            "Execute a read-only SQL SELECT/CTE query and return rows. Use for retrieval only; avoid DML/DDL statements.";
    public static final String DESC_EXECUTE_DML =
            "Execute data-changing DML SQL (INSERT/UPDATE/DELETE/MERGE/REPLACE). Use when you need to modify existing data.";
    public static final String DESC_EXECUTE_DDL =
            "Execute schema-changing DDL SQL (CREATE/ALTER/DROP/TRUNCATE/RENAME/COMMENT). Use for database structure changes.";


    public static final String PARAM_PROJECT_DESC =
            "Optional IntelliJ project name or path hint used to resolve data sources in multi-project IDE sessions.";
    public static final String PARAM_SCOPE_DESC =
            "Optional data source scope filter. GLOBAL = IDE-level shared sources, PROJECT = current project sources, ALL = both. " +
                    "If omitted, plugin default scope is used.";
    public static final String PARAM_SCOPE_DESC_SHORT =
            "Optional data source scope filter. GLOBAL / PROJECT / ALL. If omitted, plugin default scope is used.";
    public static final String PARAM_DATASOURCE_DESC = "Exact data source name returned by database_list_data_sources.";
    public static final String PARAM_SQL_READONLY_DESC =
            "Read-only SQL (typically SELECT/WITH). Do not pass INSERT/UPDATE/DELETE/CREATE/ALTER/DROP here.";
    public static final String PARAM_SQL_DML_DESC = "DML SQL statement such as INSERT/UPDATE/DELETE/MERGE/REPLACE.";
    public static final String PARAM_SQL_DDL_DESC =
            "DDL SQL statement such as CREATE/ALTER/DROP/TRUNCATE/RENAME/COMMENT.";
    public static final String PARAM_MAXROWS_DESC =
            "Maximum number of rows to return. Smaller values improve latency. Default: 200.";


    public static final String KT_DESC_LIST_DATA_SOURCES =
            DESC_LIST_DATA_SOURCES +
                    "\n\n" +
                    "Parameters:\n" +
                    "- project (optional): " + PARAM_PROJECT_DESC + "\n" +
                    "- scope (optional): " + PARAM_SCOPE_DESC;

    public static final String KT_DESC_LIST_DATABASES =
            DESC_LIST_DATABASES +
                    "\n\n" +
                    "Parameters:\n" +
                    "- project (optional): " + PARAM_PROJECT_DESC + "\n" +
                    "- scope (optional): " + PARAM_SCOPE_DESC_SHORT + "\n" +
                    "- dataSource (required): " + PARAM_DATASOURCE_DESC;

    public static final String KT_DESC_EXECUTE_QUERY =
            DESC_EXECUTE_QUERY +
                    "\n\n" +
                    "Parameters:\n" +
                    "- project (optional): " + PARAM_PROJECT_DESC + "\n" +
                    "- scope (optional): " + PARAM_SCOPE_DESC_SHORT + "\n" +
                    "- dataSource (required): " + PARAM_DATASOURCE_DESC + "\n" +
                    "- sql (required): " + PARAM_SQL_READONLY_DESC + "\n" +
                    "- maxRows (optional): " + PARAM_MAXROWS_DESC;

    public static final String KT_DESC_EXECUTE_DML =
            DESC_EXECUTE_DML +
                    "\n\n" +
                    "Parameters:\n" +
                    "- project (optional): " + PARAM_PROJECT_DESC + "\n" +
                    "- scope (optional): " + PARAM_SCOPE_DESC_SHORT + "\n" +
                    "- dataSource (required): " + PARAM_DATASOURCE_DESC + "\n" +
                    "- sql (required): " + PARAM_SQL_DML_DESC;

    public static final String KT_DESC_EXECUTE_DDL =
            DESC_EXECUTE_DDL +
                    "\n\n" +
                    "Parameters:\n" +
                    "- project (optional): " + PARAM_PROJECT_DESC + "\n" +
                    "- scope (optional): " + PARAM_SCOPE_DESC_SHORT + "\n" +
                    "- dataSource (required): " + PARAM_DATASOURCE_DESC + "\n" +
                    "- sql (required): " + PARAM_SQL_DDL_DESC;


    public static List<Map<String, Object>> getTools() {
        return List.of(
                Map.of(
                        "name", TOOL_LIST_DATA_SOURCES,
                        "description", DESC_LIST_DATA_SOURCES,
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "project", Map.of(
                                                "type", "string",
                                                "description", PARAM_PROJECT_DESC,
                                                "examples", List.of("ide-database-mcp", "/Users/me/workspace/my-project")
                                        ),
                                        "scope", Map.of(
                                                "type", "string",
                                                "description", PARAM_SCOPE_DESC,
                                                "enum", List.of("GLOBAL", "PROJECT", "ALL"),
                                                "examples", List.of("ALL")
                                        )
                                ),
                                "additionalProperties", false
                        )
                ),
                Map.of(
                        "name", TOOL_LIST_DATABASES,
                        "description", DESC_LIST_DATABASES,
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "project", Map.of(
                                                "type", "string",
                                                "description", PARAM_PROJECT_DESC,
                                                "examples", List.of("ide-database-mcp", "/Users/me/workspace/my-project")
                                        ),
                                        "scope", Map.of(
                                                "type", "string",
                                                "description", PARAM_SCOPE_DESC_SHORT,
                                                "enum", List.of("GLOBAL", "PROJECT", "ALL"),
                                                "examples", List.of("ALL")
                                        ),
                                        "dataSource", Map.of(
                                                "type", "string",
                                                "description", PARAM_DATASOURCE_DESC,
                                                "minLength", 1,
                                                "examples", List.of("Local MySQL", "PostgreSQL-Prod")
                                        )
                                ),
                                "required", List.of("dataSource"),
                                "additionalProperties", false
                        )
                ),
                Map.of(
                        "name", TOOL_EXECUTE_QUERY,
                        "description", DESC_EXECUTE_QUERY,
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "project", Map.of(
                                                "type", "string",
                                                "description", PARAM_PROJECT_DESC,
                                                "examples", List.of("ide-database-mcp", "/Users/me/workspace/my-project")
                                        ),
                                        "scope", Map.of(
                                                "type", "string",
                                                "description", PARAM_SCOPE_DESC_SHORT,
                                                "enum", List.of("GLOBAL", "PROJECT", "ALL"),
                                                "examples", List.of("ALL")
                                        ),
                                        "dataSource", Map.of(
                                                "type", "string",
                                                "description", PARAM_DATASOURCE_DESC,
                                                "minLength", 1,
                                                "examples", List.of("Local MySQL", "PostgreSQL-Prod")
                                        ),
                                        "sql", Map.of(
                                                "type", "string",
                                                "description", PARAM_SQL_READONLY_DESC,
                                                "minLength", 1,
                                                "examples", List.of("SELECT id, name FROM users ORDER BY id DESC LIMIT 20")
                                        ),
                                        "maxRows", Map.of(
                                                "type", "integer",
                                                "description", PARAM_MAXROWS_DESC,
                                                "minimum", 1,
                                                "maximum", 10000,
                                                "default", 200,
                                                "examples", List.of(100, 500)
                                        )
                                ),
                                "required", List.of("dataSource", "sql"),
                                "additionalProperties", false
                        )
                ),
                Map.of(
                        "name", TOOL_EXECUTE_DML,
                        "description", DESC_EXECUTE_DML,
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "project", Map.of(
                                                "type", "string",
                                                "description", PARAM_PROJECT_DESC,
                                                "examples", List.of("ide-database-mcp", "/Users/me/workspace/my-project")
                                        ),
                                        "scope", Map.of(
                                                "type", "string",
                                                "description", PARAM_SCOPE_DESC_SHORT,
                                                "enum", List.of("GLOBAL", "PROJECT", "ALL"),
                                                "examples", List.of("ALL")
                                        ),
                                        "dataSource", Map.of(
                                                "type", "string",
                                                "description", PARAM_DATASOURCE_DESC,
                                                "minLength", 1,
                                                "examples", List.of("Local MySQL", "PostgreSQL-Prod")
                                        ),
                                        "sql", Map.of(
                                                "type", "string",
                                                "description", PARAM_SQL_DML_DESC,
                                                "minLength", 1,
                                                "examples", List.of("UPDATE users SET status = 'ACTIVE' WHERE id = 1001")
                                        )
                                ),
                                "required", List.of("dataSource", "sql"),
                                "additionalProperties", false
                        )
                ),
                Map.of(
                        "name", TOOL_EXECUTE_DDL,
                        "description", DESC_EXECUTE_DDL,
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "project", Map.of(
                                                "type", "string",
                                                "description", PARAM_PROJECT_DESC,
                                                "examples", List.of("ide-database-mcp", "/Users/me/workspace/my-project")
                                        ),
                                        "scope", Map.of(
                                                "type", "string",
                                                "description", PARAM_SCOPE_DESC_SHORT,
                                                "enum", List.of("GLOBAL", "PROJECT", "ALL"),
                                                "examples", List.of("ALL")
                                        ),
                                        "dataSource", Map.of(
                                                "type", "string",
                                                "description", PARAM_DATASOURCE_DESC,
                                                "minLength", 1,
                                                "examples", List.of("Local MySQL", "PostgreSQL-Prod")
                                        ),
                                        "sql", Map.of(
                                                "type", "string",
                                                "description", PARAM_SQL_DDL_DESC,
                                                "minLength", 1,
                                                "examples", List.of("ALTER TABLE users ADD COLUMN last_login TIMESTAMP NULL")
                                        )
                                ),
                                "required", List.of("dataSource", "sql"),
                                "additionalProperties", false
                        )
                )
        );
    }
}

