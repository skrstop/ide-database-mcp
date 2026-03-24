package com.skrstop.ide.databasemcp.toolwindow;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.skrstop.ide.databasemcp.icons.DatabaseMcpIcons;
import com.skrstop.ide.databasemcp.service.PluginStateCompat;
import com.skrstop.ide.databasemcp.settings.DatabaseMcpMessages;
import com.skrstop.ide.databasemcp.settings.McpSettingsConfigurable;
import icons.DatabaseIcons;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class DatabaseMcpToolWindowFactory implements ToolWindowFactory, DumbAware {
    private static final String DATABASE_PLUGIN_ID = "com.intellij.database";
    private static final String DATABASE_MANAGER_CLASS = "com.intellij.database.psi.DataSourceManager";
    private static final List<String> DATABASE_TOOLWINDOW_IDS = List.of("Database", "DatabaseView", "Database Explorer");

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        DatabaseMcpToolWindowPanel panel = new DatabaseMcpToolWindowPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel.getRootPanel(), "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);

        DumbAwareAction openSettings = new DumbAwareAction(
                DatabaseMcpMessages.message("toolwindow.openSettings"),
                DatabaseMcpMessages.message("toolwindow.openSettings"),
                DatabaseMcpIcons.ToolWindowSettings
        ) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, McpSettingsConfigurable.class);
            }
        };

        DumbAwareAction openDatabase = new DumbAwareAction(
                DatabaseMcpMessages.message("toolwindow.openDatabase"),
                DatabaseMcpMessages.message("toolwindow.openDatabase"),
                DatabaseIcons.ToolWindowDatabase
        ) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                String dependencyError = getDatabaseDependencyError();
                if (dependencyError != null) {
                    Messages.showWarningDialog(
                            project,
                            dependencyError,
                            DatabaseMcpMessages.message("settings.error.title")
                    );
                    return;
                }

                ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
                ToolWindow databaseToolWindow = null;
                for (String toolWindowId : DATABASE_TOOLWINDOW_IDS) {
                    databaseToolWindow = toolWindowManager.getToolWindow(toolWindowId);
                    if (databaseToolWindow != null) {
                        break;
                    }
                }
                if (databaseToolWindow != null) {
                    databaseToolWindow.activate(null, true);
                    return;
                }
                Messages.showWarningDialog(
                        project,
                        DatabaseMcpMessages.message("toolwindow.databaseUnavailable"),
                        DatabaseMcpMessages.message("settings.error.title")
                );
            }
        };

        toolWindow.setTitleActions(List.of(openDatabase, openSettings));
    }

    private static String getDatabaseDependencyError() {
        PluginId pluginId = PluginId.getId(DATABASE_PLUGIN_ID);
        if (PluginStateCompat.isPluginUnavailable(pluginId)) {
            return DatabaseMcpMessages.message("settings.databasePluginMissing", DATABASE_PLUGIN_ID);
        }

        try {
            Class.forName(DATABASE_MANAGER_CLASS, false, DatabaseMcpToolWindowFactory.class.getClassLoader());
            return null;
        } catch (Throwable ex) {
            return DatabaseMcpMessages.message("settings.databaseApiUnavailable", DATABASE_MANAGER_CLASS);
        }
    }
}

