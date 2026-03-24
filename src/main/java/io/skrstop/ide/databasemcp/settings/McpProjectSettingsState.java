package io.skrstop.ide.databasemcp.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Service(Service.Level.PROJECT)
@State(
        name = "DatabaseMcpProjectSettings",
        storages = @Storage("database_mcp.xml")
)
public final class McpProjectSettingsState implements PersistentStateComponent<McpSettingsState.State> {
    private McpSettingsState.State state = new McpSettingsState.State();

    public static McpProjectSettingsState getInstance(Project project) {
        return project.getService(McpProjectSettingsState.class);
    }

    @Override
    public @Nullable McpSettingsState.State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull McpSettingsState.State state) {
        this.state = state;
    }

    McpSettingsState.State getCurrentState() {
        return state;
    }
}

