package com.skrstop.ide.databasemcp.mcpserver

import com.google.gson.Gson
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.project.ProjectManager
import com.skrstop.ide.databasemcp.db.IdeDatabaseFacade
import com.skrstop.ide.databasemcp.mcp.McpToolDefinitions
import com.skrstop.ide.databasemcp.service.McpRuntimeLogService
import com.skrstop.ide.databasemcp.settings.McpSettingsState

private val gson = Gson()

class DatabaseMcpToolset : McpToolset {
    private val facade = IdeDatabaseFacade()

    init {
        McpRuntimeLogService.getInstance().info(
            "jetbrains official McpServer",
            "Database MCP toolset loaded via Kotlin implementation."
        )
    }

    @McpTool(name = McpToolDefinitions.TOOL_LIST_DATASOURCES)
    @McpDescription(description = McpToolDefinitions.KT_DESC_LIST_DATASOURCES)
    suspend fun database_list_datasources(project: String?, scope: String?): String {
        val resolvedScope = parseScope(scope)
        val result = facade.listDataSources(resolveProjectHint(project), resolvedScope)
        return gson.toJson(result)
    }

    @McpTool(name = McpToolDefinitions.TOOL_LIST_DATABASES)
    @McpDescription(description = McpToolDefinitions.KT_DESC_LIST_DATABASES)
    suspend fun database_list_databases(project: String?, scope: String?, dataSource: String): String {
        requireText(dataSource, "dataSource")
        val resolvedScope = parseScope(scope)
        val result = facade.listDatabases(resolveProjectHint(project), dataSource, resolvedScope)
        return gson.toJson(result)
    }

    @McpTool(name = McpToolDefinitions.TOOL_EXECUTE_SQL_QUERY)
    @McpDescription(description = McpToolDefinitions.KT_DESC_EXECUTE_SQL_QUERY)
    suspend fun database_execute_sql_query(
        project: String?,
        scope: String?,
        dataSource: String,
        sql: String,
        maxRows: Int?
    ): String {
        requireText(dataSource, "dataSource")
        requireText(sql, "sql")
        val resolvedScope = parseScope(scope)
        val effectiveMaxRows = maxRows ?: 200
        val result =
            facade.executeQuerySql(resolveProjectHint(project), dataSource, sql, effectiveMaxRows, resolvedScope)
        return gson.toJson(result)
    }

    @McpTool(name = McpToolDefinitions.TOOL_EXECUTE_SQL_DML)
    @McpDescription(description = McpToolDefinitions.KT_DESC_EXECUTE_SQL_DML)
    suspend fun database_execute_sql_dml(project: String?, scope: String?, dataSource: String, sql: String): String {
        requireText(dataSource, "dataSource")
        requireText(sql, "sql")
        val resolvedScope = parseScope(scope)
        val result = facade.executeDmlSql(resolveProjectHint(project), dataSource, sql, resolvedScope)
        return gson.toJson(result)
    }

    @McpTool(name = McpToolDefinitions.TOOL_EXECUTE_SQL_DDL)
    @McpDescription(description = McpToolDefinitions.KT_DESC_EXECUTE_SQL_DDL)
    suspend fun database_execute_sql_ddl(project: String?, scope: String?, dataSource: String, sql: String): String {
        requireText(dataSource, "dataSource")
        requireText(sql, "sql")
        val resolvedScope = parseScope(scope)
        val result = facade.executeDdlSql(resolveProjectHint(project), dataSource, sql, resolvedScope)
        return gson.toJson(result)
    }

    @McpTool(name = McpToolDefinitions.TOOL_EXECUTE_NOSQL_QUERY)
    @McpDescription(description = McpToolDefinitions.KT_DESC_EXECUTE_NOSQL_QUERY)
    suspend fun database_execute_nosql_query(
        project: String?,
        scope: String?,
        dataSource: String,
        statement: String,
        maxRows: Int?
    ): String {
        requireText(dataSource, "dataSource")
        requireText(statement, "statement")
        val resolvedScope = parseScope(scope)
        val effectiveMaxRows = maxRows ?: 200
        val result = facade.executeNoSqlQuery(
            resolveProjectHint(project),
            dataSource,
            statement,
            effectiveMaxRows,
            resolvedScope
        )
        return gson.toJson(result)
    }

    @McpTool(name = McpToolDefinitions.TOOL_EXECUTE_NOSQL_WRITE_DELETE)
    @McpDescription(description = McpToolDefinitions.KT_DESC_EXECUTE_NOSQL_WRITE_DELETE)
    suspend fun database_execute_nosql_write_delete(
        project: String?,
        scope: String?,
        dataSource: String,
        statement: String
    ): String {
        requireText(dataSource, "dataSource")
        requireText(statement, "statement")
        val resolvedScope = parseScope(scope)
        val result = facade.executeNoSqlWriteDelete(resolveProjectHint(project), dataSource, statement, resolvedScope)
        return gson.toJson(result)
    }

    private fun parseScope(scope: String?): McpSettingsState.DataSourceScope? {
        if (scope.isNullOrBlank()) return null
        return try {
            // 注意：确保 Java 端的 DataSourceScope 是 public enum
            McpSettingsState.DataSourceScope.valueOf(scope.trim().uppercase())
        } catch (_: Exception) {
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
}