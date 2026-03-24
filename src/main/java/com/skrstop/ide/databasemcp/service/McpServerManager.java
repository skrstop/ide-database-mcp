package com.skrstop.ide.databasemcp.service;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.skrstop.ide.databasemcp.db.IdeDatabaseFacade;
import com.skrstop.ide.databasemcp.mcp.McpHttpHandler;
import com.skrstop.ide.databasemcp.settings.DatabaseMcpMessages;
import com.skrstop.ide.databasemcp.settings.McpSettingsState;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

@Service(Service.Level.APP)
public final class McpServerManager implements Disposable {
    private static final Logger LOG = Logger.getInstance(McpServerManager.class);
    private static final String BIND_HOST = "0.0.0.0";
    private static final String DISPLAY_HOST = "127.0.0.1";
    private static final String MCP_PATH = "/mcp";
    private static final String DATABASE_PLUGIN_ID = "com.intellij.database";
    private static final String DATABASE_MANAGER_CLASS = "com.intellij.database.psi.DataSourceManager";
    // 优雅关闭的超时时间（秒）
    private static final long GRACEFUL_SHUTDOWN_TIMEOUT = 10;

    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private HttpServer httpServer;
    private ExecutorService httpServerExecutor;
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
            logWarn("Port validation failed: " + ex.getMessage());
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
            LOG.warn("Database MCP start blocked (" + reason + "): " + dependencyError);
            logWarn("Start blocked (" + reason + "): " + dependencyError);
            return StartResult.failure(dependencyError);
        }

        McpSettingsState settings = McpSettingsState.getInstance();
        int port = settings.getPort(scope);
        String portError = validatePortAvailability(port);
        if (portError != null) {
            LOG.warn("Database MCP start blocked (" + reason + "): " + portError);
            logWarn("Start blocked (" + reason + "): " + portError);
            return StartResult.failure(portError);
        }

        lock.lock();
        try {
            if (running.get()) {
                LOG.debug("MCP server already running, skip duplicate start: " + reason);
                logInfo("Start skipped because server is already running (" + reason + ")");
                return StartResult.success("Database MCP already running.");
            }

            InetSocketAddress address = new InetSocketAddress(BIND_HOST, port);
            httpServer = HttpServer.create(address, 0);
            httpServer.createContext(MCP_PATH, new McpHttpHandler(new IdeDatabaseFacade()));
            httpServer.createContext("/health", exchange -> {
                byte[] ok = "ok".getBytes();
                exchange.sendResponseHeaders(200, ok.length);
                exchange.getResponseBody().write(ok);
                exchange.close();
            });
            // 创建可管理的线程池，支持优雅关闭
            httpServerExecutor = createHttpServerExecutor();
            httpServer.setExecutor(httpServerExecutor);
            httpServer.start();

            runningPort = port;
            running.set(true);
            LOG.info("Database MCP started at " + getServiceUrl() + " (" + reason + ")");
            logInfo("Service started: " + getServiceUrl() + " (" + reason + ")");
            return StartResult.success("Database MCP started at " + getServiceUrl());
        } catch (IOException ex) {
            LOG.warn("Failed to start Database MCP", ex);
            logError("Failed to start service: " + ex.getMessage());
            stop("start-failed");
            return StartResult.failure("Failed to start Database MCP: " + ex.getMessage());
        } finally {
            lock.unlock();
        }
    }

    public void startIfNeeded(String reason) {
        StartResult result = startWithValidation(reason);
        if (!result.isSuccess()) {
            LOG.warn(result.getMessage());
            logWarn(result.getMessage());
        }
    }

    public void stop(String reason) {
        lock.lock();
        try {
            if (httpServer != null) {
                httpServer.stop(0);
                httpServer = null;
            }
            // 优雅关闭线程池
            shutdownExecutorGracefully(httpServerExecutor);
            httpServerExecutor = null;

            running.set(false);
            runningPort = -1;
            LOG.info("Database MCP stopped (" + reason + ")");
            logInfo("Service stopped (" + reason + ")");
        } finally {
            lock.unlock();
        }
    }

    public StartResult onSettingsChanged(int oldPort) {
        McpSettingsState settings = McpSettingsState.getInstance();
        boolean endpointChanged = oldPort != settings.getPortEffective();

        if (running.get() && endpointChanged) {
            stop("settings-endpoint-changed");
            logInfo("Endpoint changed, restarting service");
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
            LOG.warn("Database API class is unavailable", ex);
            return DatabaseMcpMessages.message("settings.databaseApiUnavailable", DATABASE_MANAGER_CLASS);
        }
    }

    private void logInfo(String message) {
        McpRuntimeLogService logService = getLogService();
        if (logService != null) {
            logService.info("server", message);
        }
    }

    private void logWarn(String message) {
        McpRuntimeLogService logService = getLogService();
        if (logService != null) {
            logService.warn("server", message);
        }
    }

    private void logError(String message) {
        McpRuntimeLogService logService = getLogService();
        if (logService != null) {
            logService.error("server", message);
        }
    }

    private McpRuntimeLogService getLogService() {
        return ApplicationManager.getApplication() == null
                ? null
                : ApplicationManager.getApplication().getService(McpRuntimeLogService.class);
    }

    /**
     * 接入 IDE 应用生命周期
     * 在 IDE 关闭时自动触发 dispose()
     */
    @Override
    public void dispose() {
        if (running.get()) {
            LOG.info("Gracefully shutting down Database MCP on application exit");
            logInfo("Gracefully shutting down on application exit");
            stop("application-exit");
        }
    }

    /**
     * 创建可管理的线程池，用于 HttpServer
     * 相比 Executors.newCachedThreadPool()，这个实现可以优雅关闭
     */
    private ExecutorService createHttpServerExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2,                      // corePoolSize: 最少保持 2 个线程
                10,                     // maximumPoolSize: 最多 10 个线程
                60,                     // keepAliveTime: 空闲 60 秒后回收
                TimeUnit.SECONDS,       // 时间单位
                new LinkedBlockingQueue<>(100), // 队列大小 100
                r -> {
                    Thread t = new Thread(r, "McpServer-Http-Worker");
                    t.setDaemon(false); // 不使用 daemon，确保优雅关闭
                    return t;
                }
        );
        // 允许核心线程超时后被回收
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * 优雅关闭线程池
     * 等待现有请求完成，然后再关闭
     */
    private void shutdownExecutorGracefully(ExecutorService executor) {
        if (executor == null || executor.isShutdown()) {
            return;
        }

        try {
            LOG.info("Starting graceful shutdown of HttpServer executor pool");

            // 第一步：停止接收新任务
            executor.shutdown();

            // 第二步：等待现有任务完成（带超时）
            if (!executor.awaitTermination(GRACEFUL_SHUTDOWN_TIMEOUT, TimeUnit.SECONDS)) {
                LOG.warn("HttpServer executor did not terminate within " + GRACEFUL_SHUTDOWN_TIMEOUT +
                        " seconds, forcing shutdown");

                // 第三步：强制关闭所有任务
                var unfinishedTasks = executor.shutdownNow();
                if (!unfinishedTasks.isEmpty()) {
                    LOG.warn("Forced shutdown of " + unfinishedTasks.size() + " pending tasks");
                }

                // 再等待一次，确保所有线程都已停止
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOG.error("HttpServer executor failed to shutdown after forced termination");
                }
            } else {
                LOG.info("HttpServer executor gracefully shut down");
            }
        } catch (InterruptedException ex) {
            LOG.warn("Interrupted while waiting for HttpServer executor shutdown", ex);
            // 再次尝试强制关闭
            var unfinishedTasks = executor.shutdownNow();
            if (!unfinishedTasks.isEmpty()) {
                LOG.warn("Forced shutdown of " + unfinishedTasks.size() + " pending tasks after interruption");
            }
            // 恢复中断状态
            Thread.currentThread().interrupt();
        }
    }
}
