package com.skrstop.ide.databasemcp.db;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.skrstop.ide.databasemcp.enums.NoSqlMode;
import com.skrstop.ide.databasemcp.enums.SqlMode;
import com.skrstop.ide.databasemcp.service.McpRuntimeLogService;
import com.skrstop.ide.databasemcp.settings.McpSettingsState;

import java.sql.*;
import java.util.*;

public class IdeDatabaseFacade {
    private static final int DEFAULT_ROW_SIZE = 10;

    private final DataSourceDiscoveryUtil discoveryUtil = new DataSourceDiscoveryUtil();
    private final JdbcConnectionUtil connectionUtil = new JdbcConnectionUtil();

    public List<Map<String, Object>> listDataSources(String projectHint) {
        return listDataSources(projectHint, null);
    }

    public List<Map<String, Object>> listDataSources(String projectHint, McpSettingsState.DataSourceScope overrideScope) {
        McpSettingsState.DataSourceScope scope = resolveScope(overrideScope);
        Project project = (scope == McpSettingsState.DataSourceScope.GLOBAL) ? null : resolveProject(projectHint);
        List<Map<String, Object>> result = new ArrayList<>();
        List<DataSourceDiscoveryUtil.ScopedDataSource> scopedDataSources;
        try {
            scopedDataSources = discoveryUtil.findScopedDataSources(project, scope);
        } catch (IllegalStateException ex) {
            McpRuntimeLogService.logWarn("facade", "Data source discovery failed: " + ex.getMessage());
            result.add(Map.of(
                    "name", "__discovery_error__",
                    "message", ex.getMessage()
            ));
            return result;
        }

        for (DataSourceDiscoveryUtil.ScopedDataSource scoped : scopedDataSources) {
            result.add(mapDataSourceRow(scoped));
        }
        return result;
    }

    public List<Map<String, Object>> listDatabases(String projectHint, String dataSourceName, McpSettingsState.DataSourceScope overrideScope) {
        Object dataSource = resolveRequiredDataSource(projectHint, dataSourceName, overrideScope);

        try (Connection conn = connectionUtil.openConnection(dataSource, dataSourceName)) {
            DatabaseMetaData metaData = conn.getMetaData();
            Map<String, Map<String, Object>> merged = new LinkedHashMap<>();

            try (ResultSet catalogs = metaData.getCatalogs()) {
                while (catalogs.next()) {
                    String name = catalogs.getString(1);
                    if (name != null && !name.isBlank()) {
                        merged.putIfAbsent(name, mapDatabase(name, "CATALOG"));
                    }
                }
            } catch (Exception ex) {
                McpRuntimeLogService.getInstance().error("Database", "connect db error：" + ex.getMessage());
            }

            try (ResultSet schemas = metaData.getSchemas()) {
                while (schemas.next()) {
                    String schema = schemas.getString("TABLE_SCHEM");
                    if (schema != null && !schema.isBlank()) {
                        merged.putIfAbsent(schema, mapDatabase(schema, "SCHEMA"));
                    }
                }
            } catch (Exception ignored) {
                // Some drivers don't support schemas.
            }

            return new ArrayList<>(merged.values());
        } catch (Exception ex) {
            throw new IllegalStateException("List databases failed: " + ex.getMessage(), ex);
        }
    }

    public Map<String, Object> executeQuerySql(String projectHint, String dataSourceName, String sql, int maxRows,
                                               McpSettingsState.DataSourceScope overrideScope) {
        return executeSqlInternal(projectHint, dataSourceName, sql, maxRows, overrideScope, SqlMode.QUERY);
    }

    public Map<String, Object> executeDmlSql(String projectHint, String dataSourceName, String sql,
                                             McpSettingsState.DataSourceScope overrideScope) {
        return executeSqlInternal(projectHint, dataSourceName, sql, 1, overrideScope, SqlMode.DML);
    }

    public Map<String, Object> executeDdlSql(String projectHint, String dataSourceName, String sql,
                                             McpSettingsState.DataSourceScope overrideScope) {
        return executeSqlInternal(projectHint, dataSourceName, sql, 1, overrideScope, SqlMode.DDL);
    }

    public Map<String, Object> executeNoSqlQuery(String projectHint, String dataSourceName, String statement, int maxRows,
                                                 McpSettingsState.DataSourceScope overrideScope) {
        return executeNoSqlInternal(projectHint, dataSourceName, statement, maxRows, overrideScope, NoSqlMode.QUERY);
    }

