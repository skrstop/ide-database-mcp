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

    public static final String TOOL_LIST_DATASOURCES = "database_list_datasources";
    public static final String TOOL_LIST_DATABASES = "database_list_databases";
    public static final String TOOL_EXECUTE_SQL_QUERY = "database_execute_sql_query";
    public static final String TOOL_EXECUTE_SQL_DML = "database_execute_sql_dml";
    public static final String TOOL_EXECUTE_SQL_DDL = "database_execute_sql_ddl";
    public static final String TOOL_EXECUTE_NOSQL_QUERY = "database_execute_nosql_query";
    public static final String TOOL_EXECUTE_NOSQL_WRITE_DELETE = "database_execute_nosql_write_delete";
    /**
     * 获取数据库 Schema 结构（表 + 列定义），支持关键词相关性过滤
     */
    public static final String TOOL_LIST_TABLE_SCHEMA = "database_list_table_schema";


    public static final String DESC_LIST_DATA_SOURCES =
            "Discover available IntelliJ Database data sources. Each entry includes name, url, driverClass and an inferred `type` label. " +
                    "Call this first when you need a valid " +
                    "dataSource name for other database tools.";
    public static final String DESC_LIST_DATABASES =
            "List databases/catalogs/schemas under one IntelliJ-managed data source. When metadata is unavailable, fall back to query tools for " +
                    "manual inspection.";
    public static final String DESC_EXECUTE_QUERY =
            "Execute SQL intended for read/query use and return rows. This tool relies on caller discipline via description and parameters; " +
                    "the server does not parse SQL keywords for enforcement.";
    public static final String DESC_EXECUTE_DML =
            "Execute SQL intended for data modification. Choose this tool for INSERT/UPDATE/DELETE style operations by convention.";
    public static final String DESC_EXECUTE_DDL =
            "Execute SQL intended for schema/structure changes. Choose this tool for CREATE/ALTER/DROP style operations by convention.";
    public static final String DESC_EXECUTE_NOSQL_QUERY =
            "Execute NoSQL statements intended for read/query usage. Tool selection is controlled by description and parameters only.";
    public static final String DESC_EXECUTE_NOSQL_WRITE_DELETE =
            "Execute NoSQL statements intended for write/delete usage. Tool selection is controlled by description and parameters only.";
    public static final String DESC_LIST_TABLE_SCHEMA =
            "Get the table and column structure (schema) of a database. " +
                    "Call this tool BEFORE writing SQL queries whenever you need to know what tables exist, " +
                    "what columns they have, or how they relate to each other. " +
                    "For large databases with many tables, provide keywords matching your current task " +
                    "(e.g. 'order', 'user', 'payment') — only the most relevant tables are returned, " +
                    "ranked by keyword matches in table names, column names, and comments, " +
                    "plus foreign-key connectivity. Without keywords the most interconnected (core) tables appear first. " +
                    "Use catalog/schema/tablePrefix to further narrow the scope.";


    public static final String PARAM_PROJECT_DESC =
            "Optional IntelliJ project name or path hint used to resolve data sources in multi-project IDE sessions.";
    public static final String PARAM_SCOPE_DESC =
            "Optional data source scope filter. GLOBAL = IDE-level shared sources, PROJECT = current project sources, ALL = both. " +
                    "If omitted, plugin default scope is used.";
    public static final String PARAM_SCOPE_DESC_SHORT =
            "Optional data source scope filter. GLOBAL / PROJECT / ALL. If omitted, plugin default scope is used.";
    public static final String PARAM_DATASOURCE_DESC = "Exact data source name returned by database_list_datasources.";
    public static final String PARAM_SQL_READONLY_DESC =
            "SQL text intended for query/read operations (for example SELECT/WITH).";
    public static final String PARAM_SQL_DML_DESC = "SQL text intended for data modification operations.";
    public static final String PARAM_SQL_DDL_DESC =
            "SQL text intended for schema/structure changes.";
    public static final String PARAM_NOSQL_STMT_DESC =
            "NoSQL statement/command text. Use the query or write-delete tool according to intended operation.";
    public static final String PARAM_MAXROWS_DESC =
            "Maximum number of rows to return. Smaller values improve latency. Default: 200.";


    public static final String KT_DESC_LIST_DATASOURCES =
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

    public static final String KT_DESC_EXECUTE_SQL_QUERY =
            DESC_EXECUTE_QUERY +
                    "\n\n" +
                    "Parameters:\n" +
                    "- project (optional): " + PARAM_PROJECT_DESC + "\n" +
                    "- scope (optional): " + PARAM_SCOPE_DESC_SHORT + "\n" +
                    "- dataSource (required): " + PARAM_DATASOURCE_DESC + "\n" +
                    "- sql (required): " + PARAM_SQL_READONLY_DESC + "\n" +
                    "- maxRows (optional): " + PARAM_MAXROWS_DESC;

    public static final String KT_DESC_EXECUTE_SQL_DML =
            DESC_EXECUTE_DML +
                    "\n\n" +
                    "Parameters:\n" +
                    "- project (optional): " + PARAM_PROJECT_DESC + "\n" +
                    "- scope (optional): " + PARAM_SCOPE_DESC_SHORT + "\n" +
                    "- dataSource (required): " + PARAM_DATASOURCE_DESC + "\n" +
                    "- sql (required): " + PARAM_SQL_DML_DESC;

    public static final String KT_DESC_EXECUTE_SQL_DDL =
            DESC_EXECUTE_DDL +
                    "\n\n" +
                    "Parameters:\n" +
                    "- project (optional): " + PARAM_PROJECT_DESC + "\n" +
                    "- scope (optional): " + PARAM_SCOPE_DESC_SHORT + "\n" +
                    "- dataSource (required): " + PARAM_DATASOURCE_DESC + "\n" +
                    "- sql (required): " + PARAM_SQL_DDL_DESC;

    public static final String KT_DESC_EXECUTE_NOSQL_QUERY =
            DESC_EXECUTE_NOSQL_QUERY +
                    "\n\n" +
                    "Parameters:\n" +
                    "- project (optional): " + PARAM_PROJECT_DESC + "\n" +
                    "- scope (optional): " + PARAM_SCOPE_DESC_SHORT + "\n" +
                    "- dataSource (required): " + PARAM_DATASOURCE_DESC + "\n" +
                    "- statement (required): " + PARAM_NOSQL_STMT_DESC + "\n" +
                    "- maxRows (optional): " + PARAM_MAXROWS_DESC;

    public static final String KT_DESC_EXECUTE_NOSQL_WRITE_DELETE =
            DESC_EXECUTE_NOSQL_WRITE_DELETE +
                    "\n\n" +
                    "Parameters:\n" +
                    "- project (optional): " + PARAM_PROJECT_DESC + "\n" +
                    "- scope (optional): " + PARAM_SCOPE_DESC_SHORT + "\n" +
                    "- dataSource (required): " + PARAM_DATASOURCE_DESC + "\n" +
                    "- statement (required): " + PARAM_NOSQL_STMT_DESC;

    public static final String KT_DESC_GET_SCHEMA =
            DESC_LIST_TABLE_SCHEMA +
                    "\n\n" +
                    "Parameters:\n" +
                    "- project (optional): " + PARAM_PROJECT_DESC + "\n" +
                    "- scope (optional): " + PARAM_SCOPE_DESC_SHORT + "\n" +
                    "- dataSource (required): " + PARAM_DATASOURCE_DESC + "\n" +
                    "- catalog (optional): Target catalog/database name to filter tables. Leave blank to use the default.\n" +
                    "- schema (optional): Target schema name to filter tables. Leave blank to use the default.\n" +
                    "- keywords (optional): Relevance keywords, e.g. ['order','user','payment']. " +
                    "Tables/columns whose names or comments match these keywords score higher and appear first.\n" +
                    "- tablePrefix (optional): Only include tables whose names start with this prefix, e.g. 'order_'.\n" +
                    "- maxTables (optional): Maximum number of tables to return. Default: 20, range 1-200.\n" +
                    "- includeIndexes (optional): Whether to include index information for each table. Default: false.";


    public static List<Map<String, Object>> getTools() {
        return List.of(
                Map.of(
                        "name", TOOL_LIST_DATASOURCES,
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
                        "name", TOOL_EXECUTE_SQL_QUERY,
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
                        "name", TOOL_EXECUTE_SQL_DML,
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
                        "name", TOOL_EXECUTE_SQL_DDL,
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
                ),
                Map.of(
                        "name", TOOL_EXECUTE_NOSQL_QUERY,
                        "description", DESC_EXECUTE_NOSQL_QUERY,
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
                                                "examples", List.of("Local MongoDB", "Redis-Prod")
                                        ),
                                        "statement", Map.of(
                                                "type", "string",
                                                "description", PARAM_NOSQL_STMT_DESC,
                                                "minLength", 1,
                                                "examples", List.of("db.users.find({status: 'ACTIVE'})", "GET user:1001")
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
                                "required", List.of("dataSource", "statement"),
                                "additionalProperties", false
                        )
                ),
                Map.of(
                        "name", TOOL_EXECUTE_NOSQL_WRITE_DELETE,
                        "description", DESC_EXECUTE_NOSQL_WRITE_DELETE,
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
                                                "examples", List.of("Local MongoDB", "Redis-Prod")
                                        ),
                                        "statement", Map.of(
                                                "type", "string",
                                                "description", PARAM_NOSQL_STMT_DESC,
                                                "minLength", 1,
                                                "examples", List.of("db.users.updateOne({id: 1}, {$set: {active: true}})", "DEL user:1001")
                                        )
                                ),
                                "required", List.of("dataSource", "statement"),
                                "additionalProperties", false
                        )
                ),
                Map.of(
                        "name", TOOL_LIST_TABLE_SCHEMA,
                        "description", DESC_LIST_TABLE_SCHEMA,
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.ofEntries(
                                        Map.entry("project", Map.of(
                                                "type", "string",
                                                "description", PARAM_PROJECT_DESC,
                                                "examples", List.of("ide-database-mcp", "/Users/me/workspace/my-project")
                                        )),
                                        Map.entry("scope", Map.of(
                                                "type", "string",
                                                "description", PARAM_SCOPE_DESC_SHORT,
                                                "enum", List.of("GLOBAL", "PROJECT", "ALL"),
                                                "examples", List.of("ALL")
                                        )),
                                        Map.entry("dataSource", Map.of(
                                                "type", "string",
                                                "description", PARAM_DATASOURCE_DESC,
                                                "minLength", 1,
                                                "examples", List.of("Local MySQL", "PostgreSQL-Prod")
                                        )),
                                        Map.entry("catalog", Map.of(
                                                "type", "string",
                                                "description", "Target catalog/database name to filter tables. Leave blank for driver default."
                                        )),
                                        Map.entry("schema", Map.of(
                                                "type", "string",
                                                "description", "Target schema name to filter tables. Leave blank for driver default."
                                        )),
                                        Map.entry("keywords", Map.of(
                                                "type", "array",
                                                "items", Map.of("type", "string"),
                                                "description", "Relevance keywords. Tables/columns matching these keywords score higher and appear first.",
                                                "examples", List.of(List.of("order", "user", "payment"))
                                        )),
                                        Map.entry("tablePrefix", Map.of(
                                                "type", "string",
                                                "description", "Only include tables whose names start with this prefix, e.g. 'order_'."
                                        )),
                                        Map.entry("maxTables", Map.of(
                                                "type", "integer",
                                                "description", "Maximum number of tables to return. Default: 20, range 1-200.",
                                                "minimum", 1,
                                                "maximum", 200,
                                                "default", 20,
                                                "examples", List.of(10, 20, 50)
                                        )),
                                        Map.entry("includeIndexes", Map.of(
                                                "type", "boolean",
                                                "description", "Whether to include index information for each table. Default: false.",
                                                "default", false
                                        ))
                                ),
                                "required", List.of("dataSource"),
                                "additionalProperties", false
                        )
                )
        );
    }
}

