package com.skrstop.ide.databasemcp.db;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.skrstop.ide.databasemcp.service.McpRuntimeLogService;
import com.skrstop.ide.databasemcp.settings.McpSettingsState;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

final class DataSourceDiscoveryUtil {
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

    DataSourceDiscoveryUtil() {
    }

    private static McpRuntimeLogService logService() {
        return ApplicationManager.getApplication() == null
                ? null
                : ApplicationManager.getApplication().getService(McpRuntimeLogService.class);
    }

    private static void logInfo(String message) {
        McpRuntimeLogService service = logService();
        if (service != null) {
            service.info("discovery", message);
        }
    }

    private static void logWarn(String message) {
        McpRuntimeLogService service = logService();
        if (service != null) {
            service.warn("discovery", message);
        }
    }

    List<ScopedDataSource> findScopedDataSources(Project project, McpSettingsState.DataSourceScope scope) {
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
        DiscoveryResult result = tryReadDataSourceStorage(project);
        if (result != null) {
            result.apiReached = true;
            return asScopedDataSources(result, McpSettingsState.DataSourceScope.PROJECT);
        }

        result = discoverDataSources(PROJECT_MANAGER_CLASS_CANDIDATES, project, McpSettingsState.DataSourceScope.PROJECT);
        return asScopedDataSources(result, McpSettingsState.DataSourceScope.PROJECT);
    }

    private List<ScopedDataSource> loadGlobalScopedDataSources(Project project) {
        DiscoveryResult result = tryReadDataSourceStorage(null);
        if (result != null) {
            result.apiReached = true;
            return asScopedDataSources(result, McpSettingsState.DataSourceScope.GLOBAL);
        }

        result = discoverDataSources(GLOBAL_MANAGER_CLASS_CANDIDATES, project, McpSettingsState.DataSourceScope.GLOBAL);
        return asScopedDataSources(result, McpSettingsState.DataSourceScope.GLOBAL);
    }

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
            logInfo("DataSourceStorage read failed: " + ex.getMessage());
            return null;
        }
    }

    private List<ScopedDataSource> asScopedDataSources(DiscoveryResult result, McpSettingsState.DataSourceScope scope) {
        if (!result.errors.isEmpty()) {
            for (String err : result.errors) {
                logWarn(err);
            }
        }

        if (result.dataSources.isEmpty() && !result.apiReached && !result.errors.isEmpty()) {
            throw new IllegalStateException("IntelliJ " + scope.name() + " data source API unavailable: " + String.join(" | ", result.errors));
        }

        List<ScopedDataSource> scoped = new ArrayList<>();
        for (Object item : result.dataSources) {
            Boolean isGlobal = DbReflectionUtil.invokeBoolean(item, "isGlobal");
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
        String name = DbReflectionUtil.invokeString(dataSource, "getName");
        if (name != null && !name.isBlank()) {
            return "name:" + name;
        }
        String url = DbReflectionUtil.invokeString(dataSource, "getUrl");
        return "url:" + (url == null ? String.valueOf(System.identityHashCode(dataSource)) : url);
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
        if (project != null) {
            plans.add(new MethodCallPlan("getInstance", new Class<?>[]{Project.class}, new Object[]{project}));
        }
        plans.add(new MethodCallPlan("getInstance", new Class<?>[0], new Object[0]));

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
                List<String> appCandidates = List.of(
                        "com.intellij.database.dataSource.DataSourceStorage$App",
                        "com.intellij.database.dataSource.DataSourceStorageShared$App",
                        "com.intellij.database.dataSource.DataSourceModelStorageImpl$App"
                );
                for (String candidate : appCandidates) {
                    try {
                        Class<?> appCls = Class.forName(candidate);
                        try {
                            Field f = appCls.getField("INSTANCE");
                            Object inst = f.get(null);
                            if (inst != null) {
                                return inst;
                            }
                        } catch (NoSuchFieldException ignored) {
                        } catch (IllegalAccessException ignored) {
                        }
                        try {
                            Method m = appCls.getMethod("getInstance");
                            Object inst = m.invoke(null);
                            if (inst != null) {
                                return inst;
                            }
                        } catch (NoSuchMethodException ignored) {
                        } catch (Exception ignored) {
                        }
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

    static final class ScopedDataSource {
        private final Object delegate;
        private final McpSettingsState.DataSourceScope scope;

        ScopedDataSource(Object delegate, McpSettingsState.DataSourceScope scope) {
            this.delegate = delegate;
            this.scope = scope;
        }

        Object delegate() {
            return delegate;
        }

        McpSettingsState.DataSourceScope scope() {
            return scope;
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
}