    public Map<String, Object> executeNoSqlWriteDelete(String projectHint, String dataSourceName, String statement,
                                                       McpSettingsState.DataSourceScope overrideScope) {
        return executeNoSqlInternal(projectHint, dataSourceName, statement, 1, overrideScope, NoSqlMode.WRITE_DELETE);
    }

    /**
     * Schema 智能采样：从大型企业数据库中按相关性评分提取局部 Schema。
     *
     * <p>通过关键词匹配（表名/列名/注释评分）+ 外键关联扩散算法，仅返回最相关的 Top-N 张表，
     * 有效压缩 AI Prompt 长度，解决百表级数据库导致 AI"变笨"的问题。
     *
     * @param projectHint    项目路径提示（可为空）
     * @param dataSourceName 数据源名称（来自 database_list_datasources 的结果）
     * @param catalog        catalog/database 过滤（可为 null）
     * @param schema         schema 过滤（可为 null）
     * @param keywords       相关性关键词列表（可为 null 或空）
     * @param tablePrefix    表名前缀过滤（可为 null）
     * @param maxTables      最多返回表数量，默认 20，范围 1-200
     * @param includeColumns 是否在结果中附带列（字段）详情，默认 false 时仅返回 columnCount
     * @param includeIndexes 是否附带索引信息
     * @param overrideScope  数据源 scope 覆盖（可为 null，使用全局配置）
     * @return 采样结果 Map，包含 totalTablesFound / sampledCount / tables
     */
    public Map<String, Object> listTableSchema(
            String projectHint,
            String dataSourceName,
            String catalog,
            String schema,
            List<String> keywords,
            String tablePrefix,
            int maxTables,
            boolean includeColumns,
            boolean includeIndexes,
            McpSettingsState.DataSourceScope overrideScope
    ) {
        Object dataSource = resolveRequiredDataSource(projectHint, dataSourceName, overrideScope);
        try (Connection conn = connectionUtil.openConnection(dataSource, dataSourceName)) {
            return SchemaSmartSampler.listTableSchema(
                    conn,
                    nullIfBlank(catalog),
                    nullIfBlank(schema),
                    keywords != null ? keywords : Collections.emptyList(),
                    nullIfBlank(tablePrefix),
                    Math.max(1, Math.min(200, maxTables)),
                    includeColumns,
                    includeIndexes
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Schema smart sample failed: " + ex.getMessage(), ex);
        }
    }

    public Map<String, Object> executeSql(String projectHint, String dataSourceName, String sql, int maxRows) {
        return executeQuerySql(projectHint, dataSourceName, sql, maxRows, null);
    }

    private Map<String, Object> executeSqlInternal(String projectHint, String dataSourceName, String sql, int maxRows,
                                                   McpSettingsState.DataSourceScope overrideScope, SqlMode mode) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL must not be empty");
        }

