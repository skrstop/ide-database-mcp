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
                case McpToolDefinitions.TOOL_LIST_DATA_SOURCES -> {
                    String project = args.has("project") ? args.get("project").getAsString() : "";
                    McpSettingsState.DataSourceScope scope = parseScopeArg(args);
                    List<Map<String, Object>> dataSources = databaseFacade.listDataSources(project, scope);
                    logInfo("Executed tool " + McpToolDefinitions.TOOL_LIST_DATA_SOURCES);
                    yield ok(id, mcpToolResult(dataSources));
                }
                case McpToolDefinitions.TOOL_LIST_DATABASES -> {
                    String project = args.has("project") ? args.get("project").getAsString() : "";
                    String dataSource = requiredString(args, "dataSource");
                    McpSettingsState.DataSourceScope scope = parseScopeArg(args);
                    List<Map<String, Object>> databases = databaseFacade.listDatabases(project, dataSource, scope);
                    logInfo("Executed tool " + McpToolDefinitions.TOOL_LIST_DATABASES + " on data source: " + dataSource);
                    yield ok(id, mcpToolResult(databases));
                }
                case McpToolDefinitions.TOOL_EXECUTE_QUERY -> {
                    String project = args.has("project") ? args.get("project").getAsString() : "";
                    String dataSource = requiredString(args, "dataSource");
                    String sql = requiredString(args, "sql");
                    int maxRows = args.has("maxRows") ? args.get("maxRows").getAsInt() : 200;
                    McpSettingsState.DataSourceScope scope = parseScopeArg(args);
                    Map<String, Object> queryResult = databaseFacade.executeQuerySql(project, dataSource, sql, maxRows, scope);
                    logInfo("Executed tool " + McpToolDefinitions.TOOL_EXECUTE_QUERY + " on data source: " + dataSource);
                    yield ok(id, mcpToolResult(queryResult));
                }
                case McpToolDefinitions.TOOL_EXECUTE_DML -> {
                    String project = args.has("project") ? args.get("project").getAsString() : "";
                    String dataSource = requiredString(args, "dataSource");
                    String sql = requiredString(args, "sql");
                    McpSettingsState.DataSourceScope scope = parseScopeArg(args);
                    Map<String, Object> dmlResult = databaseFacade.executeDmlSql(project, dataSource, sql, scope);
                    logInfo("Executed tool " + McpToolDefinitions.TOOL_EXECUTE_DML + " on data source: " + dataSource);
                    yield ok(id, mcpToolResult(dmlResult));
                }
                case McpToolDefinitions.TOOL_EXECUTE_DDL -> {
                    String project = args.has("project") ? args.get("project").getAsString() : "";
                    String dataSource = requiredString(args, "dataSource");
                    String sql = requiredString(args, "sql");
                    McpSettingsState.DataSourceScope scope = parseScopeArg(args);
                    Map<String, Object> ddlResult = databaseFacade.executeDdlSql(project, dataSource, sql, scope);
                    logInfo("Executed tool " + McpToolDefinitions.TOOL_EXECUTE_DDL + " on data source: " + dataSource);
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
        // Delegate to centralized definitions
        List<Map<String, Object>> tools = McpToolDefinitions.getTools();

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
