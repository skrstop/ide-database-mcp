package com.skrstop.ide.databasemcp.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.skrstop.ide.databasemcp.db.IdeDatabaseFacade;
import com.skrstop.ide.databasemcp.service.McpMethodMetricsService;
import com.skrstop.ide.databasemcp.service.McpRuntimeLogService;
import com.skrstop.ide.databasemcp.settings.McpSettingsState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class McpProtocolRouter {
    private static final Gson GSON = new Gson();
    private final IdeDatabaseFacade databaseFacade;
    private static final String TOOL_LIST_DATA_SOURCES = "database.list_data_sources";
    private static final String TOOL_LIST_DATABASES = "database.list_databases";
    private static final String TOOL_EXECUTE_QUERY = "database.execute_query";
    private static final String TOOL_EXECUTE_DML = "database.execute_dml";
    private static final String TOOL_EXECUTE_DDL = "database.execute_ddl";

    public McpProtocolRouter(IdeDatabaseFacade databaseFacade) {
        this.databaseFacade = databaseFacade;
    }

    public String handle(String requestBody) {
        long startNanos = System.nanoTime();
        String methodKey = "rpc:unknown";
        try {
            JsonObject req = JsonParser.parseString(requestBody).getAsJsonObject();
            String method = req.get("method").getAsString();
            JsonElement id = req.get("id");
            JsonObject params = req.has("params") && req.get("params").isJsonObject() ? req.getAsJsonObject("params") : new JsonObject();
            methodKey = "rpc:" + method;

            return switch (method) {
                case "initialize" -> ok(id, initializeResult());
                case "tools/list" -> handleToolsList(id);
                case "tools/call" -> handleToolCall(id, params);
                default -> error(id, -32601, "Method not found: " + method);
            };
        } catch (Exception ex) {
            logError("Protocol handle failed: " + ex.getMessage());
            return error(null, -32603, "Internal error: " + ex.getMessage());
        } finally {
            recordInvocation(methodKey, System.nanoTime() - startNanos);
        }
    }

    private Map<String, Object> initializeResult() {
        return Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(
                        "tools", Map.of("listChanged", false)
                ),
                "serverInfo", Map.of("name", "ide-database-mcp", "version", "0.1.0")
        );
    }

    private String handleToolCall(JsonElement id, JsonObject params) {
        String toolName = params.has("name") ? params.get("name").getAsString() : "";
        long startNanos = System.nanoTime();
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();

        try {
            return switch (toolName) {
                case TOOL_LIST_DATA_SOURCES -> {
                    String project = args.has("project") ? args.get("project").getAsString() : "";
                    McpSettingsState.DataSourceScope scope = parseScopeArg(args);
                    List<Map<String, Object>> dataSources = databaseFacade.listDataSources(project, scope);
                    logInfo("Executed tool " + TOOL_LIST_DATA_SOURCES);
                    yield ok(id, mcpToolResult(dataSources));
                }
                case TOOL_LIST_DATABASES -> {
                    String project = args.has("project") ? args.get("project").getAsString() : "";
                    String dataSource = requiredString(args, "dataSource");
                    McpSettingsState.DataSourceScope scope = parseScopeArg(args);
                    List<Map<String, Object>> databases = databaseFacade.listDatabases(project, dataSource, scope);
                    logInfo("Executed tool " + TOOL_LIST_DATABASES + " on data source: " + dataSource);
                    yield ok(id, mcpToolResult(databases));
                }
                case TOOL_EXECUTE_QUERY -> {
                    String project = args.has("project") ? args.get("project").getAsString() : "";
                    String dataSource = requiredString(args, "dataSource");
                    String sql = requiredString(args, "sql");
                    int maxRows = args.has("maxRows") ? args.get("maxRows").getAsInt() : 200;
                    McpSettingsState.DataSourceScope scope = parseScopeArg(args);
                    Map<String, Object> queryResult = databaseFacade.executeQuerySql(project, dataSource, sql, maxRows, scope);
                    logInfo("Executed tool " + TOOL_EXECUTE_QUERY + " on data source: " + dataSource);
                    yield ok(id, mcpToolResult(queryResult));
                }
                case TOOL_EXECUTE_DML -> {
                    String project = args.has("project") ? args.get("project").getAsString() : "";
                    String dataSource = requiredString(args, "dataSource");
                    String sql = requiredString(args, "sql");
                    McpSettingsState.DataSourceScope scope = parseScopeArg(args);
                    Map<String, Object> dmlResult = databaseFacade.executeDmlSql(project, dataSource, sql, scope);
                    logInfo("Executed tool " + TOOL_EXECUTE_DML + " on data source: " + dataSource);
                    yield ok(id, mcpToolResult(dmlResult));
                }
                case TOOL_EXECUTE_DDL -> {
                    String project = args.has("project") ? args.get("project").getAsString() : "";
                    String dataSource = requiredString(args, "dataSource");
                    String sql = requiredString(args, "sql");
                    McpSettingsState.DataSourceScope scope = parseScopeArg(args);
                    Map<String, Object> ddlResult = databaseFacade.executeDdlSql(project, dataSource, sql, scope);
                    logInfo("Executed tool " + TOOL_EXECUTE_DDL + " on data source: " + dataSource);
                    yield ok(id, mcpToolResult(ddlResult));
                }
                default -> error(id, -32602, "Unsupported tool: " + toolName);
            };
        } catch (Exception ex) {
            logError("Tool call failed: " + toolName + ", error=" + ex.getMessage());
            return error(id, -32000, ex.getMessage());
        } finally {
            recordInvocation("tool:" + toolName, System.nanoTime() - startNanos);
        }
    }

    private String handleToolsList(JsonElement id) {
        List<Map<String, Object>> tools = List.of(
                Map.of(
                        "name", TOOL_LIST_DATA_SOURCES,
                        "description", "Discover available IntelliJ Database data sources. Each entry includes name, url, driverClass and a `type` (MySQL, PostgreSQL, MongoDB, etc.) inferred from the JDBC URL/driver. Call this first when you need a valid dataSource name for other database tools.",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "project", Map.of(
                                                "type", "string",
                                                "description", "Optional IntelliJ project name or path hint used to resolve data sources in multi-project IDE sessions.",
                                                "examples", List.of("ide-database-mcp", "/Users/me/workspace/my-project")
                                        ),
                                        "scope", Map.of(
                                                "type", "string",
                                                "description", "Optional data source scope filter. GLOBAL = IDE-level shared sources, PROJECT = current project sources, ALL = both. If omitted, plugin default scope is used.",
                                                "enum", List.of("GLOBAL", "PROJECT", "ALL"),
                                                "examples", List.of("ALL")
                                        )
                                ),
                                "additionalProperties", false
                        )
                ),
                Map.of(
                        "name", TOOL_LIST_DATABASES,
                        "description", "List databases/catalogs/schemas under one IntelliJ-managed data source. Works with SQL engines (MySQL, PostgreSQL, Oracle, SQL Server, SQLite, CockroachDB, H2) and metadata-aware NoSQL connectors (MongoDB, Apache Cassandra, Redis, etc.). When metadata is unavailable fall back to `database.execute_query` for manual inspection.",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "project", Map.of(
                                                "type", "string",
                                                "description", "Optional IntelliJ project name or path hint for disambiguation in multi-project IDE sessions.",
                                                "examples", List.of("ide-database-mcp", "/Users/me/workspace/my-project")
                                        ),
                                        "scope", Map.of(
                                                "type", "string",
                                                "description", "Optional data source scope filter. GLOBAL / PROJECT / ALL. If omitted, plugin default scope is used.",
                                                "enum", List.of("GLOBAL", "PROJECT", "ALL"),
                                                "examples", List.of("ALL")
                                        ),
                                        "dataSource", Map.of(
                                                "type", "string",
                                                "description", "Exact data source name returned by database.list_data_sources.",
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
                        "description", "Execute a read-only SQL SELECT/CTE query and return rows. Use for retrieval only; avoid DML/DDL statements.",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "project", Map.of(
                                                "type", "string",
                                                "description", "Optional IntelliJ project name or path hint for data source resolution.",
                                                "examples", List.of("ide-database-mcp", "/Users/me/workspace/my-project")
                                        ),
                                        "scope", Map.of(
                                                "type", "string",
                                                "description", "Optional data source scope filter. GLOBAL / PROJECT / ALL. If omitted, plugin default scope is used.",
                                                "enum", List.of("GLOBAL", "PROJECT", "ALL"),
                                                "examples", List.of("ALL")
                                        ),
                                        "dataSource", Map.of(
                                                "type", "string",
                                                "description", "Exact data source name returned by database.list_data_sources.",
                                                "minLength", 1,
                                                "examples", List.of("Local MySQL", "PostgreSQL-Prod")
                                        ),
                                        "sql", Map.of(
                                                "type", "string",
                                                "description", "Read-only SQL (typically SELECT/WITH). Do not pass INSERT/UPDATE/DELETE/CREATE/ALTER/DROP here.",
                                                "minLength", 1,
                                                "examples", List.of("SELECT id, name FROM users ORDER BY id DESC LIMIT 20")
                                        ),
                                        "maxRows", Map.of(
                                                "type", "integer",
                                                "description", "Maximum number of rows to return. Smaller values improve latency. Default: 200.",
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
                        "description", "Execute data-changing DML SQL (INSERT/UPDATE/DELETE/MERGE/REPLACE). Use when you need to modify existing data.",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "project", Map.of(
                                                "type", "string",
                                                "description", "Optional IntelliJ project name or path hint for data source resolution.",
                                                "examples", List.of("ide-database-mcp", "/Users/me/workspace/my-project")
                                        ),
                                        "scope", Map.of(
                                                "type", "string",
                                                "description", "Optional data source scope filter. GLOBAL / PROJECT / ALL. If omitted, plugin default scope is used.",
                                                "enum", List.of("GLOBAL", "PROJECT", "ALL"),
                                                "examples", List.of("ALL")
                                        ),
                                        "dataSource", Map.of(
                                                "type", "string",
                                                "description", "Exact data source name returned by database.list_data_sources.",
                                                "minLength", 1,
                                                "examples", List.of("Local MySQL", "PostgreSQL-Prod")
                                        ),
                                        "sql", Map.of(
                                                "type", "string",
                                                "description", "DML SQL statement such as INSERT/UPDATE/DELETE/MERGE/REPLACE.",
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
                        "description", "Execute schema-changing DDL SQL (CREATE/ALTER/DROP/TRUNCATE/RENAME/COMMENT). Use for database structure changes.",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "project", Map.of(
                                                "type", "string",
                                                "description", "Optional IntelliJ project name or path hint for data source resolution.",
                                                "examples", List.of("ide-database-mcp", "/Users/me/workspace/my-project")
                                        ),
                                        "scope", Map.of(
                                                "type", "string",
                                                "description", "Optional data source scope filter. GLOBAL / PROJECT / ALL. If omitted, plugin default scope is used.",
                                                "enum", List.of("GLOBAL", "PROJECT", "ALL"),
                                                "examples", List.of("ALL")
                                        ),
                                        "dataSource", Map.of(
                                                "type", "string",
                                                "description", "Exact data source name returned by database.list_data_sources.",
                                                "minLength", 1,
                                                "examples", List.of("Local MySQL", "PostgreSQL-Prod")
                                        ),
                                        "sql", Map.of(
                                                "type", "string",
                                                "description", "DDL SQL statement such as CREATE/ALTER/DROP/TRUNCATE/RENAME/COMMENT.",
                                                "minLength", 1,
                                                "examples", List.of("ALTER TABLE users ADD COLUMN last_login TIMESTAMP NULL")
                                        )
                                ),
                                "required", List.of("dataSource", "sql"),
                                "additionalProperties", false
                        )
                )
        );

        recordToolsListUsage(tools);
        return ok(id, Map.of("tools", tools));
    }

    private void recordToolsListUsage(List<Map<String, Object>> tools) {
        McpMethodMetricsService metricsService = metricsService();
        if (metricsService == null) {
            return;
        }

        for (Map<String, Object> tool : tools) {
            Object name = tool.get("name");
            if (name != null) {
                metricsService.incrementUnbounded("tool:" + name);
            }
        }
    }

    private void recordInvocation(String key, long elapsedNanos) {
        McpMethodMetricsService metricsService = metricsService();
        if (metricsService == null) {
            return;
        }

        long count = metricsService.record(key, elapsedNanos);
        logInfo("Invocation recorded: " + key + " count=" + count);
    }

    private String requiredString(JsonObject args, String field) {
        if (!args.has(field) || args.get(field).isJsonNull()) {
            throw new IllegalArgumentException("Missing required argument: " + field);
        }
        String value = args.get(field).getAsString();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Argument must not be blank: " + field);
        }
        return value;
    }

    private McpSettingsState.DataSourceScope parseScopeArg(JsonObject args) {
        if (!args.has("scope") || args.get("scope").isJsonNull()) {
            return null;
        }
        String raw = args.get("scope").getAsString();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return McpSettingsState.DataSourceScope.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid scope: " + raw + ". Allowed values: GLOBAL, PROJECT, ALL.");
        }
    }

    private McpMethodMetricsService metricsService() {
        return ApplicationManager.getApplication() == null
                ? null
                : ApplicationManager.getApplication().getService(McpMethodMetricsService.class);
    }

    private McpRuntimeLogService logService() {
        return ApplicationManager.getApplication() == null
                ? null
                : ApplicationManager.getApplication().getService(McpRuntimeLogService.class);
    }

    private void logInfo(String message) {
        McpRuntimeLogService service = logService();
        if (service != null) {
            service.info("router", message);
        }
    }


    private void logError(String message) {
        McpRuntimeLogService service = logService();
        if (service != null) {
            service.error("router", message);
        }
    }

    private Map<String, Object> mcpToolResult(Object value) {
        Map<String, Object> result = new HashMap<>();
        result.put("content", List.of(Map.of(
                "type", "text",
                "text", GSON.toJson(value)
        )));
        return result;
    }

    private String ok(JsonElement id, Object result) {
        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        if (id != null) {
            resp.add("id", id);
        }
        resp.add("result", GSON.toJsonTree(result));
        return GSON.toJson(resp);
    }

    private String error(JsonElement id, int code, String message) {
        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        if (id != null) {
            resp.add("id", id);
        }
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        resp.add("error", err);
        return GSON.toJson(resp);
    }
}
