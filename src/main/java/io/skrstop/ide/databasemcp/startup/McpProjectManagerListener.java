package io.skrstop.ide.databasemcp.startup;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import io.skrstop.ide.databasemcp.service.AutoStartService;
import org.jetbrains.annotations.NotNull;

public final class McpProjectManagerListener implements StartupActivity.DumbAware {
    @Override
    public void runActivity(@NotNull Project project) {
        AutoStartService.getInstance().tryAutoStartOnProjectStartup(project);
    }
}

