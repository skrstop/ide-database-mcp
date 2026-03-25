package com.skrstop.ide.databasemcp.mcpserver

import com.google.gson.Gson
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.project.ProjectManager
import com.skrstop.ide.databasemcp.db.IdeDatabaseFacade
import com.skrstop.ide.databasemcp.service.McpRuntimeLogService
import com.skrstop.ide.databasemcp.settings.McpSettingsState

class DatabaseMcpToolset : McpToolset {
    private val facade = IdeDatabaseFacade()

    init {
        McpRuntimeLogService.getInstance().info(
            "jetbrains official McpServer",
            "Database MCP toolset loaded via Kotlin implementation."
        )
    }

    @McpTool(name = "database_list_data_sources")
    @McpDescription(description = "List available IDE-managed data sources.")
    suspend fun database_list_data_sources(projectPath: String?, scope: String?): String {
        val resolvedScope = parseScope(scope)
        val result = facade.listDataSources(resolveProjectHint(projectPath), resolvedScope)
        return GSON.toJson(result)
    }

    @McpTool(name = "database_list_databases")
    @McpDescription(description = "List catalogs/schemas for a selected IDE data source.")
    suspend fun database_list_databases(dataSource: String, projectPath: String?, scope: String?): String {
        requireText(dataSource, "dataSource")
        val resolvedScope = parseScope(scope)
        val result = facade.listDatabases(resolveProjectHint(projectPath), dataSource, resolvedScope)
        return GSON.toJson(result)
    }

    @McpTool(name = "database_execute_query")
    @McpDescription(description = "Execute read-only SQL query.")
    suspend fun database_execute_query(
        dataSource: String,
        sql: String,
        maxRows: Int?,
        projectPath: String?,
        scope: String?
    ): String {
        requireText(dataSource, "dataSource")
        requireText(sql, "sql")
        val resolvedScope = parseScope(scope)
        val effectiveMaxRows = maxRows ?: 200
        val result =
            facade.executeQuerySql(resolveProjectHint(projectPath), dataSource, sql, effectiveMaxRows, resolvedScope)
        return GSON.toJson(result)
    }

    @McpTool(name = "database_execute_dml")
    @McpDescription(description = "Execute DML SQL statements.")
    suspend fun database_execute_dml(dataSource: String, sql: String, projectPath: String?, scope: String?): String {
        requireText(dataSource, "dataSource")
        requireText(sql, "sql")
        val resolvedScope = parseScope(scope)
        val result = facade.executeDmlSql(resolveProjectHint(projectPath), dataSource, sql, resolvedScope)
        return GSON.toJson(result)
    }

    @McpTool(name = "database_execute_ddl")
    @McpDescription(description = "Execute DDL SQL statements.")
    suspend fun database_execute_ddl(dataSource: String, sql: String, projectPath: String?, scope: String?): String {
        requireText(dataSource, "dataSource")
        requireText(sql, "sql")
        val resolvedScope = parseScope(scope)
        val result = facade.executeDdlSql(resolveProjectHint(projectPath), dataSource, sql, resolvedScope)
        return GSON.toJson(result)
    }

    private fun parseScope(scope: String?): McpSettingsState.DataSourceScope? {
        if (scope.isNullOrBlank()) return null
        return try {
            // 注意：确保 Java 端的 DataSourceScope 是 public enum
            McpSettingsState.DataSourceScope.valueOf(scope.trim().uppercase())
        } catch (ex: Exception) {
            null
        }
    }

    private fun resolveProjectHint(projectPath: String?): String? {
        if (!projectPath.isNullOrBlank()) return projectPath
        val openProjects = ProjectManager.getInstance().openProjects
        if (openProjects.isEmpty()) return null
        val first = openProjects[0]
        return first.basePath ?: first.name
    }

    private fun requireText(value: String?, field: String) {
        if (value.isNullOrBlank()) {
            throw IllegalArgumentException("Missing required argument: $field")
        }
    }

    companion object {
        // 使用 @JvmStatic 或直接引用
        private val GSON = Gson()
    }
}