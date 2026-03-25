package com.skrstop.ide.databasemcp.db;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.skrstop.ide.databasemcp.service.McpRuntimeLogService;
import com.skrstop.ide.databasemcp.settings.McpSettingsState;

import java.sql.*;
import java.util.*;

public class IdeDatabaseFacade {
    private static final Logger LOG = Logger.getInstance(IdeDatabaseFacade.class);
    private static final int DEFAULT_ROW_SIZE = 10;

    private final DataSourceDiscoveryUtil discoveryUtil = new DataSourceDiscoveryUtil(LOG);
    private final JdbcConnectionUtil connectionUtil = new JdbcConnectionUtil(LOG);

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
            LOG.warn("Data source discovery failed: " + ex.getMessage());
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
        String dataSourceType = DataSourceTypeUtil.inferDataSourceType(
                DbReflectionUtil.invokeString(LOG, dataSource, "getUrl"),
                DbReflectionUtil.invokeString(LOG, dataSource, "getDriverClass")
        );
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
        String name = DbReflectionUtil.invokeString(LOG, ds, "getName");
        String url = DbReflectionUtil.invokeString(LOG, ds, "getUrl");
        String driver = DbReflectionUtil.invokeString(LOG, ds, "getDriverClass");
        Map<String, Object> row = new HashMap<>();
        row.put("name", name);
        row.put("url", url);
        row.put("driverClass", driver);
        row.put("type", DataSourceTypeUtil.inferDataSourceType(url, driver));
        row.put("scope", scoped.scope().name());
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
            String dsName = DbReflectionUtil.invokeString(LOG, scoped.delegate(), "getName");
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

    private enum SqlMode {
        QUERY,
        DML,
        DDL
    }

    private enum NoSqlMode {
        QUERY,
        WRITE_DELETE
    }
}
