package com.skrstop.ide.databasemcp.service;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.extensions.PluginId;
import com.skrstop.ide.databasemcp.db.IdeDatabaseFacade;
import com.skrstop.ide.databasemcp.mcp.McpJavalinHandler;
import com.skrstop.ide.databasemcp.settings.DatabaseMcpMessages;
import com.skrstop.ide.databasemcp.settings.McpSettingsState;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

@Service(Service.Level.APP)
public final class McpServerManager implements Disposable {
    // LOG 字段已移除，统一使用 McpRuntimeLogService
    private static final String DATABASE_PLUGIN_ID = "com.intellij.database";
    private static final String DATABASE_MANAGER_CLASS = "com.intellij.database.psi.DataSourceManager";
    private static final String BIND_HOST = "0.0.0.0";
    private static final String DISPLAY_HOST = "127.0.0.1";
    private static final String MCP_PATH = "/mcp";

    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private McpJavalinHandler javalinHandler;
    private volatile int runningPort = -1;

    public static final class StartResult {
        private final boolean success;
        private final String message;

        private StartResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public static StartResult success(String message) {
            return new StartResult(true, message);
        }

        public static StartResult failure(String message) {
            return new StartResult(false, message);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }
    }

    public static McpServerManager getInstance() {
        return ApplicationManager.getApplication().getService(McpServerManager.class);
    }

    public boolean isRunning() {
        return running.get();
    }

    public String getServiceUrl() {
        int port = running.get() && runningPort > 0 ? runningPort : McpSettingsState.getInstance().getPortEffective();
        return "http://" + DISPLAY_HOST + ":" + port + MCP_PATH;
    }

    public String validatePortAvailability(int port) {
        if (port < 1 || port > 65535) {
            return "Port must be between 1 and 65535.";
        }
        if (running.get() && runningPort == port) {
            return null;
        }

        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(BIND_HOST, port));
            return null;
        } catch (IOException ex) {
            McpRuntimeLogService.logWarn("server", "Port validation failed: " + ex.getMessage());
            return "Port " + port + " is already in use.";
        }
    }

    public StartResult startWithValidation(String reason) {
        McpSettingsState.PluginSettingsScope scope = McpSettingsState.getInstance().getPluginSettingsScope();
        return startWithValidation(reason, scope);
    }

    public StartResult startWithValidation(String reason, McpSettingsState.PluginSettingsScope scope) {
        String dependencyError = validateDatabaseDependency();
        if (dependencyError != null) {
            McpRuntimeLogService.logWarn("server", "Start blocked (" + reason + "): " + dependencyError);
            return StartResult.failure(dependencyError);
        }

        McpSettingsState settings = McpSettingsState.getInstance();
        int port = settings.getPort(scope);
        String portError = validatePortAvailability(port);
        if (portError != null) {
            McpRuntimeLogService.logWarn("server", "Start blocked (" + reason + "): " + portError);
            return StartResult.failure(portError);
        }

        lock.lock();
        try {
            if (running.get()) {
                McpRuntimeLogService.logInfo("server", "Start skipped because server is already running (" + reason + ")");
                return StartResult.success("Database MCP already running.");
            }

            javalinHandler = new McpJavalinHandler(new IdeDatabaseFacade());
            javalinHandler.start(port);

            runningPort = port;
            running.set(true);
            McpRuntimeLogService.logInfo("server", "Service started: " + getServiceUrl() + " (" + reason + ")");
            return StartResult.success("Database MCP started at " + getServiceUrl());
        } catch (Exception ex) {
            McpRuntimeLogService.logError("server", "Failed to start service: " + ex.getMessage());
            stop("start-failed");
            return StartResult.failure("Failed to start Database MCP: " + ex.getMessage());
        } finally {
            lock.unlock();
        }
    }

    public void stop(String reason) {
        lock.lock();
        try {
            if (javalinHandler != null) {
                javalinHandler.stop();
                javalinHandler = null;
            }

            running.set(false);
            runningPort = -1;
            McpRuntimeLogService.logInfo("server", "Service stopped (" + reason + ")");
        } finally {
            lock.unlock();
        }
    }

    public StartResult onSettingsChanged(int oldPort) {
        McpSettingsState settings = McpSettingsState.getInstance();
        boolean endpointChanged = oldPort != settings.getPortEffective();

        if (running.get() && endpointChanged) {
            stop("settings-endpoint-changed");
            McpRuntimeLogService.logInfo("server", "Endpoint changed, restarting service");
            return startWithValidation("settings-endpoint-changed");
        }


        return StartResult.success("Settings applied.");
    }

    private String validateDatabaseDependency() {
        PluginId pluginId = PluginId.getId(DATABASE_PLUGIN_ID);
        if (PluginStateCompat.isPluginUnavailable(pluginId)) {
            return DatabaseMcpMessages.message("settings.databasePluginMissing", DATABASE_PLUGIN_ID);
        }

        try {
            // Verify Database API is really available for current platform/product version.
            Class.forName(DATABASE_MANAGER_CLASS, false, McpServerManager.class.getClassLoader());
            return null;
        } catch (Throwable ex) {
            McpRuntimeLogService.logWarn("server", "Database API class is unavailable: " + ex.getMessage());
            return DatabaseMcpMessages.message("settings.databaseApiUnavailable", DATABASE_MANAGER_CLASS);
        }
    }

    /**
     * 接入 IDE 应用生命周期
     * 在 IDE 关闭时自动触发 dispose()
     */
    @Override
    public void dispose() {
        if (running.get()) {
            McpRuntimeLogService.logInfo("server", "Gracefully shutting down on application exit");
            stop("application-exit");
        }
    }
}
