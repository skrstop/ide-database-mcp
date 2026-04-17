package com.skrstop.ide.databasemcp.mcp;

import com.google.gson.*;
import com.intellij.openapi.application.ApplicationManager;
import com.skrstop.ide.databasemcp.db.IdeDatabaseFacade;
import com.skrstop.ide.databasemcp.service.McpMethodMetricsService;
import com.skrstop.ide.databasemcp.service.McpRuntimeLogService;
import com.skrstop.ide.databasemcp.settings.McpSettingsState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class McpProtocolRouter {
    private static final Gson GSON = new Gson();
    private final IdeDatabaseFacade databaseFacade;
    // Tool names and metadata are centralized in McpToolDefinitions; use those constants directly where needed.

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
            McpRuntimeLogService.logError("router", "Protocol handle failed: " + ex.getMessage());
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
                case McpToolDefinitions.TOOL_LIST_DATASOURCES -> {
                    String project = args.has("project") ? args.get("project").getAsString() : "";
                    McpSettingsState.DataSourceScope scope = parseScopeArg(args);
                    List<Map<String, Object>> dataSources = databaseFacade.listDataSources(project, scope);
                    McpRuntimeLogService.logInfo("router", "Executed tool " + McpToolDefinitions.TOOL_LIST_DATASOURCES);
                    yield ok(id, mcpToolResult(dataSources));
                }
                case McpToolDefinitions.TOOL_LIST_DATABASES -> {
                    String project = args.has("project") ? args.get("project").getAsString() : "";
                    String dataSource = requiredString(args, "dataSource");
                    McpSettingsState.DataSourceScope scope = parseScopeArg(args);
                    List<Map<String, Object>> databases = databaseFacade.listDatabases(project, dataSource, scope);
                    McpRuntimeLogService.logInfo("router", "Executed tool " + McpToolDefinitions.TOOL_LIST_DATABASES + " on data source: " + dataSource);
                    yield ok(id, mcpToolResult(databases));
                }
                case McpToolDefinitions.TOOL_EXECUTE_SQL_QUERY -> {
                    String project = args.has("project") ? args.get("project").getAsString() : "";
                    String dataSource = requiredString(args, "dataSource");
                    String sql = requiredString(args, "sql");
                    int maxRows = args.has("maxRows") ? args.get("maxRows").getAsInt() : 200;
                    McpSettingsState.DataSourceScope scope = parseScopeArg(args);
                    Map<String, Object> queryResult = databaseFacade.executeQuerySql(project, dataSource, sql, maxRows, scope);
                    McpRuntimeLogService.logInfo("router", "Executed tool " + McpToolDefinitions.TOOL_EXECUTE_SQL_QUERY + " on data source: " + dataSource);
                    yield ok(id, mcpToolResult(queryResult));
                }
                case McpToolDefinitions.TOOL_EXECUTE_SQL_DML -> {
                    String project = args.has("project") ? args.get("project").getAsString() : "";
                    String dataSource = requiredString(args, "dataSource");
                    String sql = requiredString(args, "sql");
                    McpSettingsState.DataSourceScope scope = parseScopeArg(args);
                    Map<String, Object> dmlResult = databaseFacade.executeDmlSql(project, dataSource, sql, scope);
                    McpRuntimeLogService.logInfo("router", "Executed tool " + McpToolDefinitions.TOOL_EXECUTE_SQL_DML + " on data source: " + dataSource);
                    yield ok(id, mcpToolResult(dmlResult));
                }
                case McpToolDefinitions.TOOL_EXECUTE_SQL_DDL -> {
                    String project = args.has("project") ? args.get("project").getAsString() : "";
                    String dataSource = requiredString(args, "dataSource");
                    String sql = requiredString(args, "sql");
                    McpSettingsState.DataSourceScope scope = parseScopeArg(args);
                    Map<String, Object> ddlResult = databaseFacade.executeDdlSql(project, dataSource, sql, scope);
                    McpRuntimeLogService.logInfo("router", "Executed tool " + McpToolDefinitions.TOOL_EXECUTE_SQL_DDL + " on data source: " + dataSource);
                    yield ok(id, mcpToolResult(ddlResult));
                }
                case McpToolDefinitions.TOOL_EXECUTE_NOSQL_QUERY -> {
                    String project = args.has("project") ? args.get("project").getAsString() : "";
                    String dataSource = requiredString(args, "dataSource");
                    String statement = requiredString(args, "statement");
                    int maxRows = args.has("maxRows") ? args.get("maxRows").getAsInt() : 200;
                    McpSettingsState.DataSourceScope scope = parseScopeArg(args);
                    Map<String, Object> noSqlQueryResult = databaseFacade.executeNoSqlQuery(project, dataSource, statement, maxRows, scope);
                    McpRuntimeLogService.logInfo("router", "Executed tool " + McpToolDefinitions.TOOL_EXECUTE_NOSQL_QUERY + " on data source: " + dataSource);
                    yield ok(id, mcpToolResult(noSqlQueryResult));
                }
                case McpToolDefinitions.TOOL_EXECUTE_NOSQL_WRITE_DELETE -> {
                    String project = args.has("project") ? args.get("project").getAsString() : "";
                    String dataSource = requiredString(args, "dataSource");
                    String statement = requiredString(args, "statement");
                    McpSettingsState.DataSourceScope scope = parseScopeArg(args);
                    Map<String, Object> noSqlWriteResult = databaseFacade.executeNoSqlWriteDelete(project, dataSource, statement, scope);
                    McpRuntimeLogService.logInfo("router", "Executed tool " + McpToolDefinitions.TOOL_EXECUTE_NOSQL_WRITE_DELETE + " on data source: " + dataSource);
                    yield ok(id, mcpToolResult(noSqlWriteResult));
                }
                case McpToolDefinitions.TOOL_LIST_TABLE_SCHEMA -> {
                    String project = args.has("project") ? args.get("project").getAsString() : "";
                    String dataSource = requiredString(args, "dataSource");
                    String catalog = args.has("catalog") ? args.get("catalog").getAsString() : null;
                    String schema = args.has("schema") ? args.get("schema").getAsString() : null;
                    List<String> keywords = parseStringArray(args, "keywords");
                    String tablePrefix = args.has("tablePrefix") ? args.get("tablePrefix").getAsString() : null;
                    int maxTables = args.has("maxTables") ? args.get("maxTables").getAsInt() : 20;
                    boolean includeColumns = args.has("includeColumns") && args.get("includeColumns").getAsBoolean();
                    boolean includeIndexes = args.has("includeIndexes") && args.get("includeIndexes").getAsBoolean();
                    McpSettingsState.DataSourceScope scope = parseScopeArg(args);
                    Map<String, Object> sampleResult = databaseFacade.listTableSchema(
                            project, dataSource, catalog, schema, keywords, tablePrefix, maxTables, includeColumns, includeIndexes, scope
                    );
                    McpRuntimeLogService.logInfo("router", "Executed tool " + McpToolDefinitions.TOOL_LIST_TABLE_SCHEMA
                            + " on data source: " + dataSource + ", sampled=" + sampleResult.get("sampledCount"));
                    yield ok(id, mcpToolResult(sampleResult));
                }
                default -> error(id, -32602, "Unsupported tool: " + toolName);
            };
        } catch (Exception ex) {
            McpRuntimeLogService.logError("router", "Tool call failed: " + toolName + ", error=" + ex.getMessage());
            return error(id, -32000, ex.getMessage());
        } finally {
            recordInvocation("tool:" + toolName, System.nanoTime() - startNanos);
        }
    }

    private String handleToolsList(JsonElement id) {
        // Delegate to centralized definitions
        List<Map<String, Object>> tools = McpToolDefinitions.getTools();
        return ok(id, Map.of("tools", tools));
    }


    private void recordInvocation(String key, long elapsedNanos) {
        McpMethodMetricsService metricsService = metricsService();
        if (metricsService == null) {
            return;
        }
        // 仅累加计数器，不再写运行时日志，避免每次调用产生无意义的日志写入与 UI 重绘
        metricsService.record(key, elapsedNanos);
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

    /**
     * 解析 JSON 参数中的字符串数组字段。
     * 支持 JSON 数组格式（["a","b"]）和逗号分隔字符串格式（"a,b"）两种输入方式。
     *
     * @param args      参数 JsonObject
     * @param fieldName 字段名
     * @return 解析后的字符串列表，字段不存在时返回空列表
     */
    private List<String> parseStringArray(JsonObject args, String fieldName) {
        if (!args.has(fieldName) || args.get(fieldName).isJsonNull()) {
            return new ArrayList<>();
        }
        JsonElement element = args.get(fieldName);
        List<String> result = new ArrayList<>();
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement item : array) {
                if (!item.isJsonNull()) {
                    String val = item.getAsString();
                    if (val != null && !val.isBlank()) {
                        result.add(val.trim());
                    }
                }
            }
        } else {
            // 降级处理：逗号分隔字符串
            String raw = element.getAsString();
            if (raw != null && !raw.isBlank()) {
                for (String part : raw.split(",")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        result.add(trimmed);
                    }
                }
            }
        }
        return result;
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
