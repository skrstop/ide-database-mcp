package io.skrstop.ide.databasemcp.startup;

import com.intellij.ide.ApplicationInitializedListener;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import io.skrstop.ide.databasemcp.service.McpRuntimeLogService;
import io.skrstop.ide.databasemcp.service.McpServerManager;
import io.skrstop.ide.databasemcp.settings.DatabaseMcpMessages;
import io.skrstop.ide.databasemcp.settings.McpSettingsState;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

public final class McpApplicationInitializedListener implements ApplicationInitializedListener {
    private static final AtomicBoolean START_ATTEMPTED = new AtomicBoolean(false);

    //    @Override
    public void componentsInitialized() {
        startOnAppInitializedOnce();
    }

    //    @Override
    public @Nullable Object execute(@NotNull Continuation<? super Unit> continuation) {
        startOnAppInitializedOnce();
        return Unit.INSTANCE;
    }

    private void startOnAppInitializedOnce() {
        if (!START_ATTEMPTED.compareAndSet(false, true)) {
            return;
        }

        McpSettingsState settings = McpSettingsState.getInstance();
        if (!shouldAutoStartOnAppInit(settings)) {
            logStartupSkipReason(settings);
            return;
        }

        McpServerManager.StartResult result = McpServerManager.getInstance().startWithValidation("app-initialized");
        if (!result.isSuccess()) {
            McpSettingsState.UiLanguage language = settings.getUiLanguageEffective();
            Messages.showErrorDialog(
                    DatabaseMcpMessages.message(language, "settings.startFailed") + "\n" + result.getMessage(),
                    DatabaseMcpMessages.message(language, "settings.error.title")
            );
        }
    }

    private void logStartupSkipReason(McpSettingsState settings) {
        McpSettingsState.PluginSettingsScope scope = settings.getPluginSettingsScope();
        if (scope == McpSettingsState.PluginSettingsScope.PROJECT
                && ProjectManager.getInstance().getOpenProjects().length == 0) {
            McpRuntimeLogService.getInstance().info(
                    "startup",
                    "Auto-start skipped: project scope selected but no opened project is available."
            );
            return;
        }

        if (!settings.isAutoStartEffective()) {
            McpRuntimeLogService.getInstance().info(
                    "startup",
                    "Auto-start skipped: auto-start is disabled in effective settings."
            );
        }
    }

    private boolean shouldAutoStartOnAppInit(McpSettingsState settings) {
        McpSettingsState.PluginSettingsScope scope = settings.getPluginSettingsScope();
        if (scope == McpSettingsState.PluginSettingsScope.PROJECT
                && ProjectManager.getInstance().getOpenProjects().length == 0) {
            // Avoid falling back to global defaults when project-scoped settings are selected.
            return false;
        }
        return settings.isAutoStartEffective();
    }

}
