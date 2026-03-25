package com.skrstop.ide.databasemcp.db;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.skrstop.ide.databasemcp.mcp.ReadOnlySqlGuard;
import com.skrstop.ide.databasemcp.service.McpRuntimeLogService;
import com.skrstop.ide.databasemcp.settings.McpSettingsState;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public class IdeDatabaseFacade {
    private static final Logger LOG = Logger.getInstance(IdeDatabaseFacade.class);

    private static final List<String> PROJECT_MANAGER_CLASS_CANDIDATES = List.of(
            "com.intellij.database.dataSource.LocalDataSourceManager",
            "com.intellij.database.dataSource.LocalDataSourceManagerImpl",
            "com.intellij.database.psi.DataSourceManager",
            "com.intellij.database.dataSource.DataSourceStorage",
            "com.intellij.database.dataSource.DataSourceStorage$App",
            "com.intellij.database.dataSource.DataSourceStorageShared$App",
            "com.intellij.database.dataSource.DataSourceModelStorageImpl$App"
    );

    private static final List<String> GLOBAL_MANAGER_CLASS_CANDIDATES = List.of(
            "com.intellij.database.dataSource.LocalDataSourceManager",
            "com.intellij.database.dataSource.LocalDataSourceManagerImpl",
            "com.intellij.database.psi.DataSourceManager",
            "com.intellij.database.dataSource.DataSourceStorage",
            "com.intellij.database.dataSource.DataSourceStorage$App",
            "com.intellij.database.dataSource.DataSourceStorageShared$App",
            "com.intellij.database.dataSource.DataSourceModelStorageImpl$App"
    );

    private static final String CUSTOM_DATA_SOURCE_TYPE = "Custom";
    private static final Set<String> DRIVER_SEGMENT_BLACKLIST = Set.of(
            "com", "org", "net", "io", "www", "jdbc", "drivers", "driver", "database", "thin",
            "data", "datasource", "access", "connection"
    );

    public List<Map<String, Object>> listDataSources(String projectHint) {
        return listDataSources(projectHint, null);
    }

    public List<Map<String, Object>> listDataSources(String projectHint, McpSettingsState.DataSourceScope overrideScope) {
        McpSettingsState.DataSourceScope scope = resolveScope(overrideScope);
//        McpSettingsState.DataSourceScope scope = McpSettingsState.DataSourceScope.ALL;
        // For GLOBAL scope we should not require or use a project
        Project project = (scope == McpSettingsState.DataSourceScope.GLOBAL) ? null : resolveProject(projectHint);
        List<Map<String, Object>> result = new ArrayList<>();
        List<ScopedDataSource> scopedDataSources;
        try {
            scopedDataSources = findScopedDataSources(project, scope);
        } catch (IllegalStateException ex) {
            LOG.warn("Data source discovery failed: " + ex.getMessage());
            result.add(Map.of(
                    "name", "__discovery_error__",
                    "message", ex.getMessage()
            ));
            return result;
        }

        for (ScopedDataSource scoped : scopedDataSources) {
            Object ds = scoped.delegate;
            Map<String, Object> row = new HashMap<>();
            String name = invokeString(ds, "getName");
            String url = invokeString(ds, "getUrl");
            String driver = invokeString(ds, "getDriverClass");
            row.put("name", name);
            row.put("url", url);
            row.put("driverClass", driver);
            row.put("type", inferDataSourceType(url, driver));
            row.put("scope", scoped.scope.name());
            result.add(row);
        }
        return result;
    }

    public List<Map<String, Object>> listDatabases(String projectHint, String dataSourceName, McpSettingsState.DataSourceScope overrideScope) {
        Project project = resolveProject(projectHint);
        Object dataSource = findDataSourceByName(project, dataSourceName, overrideScope);
        if (dataSource == null) {
            throw new IllegalArgumentException("Data source not found: " + dataSourceName);
        }

        try (Connection conn = openConnection(dataSource, dataSourceName)) {
            DatabaseMetaData metaData = conn.getMetaData();
            Map<String, Map<String, Object>> merged = new LinkedHashMap<>();

            try (ResultSet catalogs = metaData.getCatalogs()) {
                while (catalogs.next()) {
                    String name = catalogs.getString(1);
                    if (name != null && !name.isBlank()) {
                        merged.putIfAbsent(name, mapDatabase(name, "CATALOG"));
                    }
                }
            } catch (Exception ignored) {
                // Some drivers don't support catalogs.
                McpRuntimeLogService.getInstance().error("Database", "connect db error：" + ignored.getMessage());
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
        validateSql(sql, SqlMode.QUERY);
        return executeSqlInternal(projectHint, dataSourceName, sql, maxRows, overrideScope, SqlMode.QUERY);
    }

    public Map<String, Object> executeDmlSql(String projectHint, String dataSourceName, String sql,
                                             McpSettingsState.DataSourceScope overrideScope) {
        validateSql(sql, SqlMode.DML);
        return executeSqlInternal(projectHint, dataSourceName, sql, 1, overrideScope, SqlMode.DML);
    }

    public Map<String, Object> executeDdlSql(String projectHint, String dataSourceName, String sql,
                                             McpSettingsState.DataSourceScope overrideScope) {
        validateSql(sql, SqlMode.DDL);
        return executeSqlInternal(projectHint, dataSourceName, sql, 1, overrideScope, SqlMode.DDL);
    }

    public Map<String, Object> executeSql(String projectHint, String dataSourceName, String sql, int maxRows) {
        return executeQuerySql(projectHint, dataSourceName, sql, maxRows, null);
    }

    private Map<String, Object> executeSqlInternal(String projectHint, String dataSourceName, String sql, int maxRows,
                                                   McpSettingsState.DataSourceScope overrideScope, SqlMode mode) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL must not be empty");
        }

        Project project = resolveProject(projectHint);
//        if (project == null) {
//            throw new IllegalStateException("No opened project found");
//        }

        Object dataSource = findDataSourceByName(project, dataSourceName, overrideScope);
        if (dataSource == null) {
            throw new IllegalArgumentException("Data source not found: " + dataSourceName);
        }

        Map<String, Object> payload = new HashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();

        try (Connection conn = openConnection(dataSource, dataSourceName);
             Statement stmt = conn.createStatement()) {
            if (mode == SqlMode.QUERY) {
                stmt.setMaxRows(Math.max(1, maxRows));
            }
            boolean hasResultSet = stmt.execute(sql);
            if (hasResultSet) {
                try (ResultSet rs = stmt.getResultSet()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int columns = md.getColumnCount();
                    while (rs.next()) {
                        Map<String, Object> row = new HashMap<>();
                        for (int i = 1; i <= columns; i++) {
                            row.put(md.getColumnLabel(i), rs.getObject(i));
                        }
                        rows.add(row);
                    }
                }
            }
            payload.put("hasResultSet", hasResultSet);
            payload.put("updateCount", hasResultSet ? -1 : stmt.getUpdateCount());
            payload.put("mode", mode.name());
        } catch (Exception ex) {
            throw new IllegalStateException("SQL execution failed: " + ex.getMessage(), ex);
        }

        payload.put("dataSource", dataSourceName);
        payload.put("rowCount", rows.size());
        payload.put("rows", rows);
        return payload;
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
        for (ScopedDataSource scoped : findScopedDataSources(project, scope)) {
            String dsName = invokeString(scoped.delegate, "getName");
            if (Objects.equals(dsName, name)) {
                return scoped.delegate;
            }
        }
        return null;
    }

    private List<ScopedDataSource> findScopedDataSources(Project project, McpSettingsState.DataSourceScope scope) {
        return switch (scope) {
            case GLOBAL -> loadGlobalScopedDataSources(null);
            case PROJECT -> loadProjectScopedDataSources(project);
            case ALL ->
                    mergeScopedDataSources(loadGlobalScopedDataSources(project), loadProjectScopedDataSources(project));
        };
    }

    private List<ScopedDataSource> loadProjectScopedDataSources(Project project) {
        if (project == null) {
            return new ArrayList<>();
        }
        // Prefer DataSourceStorage API when available (project storage)
        DiscoveryResult result = tryReadDataSourceStorage(project);
        if (result != null) {
            result.apiReached = true;
            return asScopedDataSources(result, McpSettingsState.DataSourceScope.PROJECT);
        }

        result = discoverDataSources(PROJECT_MANAGER_CLASS_CANDIDATES, project, McpSettingsState.DataSourceScope.PROJECT);
        return asScopedDataSources(result, McpSettingsState.DataSourceScope.PROJECT);
    }

    private List<ScopedDataSource> loadGlobalScopedDataSources(Project project) {
        // Prefer DataSourceStorage.getStorage() for global sources (app-level storage)
        DiscoveryResult result = tryReadDataSourceStorage(null);
        if (result != null) {
            result.apiReached = true;
            return asScopedDataSources(result, McpSettingsState.DataSourceScope.GLOBAL);
        }

        result = discoverDataSources(GLOBAL_MANAGER_CLASS_CANDIDATES, project, McpSettingsState.DataSourceScope.GLOBAL);
        return asScopedDataSources(result, McpSettingsState.DataSourceScope.GLOBAL);
    }

    @SuppressWarnings("unchecked")
    private DiscoveryResult tryReadDataSourceStorage(Project project) {
        try {
            Class<?> storageCls = Class.forName("com.intellij.database.dataSource.DataSourceStorage");
            Method getStorageNoArg = null;
            Method getStorageWithProject = null;
            try {
                getStorageNoArg = storageCls.getMethod("getStorage");
            } catch (NoSuchMethodException ignored) {
            }
            try {
                getStorageWithProject = storageCls.getMethod("getStorage", Project.class);
            } catch (NoSuchMethodException ignored) {
            }

            Object storage = null;
            if (project != null && getStorageWithProject != null) {
                storage = getStorageWithProject.invoke(null, project);
            }
            if (storage == null && getStorageNoArg != null) {
                storage = getStorageNoArg.invoke(null);
            }

            if (storage == null) {
                return null;
            }

            Method getDataSources = storage.getClass().getMethod("getDataSources");
            Object value = getDataSources.invoke(storage);
            Collection<Object> dataSources = asCollection(value);
            DiscoveryResult res = new DiscoveryResult();
            if (dataSources != null) {
                res.dataSources.addAll(dataSources);
            }
            return res;
        } catch (ClassNotFoundException ignored) {
            return null;
        } catch (Exception ex) {
            LOG.debug("DataSourceStorage read failed: " + ex.getMessage());
            return null;
        }
    }

    private List<ScopedDataSource> asScopedDataSources(DiscoveryResult result, McpSettingsState.DataSourceScope scope) {
        if (!result.errors.isEmpty()) {
            for (String err : result.errors) {
                LOG.warn(err);
            }
        }

        if (result.dataSources.isEmpty() && !result.apiReached && !result.errors.isEmpty()) {
            throw new IllegalStateException("IntelliJ " + scope.name() + " data source API unavailable: " + String.join(" | ", result.errors));
        }

        List<ScopedDataSource> scoped = new ArrayList<>();
        for (Object item : result.dataSources) {
            Boolean isGlobal = invokeBoolean(item, "isGlobal");
            if (isGlobal != null) {
                if (scope == McpSettingsState.DataSourceScope.GLOBAL && !isGlobal) {
                    continue;
                }
                if (scope == McpSettingsState.DataSourceScope.PROJECT && isGlobal) {
                    continue;
                }
            }
            scoped.add(new ScopedDataSource(item, scope));
        }
        return scoped;
    }

    private List<ScopedDataSource> mergeScopedDataSources(List<ScopedDataSource> global, List<ScopedDataSource> project) {
        // Keep global-first behavior to match previous semantics and README.
        Map<String, ScopedDataSource> merged = new LinkedHashMap<>();
        for (ScopedDataSource ds : global) {
            merged.put(dataSourceKey(ds.delegate), ds);
        }
        for (ScopedDataSource ds : project) {
            merged.putIfAbsent(dataSourceKey(ds.delegate), ds);
        }
        return new ArrayList<>(merged.values());
    }

    private String dataSourceKey(Object dataSource) {
        String name = invokeString(dataSource, "getName");
        if (name != null && !name.isBlank()) {
            return "name:" + name;
        }
        String url = invokeString(dataSource, "getUrl");
        return "url:" + (url == null ? String.valueOf(System.identityHashCode(dataSource)) : url);
    }

    private String inferDataSourceType(String url, String driverClass) {
        String vendor = formatVendorName(extractVendorFromUrl(url));
        if (vendor == null) {
            vendor = formatVendorName(extractVendorFromDriverClass(driverClass));
        }
        return vendor == null ? CUSTOM_DATA_SOURCE_TYPE : vendor;
    }

    private String extractVendorFromUrl(String url) {
        if (url == null) {
            return null;
        }
        String normalized = url.toLowerCase(Locale.ROOT).trim();
        if (normalized.isBlank()) {
            return null;
        }
        int offset = 0;
        if (normalized.startsWith("jdbc:")) {
            offset = 5;
        }
        if (offset >= normalized.length()) {
            return null;
        }
        String remainder = normalized.substring(offset);
        if (remainder.isBlank()) {
            return null;
        }
        int delimiter = firstDelimiterIndex(remainder);
        return delimiter < 0 ? remainder : remainder.substring(0, delimiter);
    }

    private int firstDelimiterIndex(String value) {
        int idx = value.indexOf(':');
        int slash = value.indexOf('/');
        int semicolon = value.indexOf(';');
        int question = value.indexOf('?');
        int at = value.indexOf('@');
        int end = value.length();
        if (idx >= 0) {
            end = Math.min(end, idx);
        }
        if (slash >= 0) {
            end = Math.min(end, slash);
        }
        if (semicolon >= 0) {
            end = Math.min(end, semicolon);
        }
        if (question >= 0) {
            end = Math.min(end, question);
        }
        if (at >= 0) {
            end = Math.min(end, at);
        }
        return end == value.length() ? -1 : end;
    }

    private String extractVendorFromDriverClass(String driverClass) {
        if (driverClass == null || driverClass.isBlank()) {
            return null;
        }
        String normalized = driverClass.toLowerCase(Locale.ROOT);
        String[] segments = normalized.split("[^a-z0-9]+");
        if (segments.length == 0) {
            return null;
        }
        for (String segment : segments) {
            if (isSkippableDriverSegment(segment)) {
                continue;
            }
            if (segment.contains("sql")) {
                return segment;
            }
        }
        for (String segment : segments) {
            if (!isSkippableDriverSegment(segment)) {
                return segment;
            }
        }
        return null;
    }

    private boolean isSkippableDriverSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            return true;
        }
        return DRIVER_SEGMENT_BLACKLIST.contains(segment);
    }

    private String formatVendorName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.replaceAll("[^a-z0-9]+", " ").trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        String[] words = normalized.split("\\s+");
        List<String> formatted = new ArrayList<>();
        for (String word : words) {
            String part = formatVendorWord(word);
            if (part != null) {
                formatted.add(part);
            }
        }
        if (formatted.isEmpty()) {
            return null;
        }
        return String.join(" ", formatted);
    }

    private String formatVendorWord(String word) {
        if (word == null || word.isBlank()) {
            return null;
        }
        switch (word) {
            case "mongodb":
                return "MongoDB";
            case "redis":
                return "Redis";
            case "cassandra":
                return "Cassandra";
            case "cockroach":
                return "CockroachDB";
            case "db2":
                return "DB2";
            case "h2":
                return "H2";
            case "sqlite":
                return "SQLite";
        }
        if (word.endsWith("sql") && word.length() > 3) {
            String prefix = word.substring(0, word.length() - 3);
            return capitalize(prefix) + "SQL";
        }
        return capitalize(word);
    }

    private String capitalize(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        if (input.length() == 1) {
            return input.toUpperCase(Locale.ROOT);
        }
        return input.substring(0, 1).toUpperCase(Locale.ROOT) + input.substring(1).toLowerCase(Locale.ROOT);
    }

    private DiscoveryResult discoverDataSources(List<String> managerClassCandidates,
                                                Project project,
                                                McpSettingsState.DataSourceScope scope) {
        DiscoveryResult result = new DiscoveryResult();

        for (String className : managerClassCandidates) {
            Class<?> managerClass;
            try {
                managerClass = Class.forName(className);
            } catch (ClassNotFoundException ex) {
                result.errors.add("Manager class not found: " + className);
                continue;
            }

            Object manager = resolveManagerInstance(managerClass, project, scope, result.errors);
            if (manager == null) {
                continue;
            }

            try {
                Collection<Object> dataSources = readDataSources(manager, project, scope);
                result.apiReached = true;
                result.dataSources.addAll(dataSources);
                return result;
            } catch (Exception ex) {
                result.errors.add("Failed reading data sources from " + className + ": " + ex.getMessage());
            }
        }

        return result;
    }

    private Object resolveManagerInstance(Class<?> managerClass,
                                          Project project,
                                          McpSettingsState.DataSourceScope scope,
                                          List<String> errors) {
        if ("com.intellij.database.psi.DataSourceManager".equals(managerClass.getName())) {
            return resolveViaDataSourceManagers(managerClass, project, errors);
        }

        List<MethodCallPlan> plans = new ArrayList<>();
        // Prefer getInstance(Project) when a project is available; also try no-arg getInstance()
        if (project != null) {
            plans.add(new MethodCallPlan("getInstance", new Class<?>[]{Project.class}, new Object[]{project}));
        }
        plans.add(new MethodCallPlan("getInstance", new Class<?>[0], new Object[0]));

        // Try to locate an existing manager instance via DataSourceManager.getManagers(project)
        // Only attempt this if a project is available; do not fallback to an opened project for GLOBAL discovery
        if (project != null) {
            try {
                Class<?> dataSourceManagerCls = Class.forName("com.intellij.database.psi.DataSourceManager");
                Method getManagers = dataSourceManagerCls.getMethod("getManagers", Project.class);
                Object managersVal = getManagers.invoke(null, project);
                Collection<Object> managers = asCollection(managersVal);
                if (managers != null) {
                    for (Object m : managers) {
                        if (m != null && managerClass.isAssignableFrom(m.getClass())) {
                            return m;
                        }
                    }
                }
            } catch (ClassNotFoundException ignored) {
            } catch (Exception ex) {
                errors.add("DataSourceManager.getManagers discovery failed: " + ex.getMessage());
            }
        }

        for (MethodCallPlan plan : plans) {
            Object value = tryInvokeStatic(managerClass, plan, errors);
            if (value != null) {
                return value;
            }
        }

        Object singleton = tryReadStaticSingleton(managerClass, errors);
        if (singleton != null) {
            return singleton;
        }

        Object service = tryGetService(managerClass, project, scope, errors);
        if (service != null) {
            return service;
        }

        errors.add("Cannot obtain manager instance for class " + managerClass.getName());
        return null;
    }

    private Object resolveViaDataSourceManagers(Class<?> managerClass, Project project, List<String> errors) {
        Project fallback = project;
        if (fallback == null) {
            Project[] opened = ProjectManager.getInstance().getOpenProjects();
            if (opened.length == 0) {
                // No open project: try known app-level storage fallbacks
                List<String> appCandidates = List.of(
                        "com.intellij.database.dataSource.DataSourceStorage$App",
                        "com.intellij.database.dataSource.DataSourceStorageShared$App",
                        "com.intellij.database.dataSource.DataSourceModelStorageImpl$App"
                );
                for (String candidate : appCandidates) {
                    try {
                        Class<?> appCls = Class.forName(candidate);
                        // try static INSTANCE
                        try {
                            Field f = appCls.getField("INSTANCE");
                            try {
                                Object inst = f.get(null);
                                if (inst != null) {
                                    return inst;
                                }
                            } catch (IllegalAccessException ignoredAccess) {
                                // ignore and continue
                            }
                        } catch (NoSuchFieldException ignored) {
                        }
                        // try getInstance()
                        try {
                            Method m = appCls.getMethod("getInstance");
                            try {
                                Object inst = m.invoke(null);
                                if (inst != null) {
                                    return inst;
                                }
                            } catch (IllegalAccessException |
                                     java.lang.reflect.InvocationTargetException ignoredInvoke) {
                                // ignore and continue
                            }
                        } catch (NoSuchMethodException ignored) {
                        }
                        // try Application service
                        try {
                            Object app = ApplicationManager.getApplication();
                            if (app != null) {
                                Method getService = app.getClass().getMethod("getService", Class.class);
                                Object svc = getService.invoke(app, appCls);
                                if (svc != null) {
                                    return svc;
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    } catch (ClassNotFoundException ignored) {
                    }
                }
                errors.add("DataSourceManager.getManagers requires a project (no open projects)");
                return null;
            }
            fallback = opened[0];
        }
        try {
            Method method = managerClass.getMethod("getManagers", Project.class);
            Object value = method.invoke(null, fallback);
            Collection<Object> managers = asCollection(value);
            if (managers != null && !managers.isEmpty()) {
                return managers;
            }
            errors.add("DataSourceManager.getManagers returned empty");
        } catch (Exception ex) {
            errors.add("DataSourceManager.getManagers failed: " + ex.getMessage());
        }
        return null;
    }

    private Object tryInvokeStatic(Class<?> targetClass, MethodCallPlan plan, List<String> errors) {
        try {
            Method method = targetClass.getMethod(plan.methodName, plan.parameterTypes);
            return method.invoke(null, plan.args);
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (Exception ex) {
            errors.add("Static call failed: " + targetClass.getName() + "#" + plan.methodName + " - " + ex.getMessage());
            return null;
        }
    }

    private Object tryReadStaticSingleton(Class<?> targetClass, List<String> errors) {
        try {
            Field field = targetClass.getField("INSTANCE");
            return field.get(null);
        } catch (NoSuchFieldException ignored) {
            return null;
        } catch (Exception ex) {
            errors.add("Read singleton failed: " + targetClass.getName() + "#INSTANCE - " + ex.getMessage());
            return null;
        }
    }

    private Object tryGetService(Class<?> targetClass,
                                 Project project,
                                 McpSettingsState.DataSourceScope scope,
                                 List<String> errors) {
        if (scope == McpSettingsState.DataSourceScope.PROJECT && project != null) {
            try {
                Method getService = Project.class.getMethod("getService", Class.class);
                Object service = getService.invoke(project, targetClass);
                if (service != null) {
                    return service;
                }
            } catch (Exception ex) {
                errors.add("Project service lookup failed for " + targetClass.getName() + ": " + ex.getMessage());
            }
        }

        try {
            Object app = ApplicationManager.getApplication();
            if (app != null) {
                Method getService = app.getClass().getMethod("getService", Class.class);
                return getService.invoke(app, targetClass);
            }
        } catch (Exception ex) {
            errors.add("Application service lookup failed for " + targetClass.getName() + ": " + ex.getMessage());
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private Collection<Object> readDataSources(Object manager, Project project, McpSettingsState.DataSourceScope scope) throws Exception {
        if (manager instanceof Collection<?> managers) {
            List<Object> all = new ArrayList<>();
            for (Object managerItem : managers) {
                all.addAll(readDataSources(managerItem, project, scope));
            }
            return all;
        }

        List<MethodCallPlan> plans = new ArrayList<>();

        if (scope == McpSettingsState.DataSourceScope.PROJECT) {
            plans.add(new MethodCallPlan("getDataSources", new Class<?>[0], new Object[0]));
            plans.add(new MethodCallPlan("getDataSources", new Class<?>[]{Project.class}, new Object[]{project}));
            plans.add(new MethodCallPlan("getAllDataSources", new Class<?>[0], new Object[0]));
            plans.add(new MethodCallPlan("getAllDataSources", new Class<?>[]{Project.class}, new Object[]{project}));
        } else {
            plans.add(new MethodCallPlan("getDataSources", new Class<?>[0], new Object[0]));
            plans.add(new MethodCallPlan("getAllDataSources", new Class<?>[0], new Object[0]));
        }

        for (MethodCallPlan plan : plans) {
            Method method;
            try {
                method = manager.getClass().getMethod(plan.methodName, plan.parameterTypes);
            } catch (NoSuchMethodException ignored) {
                continue;
            }
            Object value = method.invoke(manager, plan.args);
            Collection<Object> converted = asCollection(value);
            if (converted != null) {
                return converted;
            }
        }

        // Last resort: heuristically scan public methods that look like datasource getters.
        for (Method method : manager.getClass().getMethods()) {
            if (!looksLikeDataSourceGetter(method)) {
                continue;
            }
            Object[] args;
            if (method.getParameterCount() == 0) {
                args = new Object[0];
            } else if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == Project.class && project != null) {
                args = new Object[]{project};
            } else {
                continue;
            }

            Object value = method.invoke(manager, args);
            Collection<Object> converted = asCollection(value);
            if (converted != null) {
                return converted;
            }
        }

        throw new IllegalStateException("No compatible data source getter found on " + manager.getClass().getName());
    }

    private boolean looksLikeDataSourceGetter(Method method) {
        String name = method.getName().toLowerCase();
        return (name.contains("datasource") || name.contains("data_sources") || name.contains("sources"))
                && (Collection.class.isAssignableFrom(method.getReturnType())
                || Iterable.class.isAssignableFrom(method.getReturnType())
                || method.getReturnType().isArray());
    }

    @SuppressWarnings("unchecked")
    private Collection<Object> asCollection(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return (Collection<Object>) collection;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> out = new ArrayList<>();
            for (Object item : iterable) {
                out.add(item);
            }
            return out;
        }
        if (value.getClass().isArray()) {
            int len = Array.getLength(value);
            List<Object> out = new ArrayList<>(len);
            for (int i = 0; i < len; i++) {
                out.add(Array.get(value, i));
            }
            return out;
        }
        return null;
    }

    private Connection openConnection(Object dataSource, String dataSourceName) throws Exception {
        String url = invokeString(dataSource, "getUrl");
        String user = invokeString(dataSource, "getUser");
        if (user == null || user.isBlank()) {
            user = invokeString(dataSource, "getUsername");
        }
        // Use IDE credential storage instead of datasource#getPassword (usually unavailable by design).
        String password = loadPasswordViaIdeaCredentials(dataSource);
        String driverClass = invokeString(dataSource, "getDriverClass");

        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Data source URL is empty: " + dataSourceName);
        }

        if (driverClass != null && !driverClass.isBlank()) {
            try {
                Class.forName(driverClass);
            } catch (ClassNotFoundException ex) {
                LOG.warn("JDBC driver class not found: " + driverClass, ex);
            }
        }

        Properties props = buildConnectionProperties(dataSource, user, password);
        try {
            return DriverManager.getConnection(url, props);
        } catch (SQLException ex) {
            if (!isNoSuitableDriverError(ex) || driverClass == null || driverClass.isBlank()) {
                throw ex;
            }
            // Fallback for IDE-managed JDBC drivers (downloaded under config/jdbc-drivers).
            if (!registerDriverFromIdeaJdbcDrivers(driverClass)) {
                throw ex;
            }
            return DriverManager.getConnection(url, props);
        }
    }

    private Properties buildConnectionProperties(Object dataSource, String user, String password) {
        Properties props = new Properties();
        try {
            Method method = dataSource.getClass().getMethod("getConnectionProperties");
            Object value = method.invoke(dataSource);
            if (value instanceof Properties fromDataSource) {
                props.putAll(fromDataSource);
            }
        } catch (Exception ignored) {
            // Some datasource implementations do not expose connection properties.
        }

        if (user != null && !user.isBlank()) {
            props.setProperty("user", user);
        }
        if (password != null) {
            props.setProperty("password", password);
        }
        return props;
    }

    private String loadPasswordViaIdeaCredentials(Object dataSource) {
        try {
            Class<?> connectionPointClass = Class.forName("com.intellij.database.dataSource.DatabaseConnectionPoint");
            if (!connectionPointClass.isInstance(dataSource)) {
                return null;
            }

            Class<?> credentialsClass = Class.forName("com.intellij.database.access.DatabaseCredentials");
            Method getInstance = credentialsClass.getMethod("getInstance");
            Object credentials = getInstance.invoke(null);
            if (credentials == null) {
                return null;
            }

            Method loadPassword = credentialsClass.getMethod("loadPassword", connectionPointClass);
            Object oneTimeString = loadPassword.invoke(credentials, dataSource);
            if (oneTimeString == null) {
                return null;
            }

            try {
                Method toString = oneTimeString.getClass().getMethod("toString", boolean.class);
                Object value = toString.invoke(oneTimeString, false);
                return value == null ? null : String.valueOf(value);
            } catch (NoSuchMethodException ignored) {
                return String.valueOf(oneTimeString);
            }
        } catch (ClassNotFoundException ignored) {
            return null;
        } catch (Exception ex) {
            LOG.debug("Failed to load password via IDE credentials: " + ex.getMessage());
            return null;
        }
    }

    private boolean isNoSuitableDriverError(SQLException ex) {
        String message = ex.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("no suitable driver");
    }

    private boolean registerDriverFromIdeaJdbcDrivers(String driverClass) {
        Path driverRoot = Path.of(PathManager.getConfigPath(), "jdbc-drivers");
        if (!Files.isDirectory(driverRoot)) {
            return false;
        }

        String classEntry = driverClass.replace('.', '/') + ".class";
        try (Stream<Path> paths = Files.walk(driverRoot)) {
            Iterator<Path> it = paths
                    .filter(p -> p.toString().endsWith(".jar"))
                    .iterator();
            while (it.hasNext()) {
                Path jar = it.next();
                if (!jarContainsClass(jar, classEntry)) {
                    continue;
                }

                // Keep classloader alive after registration so the driver remains usable.
                URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader());
                Class<?> loaded = Class.forName(driverClass, true, loader);
                Object instance = loaded.getDeclaredConstructor().newInstance();
                if (instance instanceof Driver driver) {
                    DriverManager.registerDriver(new DriverShim(driver));
                    LOG.info("Registered JDBC driver " + driverClass + " from " + jar);
                    return true;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to register JDBC driver from IDE jdbc-drivers: " + driverClass + ", " + e.getMessage());
        }
        return false;
    }

    private boolean jarContainsClass(Path jarPath, String classEntry) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            return jar.getEntry(classEntry) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Map<String, Object> mapDatabase(String name, String type) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("type", type);
        return row;
    }

    private void validateSql(String sql, SqlMode mode) {
        String normalized = normalizeSql(sql);
        switch (mode) {
            case QUERY -> {
                if (!ReadOnlySqlGuard.isReadOnlySql(normalized)) {
                    throw new IllegalArgumentException("Only read-only SQL is allowed for querySql.");
                }
            }
            case DML -> {
                if (!ReadOnlySqlGuard.isDmlSql(normalized)) {
                    throw new IllegalArgumentException("Only DML SQL is allowed for executeDml.");
                }
            }
            case DDL -> {
                if (!ReadOnlySqlGuard.isDdlSql(normalized)) {
                    throw new IllegalArgumentException("Only DDL SQL is allowed for executeDdl.");
                }
            }
        }
    }

    private String normalizeSql(String sql) {
        if (sql == null) {
            return "";
        }
        return sql.trim().toLowerCase();
    }

    private String invokeString(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            return value == null ? null : String.valueOf(value);
        } catch (Exception ex) {
            LOG.debug("Failed invoke " + target.getClass().getName() + "#" + methodName + ": " + ex.getMessage());
            return null;
        }
    }

    private Boolean invokeBoolean(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            if (value instanceof Boolean b) {
                return b;
            }
            return value == null ? null : Boolean.parseBoolean(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    private enum SqlMode {
        QUERY,
        DML,
        DDL
    }

    private static final class ScopedDataSource {
        private final Object delegate;
        private final McpSettingsState.DataSourceScope scope;

        private ScopedDataSource(Object delegate, McpSettingsState.DataSourceScope scope) {
            this.delegate = delegate;
            this.scope = scope;
        }
    }

    private static final class DiscoveryResult {
        private final List<Object> dataSources = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private boolean apiReached;
    }

    private static final class MethodCallPlan {
        private final String methodName;
        private final Class<?>[] parameterTypes;
        private final Object[] args;

        private MethodCallPlan(String methodName, Class<?>[] parameterTypes, Object[] args) {
            this.methodName = methodName;
            this.parameterTypes = parameterTypes;
            this.args = args;
        }
    }

    private static final class DriverShim implements Driver {
        private final Driver delegate;

        private DriverShim(Driver delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            return delegate.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }
    }
}