        Object dataSource = resolveRequiredDataSource(projectHint, dataSourceName, overrideScope);
        return executeStatementInternal(
                dataSource,
                dataSourceName,
                sql,
                maxRows,
                mode == SqlMode.QUERY,
                mode.name(),
                "SQL execution failed",
                null
        );
    }

    private Map<String, Object> executeNoSqlInternal(String projectHint, String dataSourceName, String statement, int maxRows,
                                                     McpSettingsState.DataSourceScope overrideScope, NoSqlMode mode) {
        if (statement == null || statement.isBlank()) {
            throw new IllegalArgumentException("Statement must not be empty");
        }

        Object dataSource = resolveRequiredDataSource(projectHint, dataSourceName, overrideScope);
        String url = DataSourceTypeUtil.resolveDataSourceUrl(dataSource);
        String driverClass = DataSourceTypeUtil.resolveDataSourceDriverClass(dataSource);
        String dataSourceType = DataSourceTypeUtil.inferDataSourceType(dataSource, url, driverClass);
        return executeStatementInternal(
                dataSource,
                dataSourceName,
                statement,
                maxRows,
                mode == NoSqlMode.QUERY,
                mode.name(),
                "NoSQL execution failed",
                dataSourceType
        );
    }

    private Object resolveRequiredDataSource(String projectHint, String dataSourceName, McpSettingsState.DataSourceScope overrideScope) {
        Project project = resolveProject(projectHint);
        Object dataSource = findDataSourceByName(project, dataSourceName, overrideScope);
        if (dataSource == null) {
            throw new IllegalArgumentException("Data source not found: " + dataSourceName);
        }
        return dataSource;
    }

    private Map<String, Object> executeStatementInternal(Object dataSource,
                                                         String dataSourceName,
                                                         String statement,
                                                         int maxRows,
                                                         boolean applyMaxRows,
                                                         String mode,
                                                         String errorPrefix,
                                                         String dataSourceType) {
        Map<String, Object> payload = new HashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();

        try (Connection conn = connectionUtil.openConnection(dataSource, dataSourceName);
             Statement stmt = conn.createStatement()) {
            if (applyMaxRows) {
                stmt.setMaxRows(Math.max(DEFAULT_ROW_SIZE, maxRows));
            }
            boolean hasResultSet = stmt.execute(statement);
            if (hasResultSet) {
                rows = readResultSetRows(stmt.getResultSet());
            }
            payload.put("hasResultSet", hasResultSet);
            payload.put("updateCount", hasResultSet ? -1 : stmt.getUpdateCount());
            payload.put("mode", mode);
        } catch (Exception ex) {
            throw new IllegalStateException(errorPrefix + ": " + ex.getMessage(), ex);
        }

        payload.put("dataSource", dataSourceName);
        if (dataSourceType != null && !dataSourceType.isBlank()) {
            payload.put("dataSourceType", dataSourceType);
        }
        payload.put("rowCount", rows.size());
        payload.put("rows", rows);
        return payload;
    }

    private List<Map<String, Object>> readResultSetRows(ResultSet rs) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (ResultSet resultSet = rs) {
            ResultSetMetaData md = resultSet.getMetaData();
            int columns = md.getColumnCount();
            while (resultSet.next()) {
                Map<String, Object> row = new HashMap<>();
                for (int i = 1; i <= columns; i++) {
                    row.put(md.getColumnLabel(i), resultSet.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private Map<String, Object> mapDataSourceRow(DataSourceDiscoveryUtil.ScopedDataSource scoped) {
        Object ds = scoped.delegate();
        String url = DataSourceTypeUtil.resolveDataSourceUrl(ds);
        String driverClass = DataSourceTypeUtil.resolveDataSourceDriverClass(ds);
        Map<String, Object> row = new HashMap<>();
        row.put("name", DataSourceTypeUtil.resolveDataSourceName(ds));
        row.put("url", url);
        row.put("driverClass", driverClass);
        row.put("type", DataSourceTypeUtil.inferDataSourceType(ds, url, driverClass));
        row.put("scope", scoped.scope().name());
        row.put("version", DataSourceTypeUtil.resolveDataSourceVersion(ds, url, driverClass));
        return row;
    }

    private McpSettingsState.DataSourceScope resolveScope(McpSettingsState.DataSourceScope overrideScope) {
        return overrideScope == null ? McpSettingsState.getInstance().getDataSourceScope() : overrideScope;
    }

    private Project resolveProject(String projectHint) {
        Project[] opened = ProjectManager.getInstance().getOpenProjects();
        if (opened.length == 0) {
            return null;
        }
        if (projectHint == null || projectHint.isBlank()) {
            return opened[0];
        }
        for (Project project : opened) {
            if (Objects.equals(project.getName(), projectHint) || Objects.equals(project.getBasePath(), projectHint)) {
                return project;
            }
        }
        return null;
    }

    private Object findDataSourceByName(Project project, String name, McpSettingsState.DataSourceScope overrideScope) {
        McpSettingsState.DataSourceScope scope = resolveScope(overrideScope);
        for (DataSourceDiscoveryUtil.ScopedDataSource scoped : discoveryUtil.findScopedDataSources(project, scope)) {
            String dsName = DataSourceTypeUtil.resolveDataSourceName(scoped.delegate());
            if (Objects.equals(dsName, name)) {
                return scoped.delegate();
            }
        }
        return null;
    }

    private static Map<String, Object> mapDatabase(String name, String type) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("type", type);
        return row;
    }

    /**
     * 将空白字符串转换为 null，用于 JDBC metadata 参数（传 null 表示不过滤）。
     */
    private static String nullIfBlank(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

}
