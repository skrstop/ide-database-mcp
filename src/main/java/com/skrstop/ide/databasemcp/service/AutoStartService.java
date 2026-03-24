package com.skrstop.ide.databasemcp.service;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.skrstop.ide.databasemcp.settings.DatabaseMcpMessages;
import com.skrstop.ide.databasemcp.settings.McpSettingsState;

import java.util.concurrent.atomic.AtomicBoolean;

@Service(Service.Level.APP)
public final class AutoStartService {
    private final AtomicBoolean attempted = new AtomicBoolean(false);

    public static AutoStartService getInstance() {
        return ApplicationManager.getApplication().getService(AutoStartService.class);
    }

    public void tryAutoStartOnProjectStartup(Project project) {
        McpSettingsState settings = McpSettingsState.getInstance();
        McpSettingsState.PluginSettingsScope scope = settings.getPluginSettingsScope();
        if (!settings.isAutoStart(scope)) {
            McpRuntimeLogService.getInstance().info("startup", "Auto-start skipped: auto-start is disabled.");
            return;
        }

        if (!attempted.compareAndSet(false, true)) {
            return;
        }

        String reason = scope == McpSettingsState.PluginSettingsScope.PROJECT
                ? "auto-start-project"
                : "auto-start-global";
        McpServerManager.StartResult result = McpServerManager.getInstance().startWithValidation(reason, scope);
        if (result.isSuccess()) {
            return;
        }

        McpSettingsState.UiLanguage language = settings.getUiLanguage(scope);
        String message = DatabaseMcpMessages.message(language, "settings.startFailed") + "\n" + result.getMessage();
        String title = DatabaseMcpMessages.message(language, "settings.error.title");
        if (project != null) {
            Messages.showErrorDialog(project, message, title);
        } else {
            Messages.showErrorDialog(message, title);
        }
    }
}

