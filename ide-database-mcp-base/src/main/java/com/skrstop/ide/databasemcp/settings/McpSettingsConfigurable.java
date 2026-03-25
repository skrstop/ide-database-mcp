package com.skrstop.ide.databasemcp.settings;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import com.skrstop.ide.databasemcp.service.McpServerManager;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ItemEvent;
import java.nio.file.Path;

public final class McpSettingsConfigurable implements Configurable {
    private static final String SETTINGS_FILE_NAME = "ide_database_mcp.xml";
    private static final Insets FULL_WIDTH_INSETS_COMPACT = new Insets(4, 8, 2, 8);
    private static final Insets FIELD_INSETS_COMPACT = new Insets(2, 8, 2, 8);

    private McpSettingsState.UiLanguage currentUiLanguage = McpSettingsState.UiLanguage.EN_US;

    private JPanel panel;
    private JCheckBox autoStartCheckBox;
    private JLabel languageLabel;
    private JLabel portLabel;
    private JLabel pluginSettingsScopeLabel;
    private JLabel dataSourceScopeLabel;
    private JLabel serviceStatusLabel;
    private JLabel serviceStatusValueLabel;
    private JLabel serviceAddressLabel;
    private JLabel serviceAddressValueLabel;
    private JLabel maxEntriesLabel;
    private JLabel maxFileSizeLabel;
    private JLabel maxLogFilesLabel;
    private JLabel readBufferSizeLabel;
    private JTextField portField;
    private JTextField maxEntriesField;
    private JTextField maxFileSizeField;
    private JTextField maxLogFilesField;
    private JTextField readBufferSizeField;
    private JComboBox<McpSettingsState.PluginSettingsScope> pluginSettingsScopeCombo;
    private JComboBox<McpSettingsState.DataSourceScope> dataSourceScopeCombo;
    private JComboBox<McpSettingsState.UiLanguage> uiLanguageCombo;
    private JButton viewConfigPathButton;
    private JButton copyAddressButton;
    private JButton startServiceButton;
    private JButton stopServiceButton;
    private JPanel logSettingsPanel;

    @Override
    public @Nls String getDisplayName() {
        return DatabaseMcpMessages.message("settings.display.name");
    }

