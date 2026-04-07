package com.skrstop.ide.databasemcp.service;

import com.intellij.ide.AppLifecycleListener;

/**
 * IDEA 应用生命周期监听器
 * 在应用关闭前优雅关闭 HttpServer 服务
 */
public class McpAppLifecycleListener implements AppLifecycleListener {

    /**
     * 在应用关闭前触发
     * 此时 IDEA 还未开始销毁应用程序
     */
    @Override
    public void appClosing() {
        McpRuntimeLogService.logInfo("lifecycle", "IDE application is closing, performing cleanup...");
        McpServerManager serverManager = McpServerManager.getInstance();
        if (serverManager.isRunning()) {
            McpRuntimeLogService.logInfo("lifecycle", "Stopping MCP server before application exit");
            serverManager.stop("app-lifecycle-closing");
        }
    }

    /**
     * 在应用关闭完成后触发
     * 此时大多数应用程序服务已被销毁
     */
    @Override
    public void appWillBeClosed(boolean isRestart) {
        McpRuntimeLogService.logInfo("lifecycle", "IDE application will be closed (isRestart=" + isRestart + ")");
        // 再次确保服务已关闭
        McpServerManager serverManager = McpServerManager.getInstance();
        if (serverManager.isRunning()) {
            McpRuntimeLogService.logWarn("lifecycle", "MCP server still running, forcing stop before application shutdown");
            serverManager.stop("app-lifecycle-will-close");
        }
    }
}
