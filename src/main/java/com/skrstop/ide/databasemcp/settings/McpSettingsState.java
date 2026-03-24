package com.skrstop.ide.databasemcp.settings;

import com.intellij.DynamicBundle;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

@Service(Service.Level.APP)
@State(name = "DatabaseMcpSettings", storages = @Storage("ide_database_mcp.xml"))
public final class McpSettingsState implements PersistentStateComponent<McpSettingsState.State> {
    private static final int DEFAULT_PORT = 18765;

    public enum DataSourceScope {
        GLOBAL,
        PROJECT,
        ALL
    }

    public enum UiLanguage {
        ZH_CN,
        EN_US
    }

    public enum PluginSettingsScope {
        GLOBAL,
        PROJECT
    }

    private State state = new State();

    public static McpSettingsState getInstance() {
        return ApplicationManager.getApplication().getService(McpSettingsState.class);
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    public boolean isAutoStart() {
        return isAutoStart(getPluginSettingsScope());
    }

    public boolean isAutoStart(PluginSettingsScope scope) {
        return resolveState(scope).autoStart;
    }

    public boolean isAutoStartEffective() {
        return resolveEffectiveState().autoStart;
    }

    public void setAutoStart(boolean autoStart) {
        setAutoStart(getPluginSettingsScope(), autoStart);
    }

    public void setAutoStart(PluginSettingsScope scope, boolean autoStart) {
        State target = resolveState(scope);
        target.autoStart = autoStart;
        markConfigured(scope, target);
    }

    public int getPort() {
        return getPort(getPluginSettingsScope());
    }

    public int getPort(PluginSettingsScope scope) {
        return resolveState(scope).port;
    }

    public int getPortEffective() {
        return resolveEffectiveState().port;
    }

    public void setPort(int port) {
        setPort(getPluginSettingsScope(), port);
    }

    public void setPort(PluginSettingsScope scope, int port) {
        State target = resolveState(scope);
        target.port = port;
        markConfigured(scope, target);
    }

    public DataSourceScope getDataSourceScope() {
        return getDataSourceScope(getPluginSettingsScope());
    }

    public DataSourceScope getDataSourceScope(PluginSettingsScope scope) {
        return parseDataSourceScope(resolveState(scope).dataSourceScope);
    }

    public void setDataSourceScope(DataSourceScope scope) {
        setDataSourceScope(getPluginSettingsScope(), scope);
    }

    public void setDataSourceScope(PluginSettingsScope targetScope, DataSourceScope scope) {
        State target = resolveState(targetScope);
        target.dataSourceScope = (scope == null ? DataSourceScope.ALL : scope).name();
        markConfigured(targetScope, target);
    }

    public UiLanguage getUiLanguage() {
        return getUiLanguage(getPluginSettingsScope());
    }

    public UiLanguage getUiLanguage(PluginSettingsScope scope) {
        return parseUiLanguage(resolveState(scope).uiLanguage);
    }

    public UiLanguage getUiLanguageEffective() {
        return parseUiLanguage(resolveEffectiveState().uiLanguage);
    }

    public void setUiLanguage(UiLanguage language) {
        setUiLanguage(getPluginSettingsScope(), language);
    }

    public void setUiLanguage(PluginSettingsScope scope, UiLanguage language) {
        State target = resolveState(scope);
        target.uiLanguage = (language == null ? defaultUiLanguage() : language).name();
        markConfigured(scope, target);
    }

    public PluginSettingsScope getPluginSettingsScope() {
        String raw = state.pluginSettingsScope;
        if (raw == null || raw.isBlank()) {
            return PluginSettingsScope.GLOBAL;
        }
        try {
            return PluginSettingsScope.valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            return PluginSettingsScope.GLOBAL;
        }
    }

    public void setPluginSettingsScope(PluginSettingsScope scope) {
        state.pluginSettingsScope = (scope == null ? PluginSettingsScope.GLOBAL : scope).name();
    }

    public int getToolWindowTopDividerLocation() {
        return getToolWindowTopDividerLocation(getPluginSettingsScope());
    }

    public int getToolWindowTopDividerLocation(PluginSettingsScope scope) {
        return normalizeDividerLocation(resolveState(scope).toolWindowTopDividerLocation, 100);
    }

    public void setToolWindowTopDividerLocation(int location) {
        setToolWindowTopDividerLocation(getPluginSettingsScope(), location);
    }

    public void setToolWindowTopDividerLocation(PluginSettingsScope scope, int location) {
        State target = resolveState(scope);
        target.toolWindowTopDividerLocation = normalizeDividerLocation(location, 100);
        markConfigured(scope, target);
    }

    public int getToolWindowBottomDividerLocation() {
        return getToolWindowBottomDividerLocation(getPluginSettingsScope());
    }

    public int getToolWindowBottomDividerLocation(PluginSettingsScope scope) {
        return normalizeDividerLocation(resolveState(scope).toolWindowBottomDividerLocation, 220);
    }

    public void setToolWindowBottomDividerLocation(int location) {
        setToolWindowBottomDividerLocation(getPluginSettingsScope(), location);
    }

    public void setToolWindowBottomDividerLocation(PluginSettingsScope scope, int location) {
        State target = resolveState(scope);
        target.toolWindowBottomDividerLocation = normalizeDividerLocation(location, 220);
        markConfigured(scope, target);
    }

    public int getMaxEntries() {
        return getMaxEntries(getPluginSettingsScope());
    }

    public int getMaxEntries(PluginSettingsScope scope) {
        int value = resolveState(scope).maxEntries;
        return value > 0 ? value : 2000;
    }

    public void setMaxEntries(int maxEntries) {
        setMaxEntries(getPluginSettingsScope(), maxEntries);
    }

    public void setMaxEntries(PluginSettingsScope scope, int maxEntries) {
        State target = resolveState(scope);
        target.maxEntries = Math.max(100, maxEntries);
        markConfigured(scope, target);
    }

    public long getMaxFileSize() {
        return getMaxFileSize(getPluginSettingsScope());
    }

    public long getMaxFileSize(PluginSettingsScope scope) {
        long value = resolveState(scope).maxFileSize;
        return value > 0 ? value : 10 * 1024 * 1024;
    }

    public void setMaxFileSize(long maxFileSize) {
        setMaxFileSize(getPluginSettingsScope(), maxFileSize);
    }

    public void setMaxFileSize(PluginSettingsScope scope, long maxFileSize) {
        State target = resolveState(scope);
        target.maxFileSize = Math.max(1024 * 1024, maxFileSize);
        markConfigured(scope, target);
    }

    public int getMaxLogFiles() {
        return getMaxLogFiles(getPluginSettingsScope());
    }

    public int getMaxLogFiles(PluginSettingsScope scope) {
        int value = resolveState(scope).maxLogFiles;
        return value > 0 ? value : 5;
    }

    public void setMaxLogFiles(int maxLogFiles) {
        setMaxLogFiles(getPluginSettingsScope(), maxLogFiles);
    }

    public void setMaxLogFiles(PluginSettingsScope scope, int maxLogFiles) {
        State target = resolveState(scope);
        target.maxLogFiles = Math.max(1, maxLogFiles);
        markConfigured(scope, target);
    }

    public int getReadBufferSize() {
        return getReadBufferSize(getPluginSettingsScope());
    }

    public int getReadBufferSize(PluginSettingsScope scope) {
        int value = resolveState(scope).readBufferSize;
        return value > 0 ? value : 512 * 1024;
    }

    public void setReadBufferSize(int readBufferSize) {
        setReadBufferSize(getPluginSettingsScope(), readBufferSize);
    }

    public void setReadBufferSize(PluginSettingsScope scope, int readBufferSize) {
        State target = resolveState(scope);
        target.readBufferSize = Math.max(4 * 1024, readBufferSize);
        markConfigured(scope, target);
    }

    private State resolveState(PluginSettingsScope scope) {
        if (scope == PluginSettingsScope.PROJECT) {
            Project project = currentProject();
            if (project != null) {
                return McpProjectSettingsState.getInstance(project).getCurrentState();
            }
        }
        return state;
    }

    /**
     * Prefer project-level settings when available; fallback to global app-level state.
     */
    private State resolveEffectiveState() {
        Project project = currentProject();
        if (project != null) {
            State projectState = McpProjectSettingsState.getInstance(project).getCurrentState();
            if (isProjectStateConfigured(projectState)) {
                return projectState;
            }
        }
        return state;
    }

    private void markConfigured(PluginSettingsScope scope, State target) {
        if (scope == PluginSettingsScope.PROJECT) {
            target.settingsConfigured = true;
        }
    }

    private boolean isProjectStateConfigured(State projectState) {
        if (projectState.settingsConfigured) {
            return true;
        }

        return !projectState.autoStart
                || projectState.port != DEFAULT_PORT
                || !DataSourceScope.ALL.name().equals(projectState.dataSourceScope)
                || !defaultUiLanguage().name().equals(projectState.uiLanguage)
                || projectState.maxEntries != 2000
                || projectState.maxFileSize != 10 * 1024 * 1024
                || projectState.maxLogFiles != 5
                || projectState.readBufferSize != 512 * 1024;
    }

    private Project currentProject() {
        Project[] opened = ProjectManager.getInstance().getOpenProjects();
        if (opened.length == 0) {
            return null;
        }
        return opened[0];
    }

    private DataSourceScope parseDataSourceScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return DataSourceScope.ALL;
        }
        try {
            return DataSourceScope.valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            return DataSourceScope.ALL;
        }
    }

    private UiLanguage parseUiLanguage(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaultUiLanguage();
        }
        try {
            return UiLanguage.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return defaultUiLanguage();
        }
    }

    private UiLanguage defaultUiLanguage() {
        Locale locale;
        try {
            locale = DynamicBundle.getLocale();
        } catch (Throwable ignored) {
            locale = Locale.getDefault();
        }

        if (locale != null && "zh".equalsIgnoreCase(locale.getLanguage())) {
            return UiLanguage.ZH_CN;
        }
        return UiLanguage.EN_US;
    }

    private int normalizeDividerLocation(int value, int defaultValue) {
        if (value < 60) {
            return defaultValue;
        }
        return value;
    }

    public static final class State {
        public boolean autoStart = true;
        public int port = DEFAULT_PORT;
        public String dataSourceScope = DataSourceScope.ALL.name();
        public String uiLanguage = McpSettingsState.defaultUiLanguageStatic();
        public String pluginSettingsScope = PluginSettingsScope.GLOBAL.name();
        public boolean settingsConfigured = false;
        public int toolWindowTopDividerLocation = 100;
        public int toolWindowBottomDividerLocation = 220;
        public int maxEntries = 2000;
        public long maxFileSize = 10 * 1024 * 1024;
        public int maxLogFiles = 5;
        public int readBufferSize = 512 * 1024;
    }

    private static String defaultUiLanguageStatic() {
        Locale locale;
        try {
            locale = DynamicBundle.getLocale();
        } catch (Throwable ignored) {
            locale = Locale.getDefault();
        }

        if (locale != null && "zh".equalsIgnoreCase(locale.getLanguage())) {
            return UiLanguage.ZH_CN.name();
        }
        return UiLanguage.EN_US.name();
    }
}
