package com.skrstop.ide.databasemcp.db;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.skrstop.ide.databasemcp.enums.NoSqlMode;
import com.skrstop.ide.databasemcp.enums.SqlMode;
import com.skrstop.ide.databasemcp.service.McpRuntimeLogService;
import com.skrstop.ide.databasemcp.settings.McpSettingsState;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public class IdeDatabaseFacade {
    private static final int DEFAULT_ROW_SIZE = 10;

    private final DataSourceDiscoveryUtil discoveryUtil = new DataSourceDiscoveryUtil();
    private final JdbcConnectionUtil connectionUtil = new JdbcConnectionUtil();

    public List<Map<String, Object>> listDataSources(String projectHint) {
        return listDataSources(projectHint, null);
    }

    /**
     * 列出所有匹配 projectHint 的项目下的数据源。
     *
     * <p>当 projectHint 为空或存在同名项目时，会合并多个项目的数据源，每条记录附带
     * {@code project}（项目名）和 {@code projectPath}（项目路径）字段。
     * 当 scope 为 GLOBAL 时忽略 projectHint，直接查询全局数据源。</p>
     *
     * @param projectHint   项目名称或路径提示；为空则返回全部已打开项目的数据源
     * @param overrideScope 数据源 scope 覆盖；null 时使用全局配置
     * @return 数据源列表，每条 Map 包含 name/url/driverClass/type/scope/version
     * 以及 project/projectPath（GLOBAL scope 下不包含后两个字段）
     */
    public List<Map<String, Object>> listDataSources(String projectHint, McpSettingsState.DataSourceScope overrideScope) {
        McpSettingsState.DataSourceScope scope = resolveScope(overrideScope);
        List<Map<String, Object>> result = new ArrayList<>();

        if (scope == McpSettingsState.DataSourceScope.GLOBAL) {
            // 全局 scope 不区分项目，project 传 null
            try {
                List<DataSourceDiscoveryUtil.ScopedDataSource> scopedDataSources =
                        discoveryUtil.findScopedDataSources(null, scope);
                for (DataSourceDiscoveryUtil.ScopedDataSource scoped : scopedDataSources) {
                    result.add(mapDataSourceRow(scoped, null));
                }
            } catch (IllegalStateException ex) {
                McpRuntimeLogService.logWarn("facade", "Data source discovery failed: " + ex.getMessage());
                result.add(Map.of("name", "__discovery_error__", "message", ex.getMessage()));
            }
            return result;
        }

        // PROJECT scope：按 hint 解析多个项目，逐一采集并合并
        List<Project> projects = resolveProjects(projectHint);
        if (projects.isEmpty()) {
            result.add(Map.of("name", "__discovery_error__", "message", "No open projects found"));
            return result;
        }

        for (Project project : projects) {
            try {
                List<DataSourceDiscoveryUtil.ScopedDataSource> scopedDataSources =
                        discoveryUtil.findScopedDataSources(project, scope);
                for (DataSourceDiscoveryUtil.ScopedDataSource scoped : scopedDataSources) {
                    result.add(mapDataSourceRow(scoped, project));
                }
            } catch (IllegalStateException ex) {
                McpRuntimeLogService.logWarn("facade",
                        "Data source discovery failed for project [" + project.getName() + "]: " + ex.getMessage());
                Map<String, Object> errorRow = new HashMap<>();
                errorRow.put("name", "__discovery_error__");
                errorRow.put("project", project.getName());
                errorRow.put("projectPath", project.getBasePath());
                errorRow.put("message", ex.getMessage());
                result.add(errorRow);
            }
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

    /**
     * 使用 {@link PreparedStatement} 执行带参数绑定的查询 SQL。
     *
     * <p>专为自定义 MCP Tool 提供，SQL 中 {@code ${name}} 占位符必须由调用方在传入前替换为 {@code ?}，
     * 并按出现顺序提供 {@code bindValues}。</p>
     *
     * @param projectHint    项目路径提示，可为空
     * @param dataSourceName 数据源名称
     * @param sql            已经把 {@code ${...}} 替换为 {@code ?} 的预编译 SQL
     * @param bindValues     按 {@code ?} 顺序的绑定值，可为 {@code null}
     * @param maxRows        最大返回行数
     * @param overrideScope  数据源 scope 覆盖
     * @return 与 {@link #executeQuerySql} 相同结构的结果 Map
     */
    public Map<String, Object> executeQuerySqlPrepared(String projectHint, String dataSourceName, String sql,
                                                       List<Object> bindValues, int maxRows,
                                                       McpSettingsState.DataSourceScope overrideScope) {
        return executePreparedInternal(projectHint, dataSourceName, sql, bindValues, maxRows, overrideScope, SqlMode.QUERY);
    }

    /**
     * 使用 {@link PreparedStatement} 执行带参数绑定的 DML SQL（INSERT / UPDATE / DELETE）。
     *
     * <p>专为自定义 MCP Tool DML 模式提供，不返回结果集，仅返回影响行数（affectedRows）。</p>
     *
     * @param projectHint    项目路径提示，可为空
     * @param dataSourceName 数据源名称
     * @param sql            已经把 {@code ${...}} 替换为 {@code ?} 的预编译 DML SQL
     * @param bindValues     按 {@code ?} 顺序的绑定值，可为 {@code null}
     * @param overrideScope  数据源 scope 覆盖
     * @return 包含 {@code affectedRows} 的结果 Map
     */
    public Map<String, Object> executeDmlSqlPrepared(String projectHint, String dataSourceName, String sql,
                                                     List<Object> bindValues,
                                                     McpSettingsState.DataSourceScope overrideScope) {
        return executePreparedInternal(projectHint, dataSourceName, sql, bindValues, 0, overrideScope, SqlMode.DML);
    }

    /**
     * PreparedStatement 执行的统一内部方法，支持 QUERY 和 DML 两种模式。
     */
    private Map<String, Object> executePreparedInternal(String projectHint, String dataSourceName, String sql,
                                                        List<Object> bindValues, int maxRows,
                                                        McpSettingsState.DataSourceScope overrideScope,
                                                        SqlMode mode) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL must not be empty");
        }
        Object dataSource = resolveRequiredDataSource(projectHint, dataSourceName, overrideScope);

        Map<String, Object> payload = new HashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = connectionUtil.openConnection(dataSource, dataSourceName);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (mode == SqlMode.QUERY && maxRows > 0) {
                ps.setMaxRows(Math.max(DEFAULT_ROW_SIZE, maxRows));
            }
            if (bindValues != null) {
                for (int i = 0; i < bindValues.size(); i++) {
                    ps.setObject(i + 1, bindValues.get(i));
                }
            }
            if (mode == SqlMode.DML) {
                int affectedRows = ps.executeUpdate();
                payload.put("hasResultSet", false);
                payload.put("affectedRows", affectedRows);
                payload.put("updateCount", affectedRows);
            } else {
                boolean hasResultSet = ps.execute();
                if (hasResultSet) {
                    rows = readResultSetRows(ps.getResultSet());
                }
                payload.put("hasResultSet", hasResultSet);
                payload.put("updateCount", hasResultSet ? -1 : ps.getUpdateCount());
            }
            payload.put("mode", mode.name());
        } catch (Exception ex) {
            throw new IllegalStateException("SQL execution failed: " + ex.getMessage(), ex);
        }

        payload.put("dataSource", dataSourceName);
        payload.put("rowCount", rows.size());
        payload.put("rows", rows);
        return payload;
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
        McpSettingsState.DataSourceScope scope = resolveScope(overrideScope);

        if (scope == McpSettingsState.DataSourceScope.GLOBAL) {
            // 全局 scope 不依赖 project
            Object dataSource = findDataSourceByNameInProject(null, dataSourceName, scope);
            if (dataSource == null) {
                throw new IllegalArgumentException("Data source not found: " + dataSourceName);
            }
            return dataSource;
        }

        // PROJECT/ALL scope：先校验 projectHint 本身是否有歧义（同名不同路径），再查找数据源
        if (projectHint != null && !projectHint.isBlank()) {
            checkProjectHintAmbiguity(projectHint);
        }

        // 遍历所有匹配项目，收集全部命中的数据源
        List<Project> projects = resolveProjects(projectHint);
        List<Map.Entry<Project, Object>> matches = new ArrayList<>();
        for (Project project : projects) {
            Object ds = findDataSourceByNameInProject(project, dataSourceName, scope);
            if (ds != null) {
                matches.add(Map.entry(project, ds));
            }
        }

        if (matches.isEmpty()) {
            throw new IllegalArgumentException("Data source not found: " + dataSourceName);
        }

        // 数据源在多个项目中同名 → 歧义，要求调用方指定 project
        if (matches.size() > 1) {
            String projectList = matches.stream()
                    .map(e -> "\"" + e.getKey().getName() + "\" (path: " + e.getKey().getBasePath() + ")")
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException(
                    "Ambiguous data source \"" + dataSourceName + "\": found in multiple projects [" + projectList + "]. "
                            + "Please specify the 'project' parameter using the project path to disambiguate.");
        }

        return matches.get(0).getValue();
    }

    /**
     * 检查 projectHint 是否匹配多个同名但不同路径的项目。
     *
     * <p>当 hint 为路径时（匹配 {@link Project#getBasePath()}）可唯一定位，无需检查；
     * 当 hint 为项目名且存在多个同名项目时，抛出歧义异常并列出各项目路径，
     * 引导调用方改用路径作为 project 参数。</p>
     *
     * @param projectHint 非空的项目名称或路径
     * @throws IllegalArgumentException 当项目名称匹配到多个不同路径的项目时
     */
    private void checkProjectHintAmbiguity(String projectHint) {
        Project[] opened = ProjectManager.getInstance().getOpenProjects();
        // 检查是否有项目的 basePath 与 hint 完全匹配：路径匹配则唯一，无歧义
        boolean matchedByPath = Arrays.stream(opened)
                .anyMatch(p -> Objects.equals(p.getBasePath(), projectHint));
        if (matchedByPath) {
            return;
        }
        // hint 作为项目名匹配，收集所有同名项目
        List<Project> byName = Arrays.stream(opened)
                .filter(p -> Objects.equals(p.getName(), projectHint))
                .collect(Collectors.toList());
        if (byName.size() > 1) {
            String pathList = byName.stream()
                    .map(p -> "\"" + p.getBasePath() + "\"")
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException(
                    "Ambiguous project name \"" + projectHint + "\": multiple projects share this name. "
                            + "Please use the project path instead. Available paths: [" + pathList + "].");
        }
    }

    /**
     * 在指定项目（或全局）中按名称查找数据源。
     *
     * @param project 目标项目，null 表示全局 scope
     * @param name    数据源名称
     * @param scope   数据源 scope
     * @return 匹配的数据源对象，未找到时返回 null
     */
    private Object findDataSourceByNameInProject(Project project, String name, McpSettingsState.DataSourceScope scope) {
        return discoveryUtil.findScopedDataSources(project, scope).stream()
                .map(DataSourceDiscoveryUtil.ScopedDataSource::delegate)
                .filter(ds -> Objects.equals(DataSourceTypeUtil.resolveDataSourceName(ds), name))
                .findFirst()
                .orElse(null);
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
            // 预先缓存列标签，避免在 while 循环内对每行重复调用 getColumnLabel(i)
            String[] labels = new String[columns];
            for (int i = 0; i < columns; i++) {
                labels[i] = md.getColumnLabel(i + 1);
            }
            while (resultSet.next()) {
                Map<String, Object> row = new HashMap<>(columns * 2);
                for (int i = 0; i < columns; i++) {
                    row.put(labels[i], resultSet.getObject(i + 1));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private Map<String, Object> mapDataSourceRow(DataSourceDiscoveryUtil.ScopedDataSource scoped, Project project) {
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
        // 当存在 project 上下文时，附带项目信息便于多项目场景下区分来源
        if (project != null) {
            row.put("project", project.getName());
            row.put("projectPath", project.getBasePath());
        }
        return row;
    }

    private McpSettingsState.DataSourceScope resolveScope(McpSettingsState.DataSourceScope overrideScope) {
        return overrideScope == null ? McpSettingsState.getInstance().getDataSourceScope() : overrideScope;
    }

    /**
     * 按 projectHint 解析多个匹配的已打开项目。
     *
     * <ul>
     *   <li>hint 为空 → 返回全部已打开项目（多项目合并场景）</li>
     *   <li>hint 有值 → 返回所有名称或路径匹配的项目（同名项目会全部返回）</li>
     *   <li>无任何匹配时 → 回退为全部已打开项目</li>
     * </ul>
     *
     * @param projectHint 项目名称或 basePath 提示，可为 null
     * @return 匹配的项目列表，永不为 null
     */
    private List<Project> resolveProjects(String projectHint) {
        Project[] opened = ProjectManager.getInstance().getOpenProjects();
        if (opened.length == 0) {
            return Collections.emptyList();
        }
        if (projectHint == null || projectHint.isBlank()) {
            // 未指定 hint，返回所有已打开的项目
            return Arrays.asList(opened);
        }
        List<Project> matched = Arrays.stream(opened)
                .filter(p -> Objects.equals(p.getName(), projectHint)
                        || Objects.equals(p.getBasePath(), projectHint))
                .collect(Collectors.toList());
        // 若无精确匹配，回退为全部项目
        return matched.isEmpty() ? Arrays.asList(opened) : matched;
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
