//package com.skrstop.ide.databasemcp.mcpserver;
//
//import com.google.gson.Gson;
//import com.intellij.mcpserver.McpToolset;
//import com.intellij.mcpserver.annotations.McpDescription;
//import com.intellij.mcpserver.annotations.McpTool;
//import com.intellij.openapi.project.Project;
//import com.intellij.openapi.project.ProjectManager;
//import com.skrstop.ide.databasemcp.db.IdeDatabaseFacade;
//import com.skrstop.ide.databasemcp.service.McpRuntimeLogService;
//import com.skrstop.ide.databasemcp.settings.McpSettingsState;
//import org.jetbrains.annotations.NotNull;
//import org.jetbrains.annotations.Nullable;
//
//import java.util.List;
//import java.util.Map;
//import java.util.Objects;
//
//public final class DatabaseMcpToolset implements McpToolset {
//    private static final McpRuntimeLogService LOG = McpRuntimeLogService.getInstance();
//    private static final Gson GSON = new Gson();
//    private final IdeDatabaseFacade facade = new IdeDatabaseFacade();
//
//    public DatabaseMcpToolset() {
//        LOG.info("jetbrains official McpServer", "Database MCP toolset loaded for official MCP Server integration.");
//    }
//
//    @McpTool(name = "database.list_data_sources")
//    @McpDescription(description = "List available IDE-managed data sources including inferred database type.")
//    public String listDataSources(@Nullable String projectPath, @Nullable String scope) {
//        McpSettingsState.DataSourceScope resolvedScope = parseScope(scope);
//        List<Map<String, Object>> result = facade.listDataSources(resolveProjectHint(projectPath), resolvedScope);
//        return GSON.toJson(result);
//    }
//
//    @McpTool(name = "database.list_databases")
//    @McpDescription(description = "List catalogs/schemas for a selected IDE data source.")
//    public String listDatabases(@NotNull String dataSource, @Nullable String projectPath, @Nullable String scope) {
//        requireText(dataSource, "dataSource");
//        McpSettingsState.DataSourceScope resolvedScope = parseScope(scope);
//        List<Map<String, Object>> result = facade.listDatabases(resolveProjectHint(projectPath), dataSource, resolvedScope);
//        return GSON.toJson(result);
//    }
//
//    @McpTool(name = "database.execute_query")
//    @McpDescription(description = "Execute read-only SQL query such as SELECT or WITH and return rows.")
//    public String executeQuery(@NotNull String dataSource, @NotNull String sql, @Nullable Integer maxRows, @Nullable String projectPath, @Nullable String scope) {
//        requireText(dataSource, "dataSource");
//        requireText(sql, "sql");
//        McpSettingsState.DataSourceScope resolvedScope = parseScope(scope);
//        int effectiveMaxRows = maxRows == null ? 200 : maxRows;
//        Map<String, Object> result = facade.executeQuerySql(resolveProjectHint(projectPath), dataSource, sql, effectiveMaxRows, resolvedScope);
//        return GSON.toJson(result);
//    }
//
//    @McpTool(name = "database.execute_dml")
//    @McpDescription(description = "Execute DML SQL statements such as INSERT, UPDATE, DELETE, MERGE, or REPLACE.")
//    public String executeDml(@NotNull String dataSource, @NotNull String sql, @Nullable String projectPath, @Nullable String scope) {
//        requireText(dataSource, "dataSource");
//        requireText(sql, "sql");
//        McpSettingsState.DataSourceScope resolvedScope = parseScope(scope);
//        Map<String, Object> result = facade.executeDmlSql(resolveProjectHint(projectPath), dataSource, sql, resolvedScope);
//        return GSON.toJson(result);
//    }
//
//    @McpTool(name = "database.execute_ddl")
//    @McpDescription(description = "Execute DDL SQL statements such as CREATE, ALTER, DROP, TRUNCATE, or RENAME.")
//    public String executeDdl(@NotNull String dataSource, @NotNull String sql, @Nullable String projectPath, @Nullable String scope) {
//        requireText(dataSource, "dataSource");
//        requireText(sql, "sql");
//        McpSettingsState.DataSourceScope resolvedScope = parseScope(scope);
//        Map<String, Object> result = facade.executeDdlSql(resolveProjectHint(projectPath), dataSource, sql, resolvedScope);
//        return GSON.toJson(result);
//    }
//
//    private McpSettingsState.DataSourceScope parseScope(String scope) {
//        if (scope == null || scope.isBlank()) {
//            return null;
//        }
//        try {
//            return McpSettingsState.DataSourceScope.valueOf(scope.trim().toUpperCase());
//        } catch (IllegalArgumentException ex) {
//            throw new IllegalArgumentException("Invalid scope: " + scope + ". Allowed values: GLOBAL, PROJECT, ALL.");
//        }
//    }
//
//    private String resolveProjectHint(String projectPath) {
//        if (projectPath != null && !projectPath.isBlank()) {
//            return projectPath;
//        }
//
//        Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
//        if (openProjects.length == 0) {
//            return null;
//        }
//
//        Project first = openProjects[0];
//        if (first.getBasePath() != null && !first.getBasePath().isBlank()) {
//            return first.getBasePath();
//        }
//        return first.getName();
//    }
//
//    private void requireText(String value, String field) {
//        if (Objects.isNull(value) || value.isBlank()) {
//            throw new IllegalArgumentException("Missing required argument: " + field);
//        }
//    }
//}