    @Override
    public @Nullable JComponent createComponent() {
        panel = new JPanel(new BorderLayout());
        JPanel formPanel = new JPanel(new GridBagLayout());

        autoStartCheckBox = new JCheckBox();
        languageLabel = new JLabel();
        portLabel = new JLabel();
        pluginSettingsScopeLabel = new JLabel();
        dataSourceScopeLabel = new JLabel();
        serviceStatusLabel = new JLabel();
        serviceStatusValueLabel = new JLabel();
        serviceAddressLabel = new JLabel();
        serviceAddressValueLabel = new JLabel();
        maxEntriesLabel = new JLabel();
        maxFileSizeLabel = new JLabel();
        maxLogFilesLabel = new JLabel();
        readBufferSizeLabel = new JLabel();
        portField = new JTextField();
        portField.setColumns(8);
        ((AbstractDocument) portField.getDocument()).setDocumentFilter(new DigitsOnlyDocumentFilter());
        maxEntriesField = new JTextField();
        maxEntriesField.setColumns(10);
        ((AbstractDocument) maxEntriesField.getDocument()).setDocumentFilter(new DigitsOnlyDocumentFilter());
        maxFileSizeField = new JTextField();
        maxFileSizeField.setColumns(10);
        ((AbstractDocument) maxFileSizeField.getDocument()).setDocumentFilter(new DigitsOnlyDocumentFilter());
        maxLogFilesField = new JTextField();
        maxLogFilesField.setColumns(10);
        ((AbstractDocument) maxLogFilesField.getDocument()).setDocumentFilter(new DigitsOnlyDocumentFilter());
        readBufferSizeField = new JTextField();
        readBufferSizeField.setColumns(10);
        ((AbstractDocument) readBufferSizeField.getDocument()).setDocumentFilter(new DigitsOnlyDocumentFilter());
        pluginSettingsScopeCombo = new JComboBox<>(McpSettingsState.PluginSettingsScope.values());
        dataSourceScopeCombo = new JComboBox<>(McpSettingsState.DataSourceScope.values());
        uiLanguageCombo = new JComboBox<>(McpSettingsState.UiLanguage.values());
        viewConfigPathButton = new JButton();
        copyAddressButton = new JButton();
        startServiceButton = new JButton();
        stopServiceButton = new JButton();

        // Make status/address actions easy to spot.
        serviceStatusLabel.setFont(serviceStatusLabel.getFont().deriveFont(Font.BOLD));
        serviceStatusValueLabel.setFont(serviceStatusValueLabel.getFont().deriveFont(Font.BOLD));

        pluginSettingsScopeCombo.setRenderer(new PluginSettingsScopeRenderer());
        dataSourceScopeCombo.setRenderer(new ScopeRenderer());
        uiLanguageCombo.setRenderer(new LanguageRenderer());

        McpSettingsState settings = McpSettingsState.getInstance();
        McpSettingsState.PluginSettingsScope initialScope = settings.getPluginSettingsScope();
        McpSettingsState.UiLanguage initialLanguage = settings.getUiLanguage(initialScope);
        currentUiLanguage = initialLanguage;
        pluginSettingsScopeCombo.setSelectedItem(initialScope);
        uiLanguageCombo.setSelectedItem(initialLanguage);
        autoStartCheckBox.setSelected(settings.isAutoStart(initialScope));
        portField.setText(String.valueOf(settings.getPort(initialScope)));
        dataSourceScopeCombo.setSelectedItem(settings.getDataSourceScope(initialScope));
        maxEntriesField.setText(String.valueOf(settings.getMaxEntries(initialScope)));
        maxFileSizeField.setText(String.valueOf(settings.getMaxFileSize(initialScope)));
        maxLogFilesField.setText(String.valueOf(settings.getMaxLogFiles(initialScope)));
        readBufferSizeField.setText(String.valueOf(settings.getReadBufferSize(initialScope)));

        uiLanguageCombo.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED && e.getItem() instanceof McpSettingsState.UiLanguage) {
                refreshTexts((McpSettingsState.UiLanguage) e.getItem());
            }
        });
        pluginSettingsScopeCombo.addActionListener(e -> reloadFieldsForSelectedScope());
        viewConfigPathButton.addActionListener(e -> showConfigPath());
        copyAddressButton.addActionListener(e -> copyServiceAddress());
        startServiceButton.addActionListener(e -> startServiceManually());
        stopServiceButton.addActionListener(e -> stopServiceManually());

        JPanel pluginScopePanel = new JPanel(new BorderLayout(4, 0));
        pluginScopePanel.add(pluginSettingsScopeCombo, BorderLayout.CENTER);
        pluginScopePanel.add(viewConfigPathButton, BorderLayout.EAST);

        JPanel addressPanel = new JPanel(new BorderLayout(4, 0));
        addressPanel.add(serviceAddressValueLabel, BorderLayout.CENTER);
        addressPanel.add(copyAddressButton, BorderLayout.EAST);

        JPanel serviceStatusPanel = new JPanel(new BorderLayout(4, 0));
        JPanel statusActionsPanel = new JPanel(new GridLayout(1, 2, 4, 0));
        statusActionsPanel.add(startServiceButton);
        statusActionsPanel.add(stopServiceButton);
        serviceStatusPanel.add(serviceStatusValueLabel, BorderLayout.WEST);
        serviceStatusPanel.add(statusActionsPanel, BorderLayout.CENTER);

        JPanel portAndStatusRow = new JPanel(new GridLayout(1, 2, 8, 0));
        portAndStatusRow.add(createInlineLabeledPanel(portLabel, portField));
        portAndStatusRow.add(createInlineLabeledPanel(serviceStatusLabel, serviceStatusPanel));

        logSettingsPanel = new JPanel(new GridBagLayout());
        addLogGridItem(logSettingsPanel, maxEntriesLabel, maxEntriesField, 0, 0);
        addLogGridItem(logSettingsPanel, maxFileSizeLabel, maxFileSizeField, 1, 0);
        addLogGridItem(logSettingsPanel, maxLogFilesLabel, maxLogFilesField, 0, 1);
        addLogGridItem(logSettingsPanel, readBufferSizeLabel, readBufferSizeField, 1, 1);

        int row = 0;
        addField(formPanel, languageLabel, uiLanguageCombo, row++);
        addFullWidth(formPanel, autoStartCheckBox, row++);
        addFullWidth(formPanel, portAndStatusRow, row++);
        addField(formPanel, serviceAddressLabel, addressPanel, row++);
        addField(formPanel, pluginSettingsScopeLabel, pluginScopePanel, row++);
        addField(formPanel, dataSourceScopeLabel, dataSourceScopeCombo, row++);
        addFullWidth(formPanel, logSettingsPanel, row);

        panel.add(formPanel, BorderLayout.NORTH);
        refreshTexts(initialLanguage);
        updateRuntimeUiState();
        return panel;
    }

    @Override
    public boolean isModified() {
        if (panel == null) {
            return false;
        }
        McpSettingsState settings = McpSettingsState.getInstance();
        McpSettingsState.PluginSettingsScope selectedScope = selectedPluginSettingsScope();
        return settings.getPluginSettingsScope() != selectedScope
                || settings.isAutoStart(selectedScope) != autoStartCheckBox.isSelected()
                || settings.getPort(selectedScope) != parsePortField(settings.getPort(selectedScope))
                || settings.getDataSourceScope(selectedScope) != dataSourceScopeCombo.getSelectedItem()
                || settings.getUiLanguage(selectedScope) != uiLanguageCombo.getSelectedItem()
                || settings.getMaxEntries(selectedScope) != parseIntField(maxEntriesField, settings.getMaxEntries(selectedScope))
                || settings.getMaxFileSize(selectedScope) != parseLongField(maxFileSizeField, settings.getMaxFileSize(selectedScope))
                || settings.getMaxLogFiles(selectedScope) != parseIntField(maxLogFilesField, settings.getMaxLogFiles(selectedScope))
                || settings.getReadBufferSize(selectedScope) != parseIntField(readBufferSizeField, settings.getReadBufferSize(selectedScope));
    }

    @Override
    public void apply() {
        McpSettingsState settings = McpSettingsState.getInstance();
        int oldPort = settings.getPortEffective();
        McpSettingsState.PluginSettingsScope selectedScope = selectedPluginSettingsScope();
        McpSettingsState.UiLanguage language = selectedUiLanguage();

        Integer parsedPort = parsePortFieldStrict();
        if (parsedPort == null || parsedPort < 1 || parsedPort > 65535) {
            showError(language, DatabaseMcpMessages.message(language, "settings.invalidPort"));
            return;
        }

        McpServerManager manager = McpServerManager.getInstance();
        String portError = manager.validatePortAvailability(parsedPort);
        if (portError != null) {
            showError(language, DatabaseMcpMessages.message(language, "settings.portInUse", parsedPort));
            return;
        }

        Integer maxEntries = parseIntFieldStrict(maxEntriesField);
        if (maxEntries == null || maxEntries < 100) {
            showError(language, DatabaseMcpMessages.message(language, "settings.invalidMaxEntries"));
            return;
        }

        Long maxFileSize = parseLongFieldStrict(maxFileSizeField);
        if (maxFileSize == null || maxFileSize < 1024L * 1024) {
            showError(language, DatabaseMcpMessages.message(language, "settings.invalidMaxFileSize"));
            return;
        }

        Integer maxLogFiles = parseIntFieldStrict(maxLogFilesField);
        if (maxLogFiles == null || maxLogFiles < 1) {
            showError(language, DatabaseMcpMessages.message(language, "settings.invalidMaxLogFiles"));
            return;
        }

        Integer readBufferSize = parseIntFieldStrict(readBufferSizeField);
        if (readBufferSize == null || readBufferSize < 4 * 1024) {
            showError(language, DatabaseMcpMessages.message(language, "settings.invalidReadBufferSize"));
            return;
        }

        settings.setPluginSettingsScope(selectedScope);
        settings.setAutoStart(selectedScope, autoStartCheckBox.isSelected());
        settings.setPort(selectedScope, parsedPort);
        settings.setDataSourceScope(selectedScope, (McpSettingsState.DataSourceScope) dataSourceScopeCombo.getSelectedItem());
        settings.setUiLanguage(selectedScope, language);
        settings.setMaxEntries(selectedScope, maxEntries);
        settings.setMaxFileSize(selectedScope, maxFileSize);
        settings.setMaxLogFiles(selectedScope, maxLogFiles);
        settings.setReadBufferSize(selectedScope, readBufferSize);

        McpServerManager.StartResult result = manager.onSettingsChanged(oldPort);
        if (!result.isSuccess()) {
            showError(language, DatabaseMcpMessages.message(language, "settings.startFailed") + "\n" + result.getMessage());
        }

        refreshTexts(language);
        updateRuntimeUiState();
    }

    @Override
    public void reset() {
        McpSettingsState settings = McpSettingsState.getInstance();
        McpSettingsState.PluginSettingsScope scope = settings.getPluginSettingsScope();
        pluginSettingsScopeCombo.setSelectedItem(scope);
        uiLanguageCombo.setSelectedItem(settings.getUiLanguage(scope));
        autoStartCheckBox.setSelected(settings.isAutoStart(scope));
        portField.setText(String.valueOf(settings.getPort(scope)));
        dataSourceScopeCombo.setSelectedItem(settings.getDataSourceScope(scope));
        maxEntriesField.setText(String.valueOf(settings.getMaxEntries(scope)));
        maxFileSizeField.setText(String.valueOf(settings.getMaxFileSize(scope)));
        maxLogFilesField.setText(String.valueOf(settings.getMaxLogFiles(scope)));
        readBufferSizeField.setText(String.valueOf(settings.getReadBufferSize(scope)));
        refreshTexts(settings.getUiLanguage(scope));
        updateRuntimeUiState();
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        autoStartCheckBox = null;
        languageLabel = null;
        portLabel = null;
        pluginSettingsScopeLabel = null;
        dataSourceScopeLabel = null;
        serviceStatusLabel = null;
        serviceStatusValueLabel = null;
        serviceAddressLabel = null;
        serviceAddressValueLabel = null;
        maxEntriesLabel = null;
        maxFileSizeLabel = null;
        maxLogFilesLabel = null;
        readBufferSizeLabel = null;
        portField = null;
        maxEntriesField = null;
        maxFileSizeField = null;
        maxLogFilesField = null;
        readBufferSizeField = null;
        pluginSettingsScopeCombo = null;
        dataSourceScopeCombo = null;
        uiLanguageCombo = null;
        viewConfigPathButton = null;
        copyAddressButton = null;
        startServiceButton = null;
        stopServiceButton = null;
        logSettingsPanel = null;
    }

    private int parsePortField(int fallback) {
        try {
            return Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Integer parsePortFieldStrict() {
        String value = portField.getText().trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int parseIntField(JTextField field, int fallback) {
        try {
            return Integer.parseInt(field.getText().trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private long parseLongField(JTextField field, long fallback) {
        try {
            return Long.parseLong(field.getText().trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Integer parseIntFieldStrict(JTextField field) {
        String value = field.getText().trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long parseLongFieldStrict(JTextField field) {
        String value = field.getText().trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void addFullWidth(JPanel root, JComponent component, int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.insets = FULL_WIDTH_INSETS_COMPACT;
        root.add(component, c);
    }

    private void addLogGridItem(JPanel root, JLabel label, JTextField field, int gridX, int gridY) {
        JPanel item = new JPanel(new BorderLayout(0, 2));
        item.add(label, BorderLayout.NORTH);
        item.add(field, BorderLayout.CENTER);

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = gridX;
        c.gridy = gridY;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.insets = FIELD_INSETS_COMPACT;
        root.add(item, c);
    }

    private JPanel createInlineLabeledPanel(JLabel label, JComponent component) {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.add(label, BorderLayout.WEST);
        panel.add(component, BorderLayout.CENTER);
        return panel;
    }

    private void addField(JPanel root, JLabel label, JComponent component, int row) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = FIELD_INSETS_COMPACT;
        root.add(label, labelConstraints);

        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = row;
        fieldConstraints.weightx = 1;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.anchor = GridBagConstraints.WEST;
        fieldConstraints.insets = FIELD_INSETS_COMPACT;
        root.add(component, fieldConstraints);
    }

    private McpSettingsState.UiLanguage selectedUiLanguage() {
        Object selected = uiLanguageCombo.getSelectedItem();
        if (selected instanceof McpSettingsState.UiLanguage) {
            return (McpSettingsState.UiLanguage) selected;
        }
        return currentUiLanguage;
    }

    private McpSettingsState.PluginSettingsScope selectedPluginSettingsScope() {
        Object selected = pluginSettingsScopeCombo.getSelectedItem();
        if (selected instanceof McpSettingsState.PluginSettingsScope) {
            return (McpSettingsState.PluginSettingsScope) selected;
        }
        return McpSettingsState.PluginSettingsScope.GLOBAL;
    }

    private void refreshTexts(McpSettingsState.UiLanguage language) {
        currentUiLanguage = language;
        languageLabel.setText(DatabaseMcpMessages.message(language, "settings.language"));
        autoStartCheckBox.setText(DatabaseMcpMessages.message(language, "settings.autoStart"));
        portLabel.setText(DatabaseMcpMessages.message(language, "settings.port"));
        pluginSettingsScopeLabel.setText(DatabaseMcpMessages.message(language, "settings.pluginScope"));
        dataSourceScopeLabel.setText(DatabaseMcpMessages.message(language, "settings.dataSourceScope"));
        maxEntriesLabel.setText(DatabaseMcpMessages.message(language, "settings.maxEntries"));
        maxFileSizeLabel.setText(DatabaseMcpMessages.message(language, "settings.maxFileSize"));
        maxLogFilesLabel.setText(DatabaseMcpMessages.message(language, "settings.maxLogFiles"));
        readBufferSizeLabel.setText(DatabaseMcpMessages.message(language, "settings.readBufferSize"));
        if (logSettingsPanel != null) {
            logSettingsPanel.setBorder(BorderFactory.createTitledBorder(
                    DatabaseMcpMessages.message(language, "settings.logGroupTitle")
            ));
        }
        serviceStatusLabel.setText(DatabaseMcpMessages.message(language, "settings.serviceStatus"));
        serviceAddressLabel.setText(DatabaseMcpMessages.message(language, "settings.serviceAddress"));
        viewConfigPathButton.setText(DatabaseMcpMessages.message(language, "settings.viewConfigPath"));
        copyAddressButton.setText(DatabaseMcpMessages.message(language, "settings.copy"));
        startServiceButton.setText(DatabaseMcpMessages.message(language, "settings.start"));
        stopServiceButton.setText(DatabaseMcpMessages.message(language, "settings.stop"));
        syncStartStopButtonWidth();
        pluginSettingsScopeCombo.repaint();
        dataSourceScopeCombo.repaint();
        uiLanguageCombo.repaint();
        if (panel != null) {
            panel.revalidate();
            panel.repaint();
        }
        updateRuntimeUiState();
    }

    private void reloadFieldsForSelectedScope() {
        if (pluginSettingsScopeCombo == null || uiLanguageCombo == null) {
            return;
        }

        McpSettingsState settings = McpSettingsState.getInstance();
        McpSettingsState.PluginSettingsScope scope = selectedPluginSettingsScope();
        McpSettingsState.UiLanguage language = settings.getUiLanguage(scope);

        uiLanguageCombo.setSelectedItem(language);
        autoStartCheckBox.setSelected(settings.isAutoStart(scope));
        portField.setText(String.valueOf(settings.getPort(scope)));
        dataSourceScopeCombo.setSelectedItem(settings.getDataSourceScope(scope));
        maxEntriesField.setText(String.valueOf(settings.getMaxEntries(scope)));
        maxFileSizeField.setText(String.valueOf(settings.getMaxFileSize(scope)));
        maxLogFilesField.setText(String.valueOf(settings.getMaxLogFiles(scope)));
        readBufferSizeField.setText(String.valueOf(settings.getReadBufferSize(scope)));
        refreshTexts(language);
    }

    private void showConfigPath() {
        McpSettingsState.UiLanguage language = selectedUiLanguage();
        McpSettingsState.PluginSettingsScope scope = selectedPluginSettingsScope();
        String path;
        if (scope == McpSettingsState.PluginSettingsScope.GLOBAL) {
            path = Path.of(PathManager.getOptionsPath(), SETTINGS_FILE_NAME).toString();
            Messages.showInfoMessage(
                    DatabaseMcpMessages.message(language, "settings.configPath.global", path),
                    DatabaseMcpMessages.message(language, "settings.configPath.title")
            );
            return;
        }

        Project project = firstOpenedProject();
        if (project == null) {
            Messages.showWarningDialog(
                    DatabaseMcpMessages.message(language, "settings.configPath.projectUnavailable"),
                    DatabaseMcpMessages.message(language, "settings.configPath.title")
            );
            return;
        }

        path = resolveProjectConfigPath(project);
        if (path == null || path.isBlank()) {
            Messages.showWarningDialog(
                    DatabaseMcpMessages.message(language, "settings.configPath.projectUnavailable"),
                    DatabaseMcpMessages.message(language, "settings.configPath.title")
            );
            return;
        }

        Messages.showInfoMessage(
                DatabaseMcpMessages.message(language, "settings.configPath.project", path),
                DatabaseMcpMessages.message(language, "settings.configPath.title")
        );
    }

    private String resolveProjectConfigPath(Project project) {
        String projectFilePath = project.getProjectFilePath();
        if (projectFilePath == null || projectFilePath.isBlank()) {
            return null;
        }

        Path projectFile = Path.of(projectFilePath);
        Path configDir = projectFile.getParent();
        if (configDir == null) {
            return null;
        }
        return configDir.resolve(SETTINGS_FILE_NAME).toString();
    }

    private Project firstOpenedProject() {
        Project[] opened = ProjectManager.getInstance().getOpenProjects();
        if (opened.length == 0) {
            return null;
        }
        return opened[0];
    }

    private void updateRuntimeUiState() {
        McpSettingsState.UiLanguage language = selectedUiLanguage();
        McpServerManager manager = McpServerManager.getInstance();
        boolean running = manager.isRunning();

        serviceStatusValueLabel.setText(running
                ? DatabaseMcpMessages.message(language, "settings.status.running")
                : DatabaseMcpMessages.message(language, "settings.status.stopped"));
        serviceStatusValueLabel.setForeground(running ? new Color(0x2E7D32) : new Color(0xC62828));
        serviceAddressValueLabel.setText(running ? manager.getServiceUrl() : "");
        copyAddressButton.setEnabled(running);
        startServiceButton.setEnabled(!running);
        stopServiceButton.setEnabled(running);
    }

    private void copyServiceAddress() {
        String address = serviceAddressValueLabel.getText();
        if (address == null || address.isBlank()) {
            return;
        }
        CopyPasteManager.getInstance().setContents(new StringSelection(address.trim()));
    }

    private void syncStartStopButtonWidth() {
        Dimension startSize = startServiceButton.getPreferredSize();
        Dimension stopSize = stopServiceButton.getPreferredSize();
        int width = Math.max(startSize.width, stopSize.width);
        int startHeight = startSize.height;
        int stopHeight = stopSize.height;
        startServiceButton.setPreferredSize(new Dimension(width, startHeight));
        stopServiceButton.setPreferredSize(new Dimension(width, stopHeight));
    }

    private void startServiceManually() {
        McpSettingsState.UiLanguage language = selectedUiLanguage();
        Integer parsedPort = parsePortFieldStrict();
        if (parsedPort == null || parsedPort < 1 || parsedPort > 65535) {
            showError(language, DatabaseMcpMessages.message(language, "settings.invalidPort"));
            return;
        }

        McpServerManager manager = McpServerManager.getInstance();
        String portError = manager.validatePortAvailability(parsedPort);
        if (portError != null) {
            showError(language, DatabaseMcpMessages.message(language, "settings.portInUse", parsedPort));
            return;
        }

        // Keep runtime actions aligned with current UI values before manual start.
        McpSettingsState settings = McpSettingsState.getInstance();
        settings.setPort(parsedPort);
        settings.setUiLanguage(language);

        McpServerManager.StartResult result = manager.startWithValidation("manual-start");
        if (!result.isSuccess()) {
            showError(language, DatabaseMcpMessages.message(language, "settings.startFailed") + "\n" + result.getMessage());
        }
        updateRuntimeUiState();
    }

    private void stopServiceManually() {
        McpServerManager.getInstance().stop("manual-stop");
        updateRuntimeUiState();
    }

    private void showError(McpSettingsState.UiLanguage language, String message) {
        Messages.showErrorDialog(message, DatabaseMcpMessages.message(language, "settings.error.title"));
    }

    private static final class DigitsOnlyDocumentFilter extends DocumentFilter {
        @Override
        public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
            if (string != null && isDigits(string)) {
                super.insertString(fb, offset, string, attr);
            }
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
            if (text == null || isDigits(text)) {
                super.replace(fb, offset, length, text, attrs);
            }
        }

        private boolean isDigits(String value) {
            for (int i = 0; i < value.length(); i++) {
                if (!Character.isDigit(value.charAt(i))) {
                    return false;
                }
            }
            return true;
        }
    }

    private final class PluginSettingsScopeRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            McpSettingsState.UiLanguage language = selectedUiLanguage();
            if (value == McpSettingsState.PluginSettingsScope.GLOBAL) {
                setText(DatabaseMcpMessages.message(language, "settings.pluginScope.global"));
            } else if (value == McpSettingsState.PluginSettingsScope.PROJECT) {
                setText(DatabaseMcpMessages.message(language, "settings.pluginScope.project"));
            }
            return c;
        }
    }

    private final class ScopeRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            McpSettingsState.UiLanguage language = selectedUiLanguage();
            if (value == McpSettingsState.DataSourceScope.GLOBAL) {
                setText(DatabaseMcpMessages.message(language, "settings.scope.global"));
            } else if (value == McpSettingsState.DataSourceScope.PROJECT) {
                setText(DatabaseMcpMessages.message(language, "settings.scope.project"));
            } else if (value == McpSettingsState.DataSourceScope.ALL) {
                setText(DatabaseMcpMessages.message(language, "settings.scope.all"));
            }
            return c;
        }
    }

    private final class LanguageRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            McpSettingsState.UiLanguage language = selectedUiLanguage();
            if (value == McpSettingsState.UiLanguage.ZH_CN) {
                setText(DatabaseMcpMessages.message(language, "settings.language.zh"));
            } else if (value == McpSettingsState.UiLanguage.EN_US) {
                setText(DatabaseMcpMessages.message(language, "settings.language.en"));
            }
            return c;
        }
    }
}
