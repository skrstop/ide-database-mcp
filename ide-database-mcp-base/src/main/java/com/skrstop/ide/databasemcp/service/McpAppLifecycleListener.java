package com.skrstop.ide.databasemcp.service;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.application.ApplicationManager;

/**
 * IDEA 应用生命周期监听器
 * 在应用关闭前优雅关闭 HttpServer 服务
 */
public class McpAppLifecycleListener implements AppLifecycleListener {

    private static McpRuntimeLogService logService() {
        return ApplicationManager.getApplication() == null
                ? null
                : ApplicationManager.getApplication().getService(McpRuntimeLogService.class);
    }

    private static void logInfo(String message) {
        McpRuntimeLogService service = logService();
        if (service != null) {
            service.info("lifecycle", message);
        }
    }

    private static void logWarn(String message) {
        McpRuntimeLogService service = logService();
        if (service != null) {
            service.warn("lifecycle", message);
        }
    }


    /**
     * 在应用关闭前触发
     * 此时 IDEA 还未开始销毁应用程序
     */
    @Override
    public void appClosing() {
        logInfo("IDE application is closing, performing cleanup...");
        McpServerManager serverManager = McpServerManager.getInstance();
        if (serverManager.isRunning()) {
            logInfo("Stopping MCP server before application exit");
            serverManager.stop("app-lifecycle-closing");
        }
    }

    /**
     * 在应用关闭完成后触发
     * 此时大多数应用程序服务已被销毁
     */
    @Override
    public void appWillBeClosed(boolean isRestart) {
        logInfo("IDE application will be closed (isRestart=" + isRestart + ")");
        // 再次确保服务已关闭
        McpServerManager serverManager = McpServerManager.getInstance();
        if (serverManager.isRunning()) {
            logWarn("MCP server still running, forcing stop before application shutdown");
            serverManager.stop("app-lifecycle-will-close");
        }
    }
}

