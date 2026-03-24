package io.skrstop.ide.databasemcp.toolwindow;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.SearchTextField;
import io.skrstop.ide.databasemcp.service.McpMethodMetricsService;
import io.skrstop.ide.databasemcp.service.McpRuntimeLogService;
import io.skrstop.ide.databasemcp.service.McpServerManager;
import io.skrstop.ide.databasemcp.settings.DatabaseMcpMessages;
import io.skrstop.ide.databasemcp.settings.McpSettingsState;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.text.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.text.DecimalFormat;
import java.util.*;
import java.util.List;

public final class DatabaseMcpToolWindowPanel implements Disposable {
    private static final Color STATUS_RUNNING = new Color(0x1B5E20);
    private static final Color STATUS_STOPPED = new Color(0xB71C1C);
    private static final Color LOG_INFO_FG = new JBColor(new Color(0x2E7D32), new Color(0x81C784));
    private static final Color LOG_WARN_FG = new JBColor(new Color(0xB26A00), new Color(0xFFD54F));
    private static final Color LOG_ERROR_FG = new JBColor(new Color(0xC62828), new Color(0xEF9A9A));

    private final Project project;
    private McpSettingsState.UiLanguage currentLanguage;

    private final JPanel rootPanel;
    private final JPanel serviceSection;
    private final JPanel methodCounterSection;
    private final JPanel logSection;
    private final JSplitPane topSplitPane;
    private final JSplitPane bottomSplitPane;

    private final JLabel serviceRunningLabel;
    private final JLabel statusValueLabel;
    private final JLabel serviceAddressLabel;
    private final JLabel addressValueLabel;
    private final JButton copyAddressButton;
    private final JButton startServiceButton;
    private final JButton stopServiceButton;

    private final JButton clearCounterButton;
    private final JTable methodTable;
    private final DefaultTableModel methodTableModel;
    private final TableRowSorter<DefaultTableModel> methodTableSorter;

    private final NoWrapTextPane logTextArea;
    private final JScrollPane logScrollPane;
    private final SearchTextField logSearchField;
    private final JButton logPrevMatchButton;
    private final JButton logNextMatchButton;
    private final JButton clearLogButton;
    private final JButton viewLogFilePathButton;
    private final Timer refreshTimer;
    private final List<int[]> logMatchRanges = new ArrayList<>();
    private int selectedLogMatchIndex = -1;
    private String lastRenderedLogContent = "";

    public DatabaseMcpToolWindowPanel(Project project) {
        this.project = project;
        this.currentLanguage = McpSettingsState.getInstance().getUiLanguageEffective();
        this.rootPanel = new JPanel(new BorderLayout());

        serviceRunningLabel = new JLabel();
        serviceRunningLabel.setFont(serviceRunningLabel.getFont().deriveFont(Font.BOLD));
        statusValueLabel = new JLabel("-");
        statusValueLabel.setFont(statusValueLabel.getFont().deriveFont(Font.BOLD));

        serviceAddressLabel = new JLabel();
        addressValueLabel = new JLabel("-");
        copyAddressButton = new JButton();
        startServiceButton = new JButton();
        stopServiceButton = new JButton();

        clearCounterButton = new JButton();
        methodTableModel = new DefaultTableModel(new Object[][]{}, new Object[]{"Method", "Calls", "Avg(ms)", "Total(ms)"}) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return switch (columnIndex) {
                    case 1 -> Long.class;
                    case 2, 3 -> Double.class;
                    default -> String.class;
                };
            }
        };

        methodTable = new JTable(methodTableModel);
        methodTable.setFillsViewportHeight(true);
        methodTable.setRowHeight(Math.max(22, methodTable.getRowHeight() + 4));
        methodTableSorter = new TableRowSorter<>(methodTableModel);
        methodTable.setRowSorter(methodTableSorter);

        DefaultTableCellRenderer decimalRenderer = new DefaultTableCellRenderer() {
            private final DecimalFormat format = new DecimalFormat("0.00");

            @Override
            protected void setValue(Object value) {
                if (value instanceof Number number) {
                    setText(format.format(number.doubleValue()));
                    return;
                }
                super.setValue(value);
            }
        };
        methodTable.getColumnModel().getColumn(2).setCellRenderer(decimalRenderer);
        methodTable.getColumnModel().getColumn(3).setCellRenderer(decimalRenderer);

        logTextArea = new NoWrapTextPane();
        logTextArea.setEditable(false);
        logTextArea.setOpaque(true);
        // Prevent caret updates from forcing viewport jumps while logs refresh every second.
        if (logTextArea.getCaret() instanceof DefaultCaret caret) {
            caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        }

        logScrollPane = new JScrollPane(logTextArea);
        logScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        syncLogBackground();

        logSearchField = new SearchTextField();
        logPrevMatchButton = new JButton(AllIcons.Actions.FindBackward);
        logNextMatchButton = new JButton(AllIcons.Actions.FindForward);
        clearLogButton = new JButton();
        viewLogFilePathButton = new JButton();

        serviceSection = buildServiceSection();
        methodCounterSection = buildMethodCounterSection();
        logSection = buildLogSection();

        topSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, serviceSection, methodCounterSection);
        topSplitPane.setResizeWeight(0.2d);
        topSplitPane.setContinuousLayout(true);

        bottomSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topSplitPane, logSection);
        bottomSplitPane.setResizeWeight(0.55d);
        bottomSplitPane.setContinuousLayout(true);

        rootPanel.add(bottomSplitPane, BorderLayout.CENTER);

        installDividerPersistence();
        installLogSearchHandlers();

        refreshTexts(currentLanguage);
        refreshTimer = new Timer(1000, e -> refreshAll());
        refreshTimer.start();

        logOperation("tool-window", "Tool window opened");
        refreshAll();
    }

    public JPanel getRootPanel() {
        return rootPanel;
    }

    private JPanel buildServiceSection() {
        JPanel section = new JPanel(new BorderLayout(0, 2));
        section.setBorder(BorderFactory.createTitledBorder(""));

        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        statusRow.add(serviceRunningLabel);
        statusRow.add(statusValueLabel);
        statusRow.add(startServiceButton);
        statusRow.add(stopServiceButton);

        JPanel addressRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        addressRow.add(serviceAddressLabel);
        addressRow.add(addressValueLabel);
        addressRow.add(copyAddressButton);

        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.add(statusRow);
        rows.add(addressRow);

        startServiceButton.addActionListener(e -> onStartService());
        stopServiceButton.addActionListener(e -> onStopService());
        copyAddressButton.addActionListener(e -> copyServiceAddress());

        section.add(rows, BorderLayout.CENTER);
        return section;
    }

    private JPanel buildMethodCounterSection() {
        JPanel section = new JPanel(new BorderLayout(0, 3));
        section.setBorder(BorderFactory.createTitledBorder(""));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        top.add(clearCounterButton);

        clearCounterButton.addActionListener(e -> {
            McpMethodMetricsService.getInstance().clear();
            logOperation("metrics", "Method counters cleared");
            refreshMethodCounters();
        });

        JScrollPane tableScroll = new JScrollPane(methodTable);
        tableScroll.setPreferredSize(new Dimension(10, 170));
        tableScroll.setMinimumSize(new Dimension(10, 140));

        section.add(top, BorderLayout.NORTH);
        section.add(tableScroll, BorderLayout.CENTER);
        return section;
    }

    private JPanel buildLogSection() {
        JPanel section = new JPanel(new BorderLayout(0, 3));
        section.setBorder(BorderFactory.createTitledBorder(""));

        logPrevMatchButton.setText(null);
        logNextMatchButton.setText(null);
        logPrevMatchButton.setFocusable(false);
        logNextMatchButton.setFocusable(false);

        logPrevMatchButton.addActionListener(e -> jumpToLogMatch(false));
        logNextMatchButton.addActionListener(e -> jumpToLogMatch(true));

        clearLogButton.addActionListener(e -> {
            McpRuntimeLogService.getInstance().clear();
            logOperation("tool-window", "Logs cleared");
            refreshLogs();
        });
        viewLogFilePathButton.addActionListener(e -> {
            String path = McpRuntimeLogService.getInstance().getLogFilePath();
            Messages.showInfoMessage(
                    project,
                    DatabaseMcpMessages.message(currentLanguage, "toolwindow.logFilePath", path),
                    DatabaseMcpMessages.message(currentLanguage, "toolwindow.logFileTitle")
            );
        });

        JPanel toolbar = new JPanel(new BorderLayout(4, 0));
        toolbar.add(logSearchField, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.add(logPrevMatchButton);
        actions.add(logNextMatchButton);
        actions.add(clearLogButton);
        actions.add(viewLogFilePathButton);
        toolbar.add(actions, BorderLayout.EAST);

        section.add(toolbar, BorderLayout.NORTH);
        section.add(logScrollPane, BorderLayout.CENTER);
        return section;
    }

    private void onStartService() {
        McpServerManager.StartResult result = McpServerManager.getInstance().startWithValidation("tool-window-start");
        if (!result.isSuccess()) {
            Messages.showErrorDialog(project, result.getMessage(), DatabaseMcpMessages.message(currentLanguage, "settings.error.title"));
        }
        logOperation("tool-window", "Start service clicked: " + result.getMessage());
        refreshAll();
    }

    private void onStopService() {
        McpServerManager.getInstance().stop("tool-window-stop");
        logOperation("tool-window", "Stop service clicked");
        refreshAll();
    }

    private void refreshAll() {
        McpSettingsState.UiLanguage language = McpSettingsState.getInstance().getUiLanguageEffective();
        if (language != currentLanguage) {
            refreshTexts(language);
        }
        syncLogBackground();
        refreshServiceStatus();
        refreshMethodCounters();
        refreshLogs();
    }

    private void refreshServiceStatus() {
        McpServerManager manager = McpServerManager.getInstance();
        boolean running = manager.isRunning();

        statusValueLabel.setText(running
                ? DatabaseMcpMessages.message(currentLanguage, "toolwindow.statusRunning")
                : DatabaseMcpMessages.message(currentLanguage, "toolwindow.statusStopped"));
        statusValueLabel.setForeground(running ? STATUS_RUNNING : STATUS_STOPPED);

        addressValueLabel.setText(running ? manager.getServiceUrl() : "");
        copyAddressButton.setEnabled(running);
        startServiceButton.setEnabled(!running);
        stopServiceButton.setEnabled(running);
    }

    private void refreshMethodCounters() {
        Map<String, McpMethodMetricsService.MethodMetric> snapshot = McpMethodMetricsService.getInstance().snapshot();
        List<Object[]> rows = new ArrayList<>();
        for (Map.Entry<String, McpMethodMetricsService.MethodMetric> entry : snapshot.entrySet()) {
            McpMethodMetricsService.MethodMetric metric = entry.getValue();
            rows.add(new Object[]{
                    entry.getKey(),
                    (long) metric.count(),
                    metric.averageMillis(),
                    metric.totalMillis()
            });
        }

        rows.sort(Comparator.comparingLong((Object[] row) -> (Long) row[1]).reversed());
        methodTableModel.setRowCount(0);
        for (Object[] row : rows) {
            methodTableModel.addRow(row);
        }

        methodTableSorter.setSortKeys(List.of(new RowSorter.SortKey(1, SortOrder.DESCENDING)));
        methodTableSorter.sort();
    }

    private void refreshLogs() {
        Point viewPosition = logScrollPane.getViewport().getViewPosition();
        List<String> lines = McpRuntimeLogService.getInstance().list();
        String content = lines.isEmpty()
                ? DatabaseMcpMessages.message(currentLanguage, "toolwindow.noLogs")
                : String.join(System.lineSeparator(), lines);

        if (content.equals(lastRenderedLogContent)) {
            return;
        }

        lastRenderedLogContent = content;

        if (lines.isEmpty()) {
            setLogContent(List.of(DatabaseMcpMessages.message(currentLanguage, "toolwindow.noLogs")));
            restoreLogViewportState(viewPosition);
            updateLogSearchMatches(true);
            return;
        }

        setLogContent(lines);
        restoreLogViewportState(viewPosition);
        updateLogSearchMatches(false);
    }

    private void installDividerPersistence() {
        McpSettingsState settings = McpSettingsState.getInstance();
        SwingUtilities.invokeLater(() -> {
            topSplitPane.setDividerLocation(settings.getToolWindowTopDividerLocation());
            bottomSplitPane.setDividerLocation(settings.getToolWindowBottomDividerLocation());
        });

        topSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY,
                e -> settings.setToolWindowTopDividerLocation(topSplitPane.getDividerLocation()));
        bottomSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY,
                e -> settings.setToolWindowBottomDividerLocation(bottomSplitPane.getDividerLocation()));
    }

    private void installLogSearchHandlers() {
        logSearchField.getTextEditor().getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateLogSearchMatches(true);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateLogSearchMatches(true);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateLogSearchMatches(true);
            }
        });
    }

    private void updateLogSearchMatches(boolean resetSelection) {
        logMatchRanges.clear();
        clearLogSearchHighlights();
        String query = currentLogSearchQuery();

        if (query.isBlank()) {
            selectedLogMatchIndex = -1;
            setLogSearchButtonsEnabled(false);
            return;
        }

        String content = logTextArea.getText();
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        String lowerContent = content.toLowerCase(Locale.ROOT);
        int from = 0;
        while (from < lowerContent.length()) {
            int hit = lowerContent.indexOf(lowerQuery, from);
            if (hit < 0) {
                break;
            }
            logMatchRanges.add(new int[]{hit, hit + lowerQuery.length()});
            from = hit + lowerQuery.length();
        }

        if (logMatchRanges.isEmpty()) {
            selectedLogMatchIndex = -1;
            setLogSearchButtonsEnabled(false);
            return;
        }

        highlightAllMatches();

        setLogSearchButtonsEnabled(true);

        if (resetSelection || selectedLogMatchIndex < 0 || selectedLogMatchIndex >= logMatchRanges.size()) {
            selectedLogMatchIndex = 0;
        }

        // Only auto-jump when user is actively searching; periodic refresh should not move viewport.
        if (resetSelection) {
            selectCurrentLogMatch();
        }
    }

    private void jumpToLogMatch(boolean next) {
        if (logMatchRanges.isEmpty()) {
            return;
        }

        if (selectedLogMatchIndex < 0) {
            selectedLogMatchIndex = 0;
        } else {
            int delta = next ? 1 : -1;
            selectedLogMatchIndex = (selectedLogMatchIndex + delta + logMatchRanges.size()) % logMatchRanges.size();
        }

        selectCurrentLogMatch();
    }

    private void selectCurrentLogMatch() {
        if (selectedLogMatchIndex < 0 || selectedLogMatchIndex >= logMatchRanges.size()) {
            return;
        }
        int[] range = logMatchRanges.get(selectedLogMatchIndex);
        int start = range[0];
        int end = range[1];
        logTextArea.select(start, end);
        logTextArea.setCaretPosition(start);
    }

    private void setLogSearchButtonsEnabled(boolean enabled) {
        logPrevMatchButton.setEnabled(enabled);
        logNextMatchButton.setEnabled(enabled);
    }

    private String currentLogSearchQuery() {
        String text = logSearchField.getText();
        return text == null ? "" : text.trim();
    }

    private void highlightAllMatches() {
        Highlighter highlighter = logTextArea.getHighlighter();
        Highlighter.HighlightPainter painter = new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 245, 157));
        for (int[] range : logMatchRanges) {
            try {
                highlighter.addHighlight(range[0], range[1], painter);
            } catch (Exception ignored) {
                // Ignore out-of-date offsets during rapid refresh.
            }
        }
    }

    private void clearLogSearchHighlights() {
        logTextArea.getHighlighter().removeAllHighlights();
    }

    private void setLogContent(List<String> lines) {
        StyledDocument document = logTextArea.getStyledDocument();
        try {
            document.remove(0, document.getLength());
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                document.insertString(document.getLength(), line, attributesForLine(line));
                if (i < lines.size() - 1) {
                    document.insertString(document.getLength(), System.lineSeparator(), null);
                }
            }
        } catch (BadLocationException ex) {
            logTextArea.setText(String.join(System.lineSeparator(), lines));
        }
    }

    private void restoreLogViewportState(Point previousViewPosition) {
        SwingUtilities.invokeLater(() -> {

            JViewport viewport = logScrollPane.getViewport();
            Point safePoint = previousViewPosition == null ? new Point(0, 0) : new Point(previousViewPosition);
            int maxX = Math.max(0, logTextArea.getWidth() - viewport.getWidth());
            int maxY = Math.max(0, logTextArea.getHeight() - viewport.getHeight());
            safePoint.x = Math.max(0, Math.min(safePoint.x, maxX));
            safePoint.y = Math.max(0, Math.min(safePoint.y, maxY));
            viewport.setViewPosition(safePoint);
        });
    }

    private SimpleAttributeSet attributesForLine(String line) {
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        if (line != null && line.startsWith("[ERROR]")) {
            StyleConstants.setForeground(attrs, LOG_ERROR_FG);
        } else if (line != null && line.startsWith("[WARN]")) {
            StyleConstants.setForeground(attrs, LOG_WARN_FG);
        } else if (line != null && line.startsWith("[INFO]")) {
            StyleConstants.setForeground(attrs, LOG_INFO_FG);
        }
        return attrs;
    }

    private void copyServiceAddress() {
        if (!McpServerManager.getInstance().isRunning()) {
            return;
        }
        String value = McpServerManager.getInstance().getServiceUrl();
        CopyPasteManager.getInstance().setContents(new StringSelection(value));
        logOperation("tool-window", "Copied service address");
    }

    private void refreshTexts(McpSettingsState.UiLanguage language) {
        currentLanguage = language;

        setSectionTitle(serviceSection, DatabaseMcpMessages.message(language, "toolwindow.serviceStatus"));
        setSectionTitle(methodCounterSection, DatabaseMcpMessages.message(language, "toolwindow.methodCounter"));
        setSectionTitle(logSection, DatabaseMcpMessages.message(language, "toolwindow.logs"));

        serviceRunningLabel.setText(DatabaseMcpMessages.message(language, "toolwindow.serviceRunning"));
        serviceAddressLabel.setText(DatabaseMcpMessages.message(language, "toolwindow.serviceAddress"));
        copyAddressButton.setText(DatabaseMcpMessages.message(language, "toolwindow.copyAddress"));
        startServiceButton.setText(DatabaseMcpMessages.message(language, "toolwindow.startService"));
        stopServiceButton.setText(DatabaseMcpMessages.message(language, "toolwindow.stopService"));

        clearCounterButton.setText(DatabaseMcpMessages.message(language, "toolwindow.resetCounter"));
        methodTableModel.setColumnIdentifiers(new Object[]{
                DatabaseMcpMessages.message(language, "toolwindow.methodColumn"),
                DatabaseMcpMessages.message(language, "toolwindow.countColumn"),
                DatabaseMcpMessages.message(language, "toolwindow.avgCostColumn"),
                DatabaseMcpMessages.message(language, "toolwindow.totalCostColumn")
        });

        logSearchField.setToolTipText(DatabaseMcpMessages.message(language, "toolwindow.searchPlaceholder"));
        logPrevMatchButton.setToolTipText(DatabaseMcpMessages.message(language, "toolwindow.searchPrevious"));
        logNextMatchButton.setToolTipText(DatabaseMcpMessages.message(language, "toolwindow.searchNext"));
        clearLogButton.setText(DatabaseMcpMessages.message(language, "toolwindow.clearLogs"));
        viewLogFilePathButton.setText(DatabaseMcpMessages.message(language, "toolwindow.viewLogFilePath"));

        rootPanel.revalidate();
        rootPanel.repaint();
    }

    private void setSectionTitle(JPanel panel, String title) {
        if (panel.getBorder() instanceof TitledBorder border) {
            border.setTitle(title);
            Font titleFont = UIManager.getFont("TitledBorder.font");
            if (titleFont != null) {
                border.setTitleFont(titleFont.deriveFont(Font.BOLD));
            }
        }
    }

    private void logOperation(String source, String message) {
        McpRuntimeLogService.getInstance().info(source, message);
    }

    @Override
    public void dispose() {
        refreshTimer.stop();
        logOperation("tool-window", "Tool window closed");
    }

    private void syncLogBackground() {
        Color bg = UIManager.getColor("TextArea.background");
        if (bg == null) {
            bg = UIManager.getColor("Panel.background");
        }
        if (bg == null) {
            bg = logTextArea.getBackground();
        }
        logTextArea.setBackground(bg);
        logScrollPane.getViewport().setBackground(bg);
        logScrollPane.setBackground(bg);
    }

    /**
     * JTextPane wraps long lines by default; use a custom editor kit to keep log rows single-line.
     */
    private static final class NoWrapTextPane extends JTextPane {
        private NoWrapTextPane() {
            setEditorKit(new NoWrapEditorKit());
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return false;
        }
    }

    private static final class NoWrapEditorKit extends StyledEditorKit {
        private final ViewFactory factory = new NoWrapViewFactory();

        @Override
        public ViewFactory getViewFactory() {
            return factory;
        }
    }

    private static final class NoWrapViewFactory implements ViewFactory {
        @Override
        public View create(Element elem) {
            String kind = elem.getName();
            if (kind != null) {
                if (AbstractDocument.ContentElementName.equals(kind)) {
                    return new LabelView(elem);
                }
                if (AbstractDocument.ParagraphElementName.equals(kind)) {
                    return new NoWrapParagraphView(elem);
                }
                if (AbstractDocument.SectionElementName.equals(kind)) {
                    return new BoxView(elem, View.Y_AXIS);
                }
                if (StyleConstants.ComponentElementName.equals(kind)) {
                    return new ComponentView(elem);
                }
                if (StyleConstants.IconElementName.equals(kind)) {
                    return new IconView(elem);
                }
            }
            return new LabelView(elem);
        }
    }

    private static final class NoWrapParagraphView extends ParagraphView {
        private NoWrapParagraphView(Element elem) {
            super(elem);
        }

        @Override
        public void layout(int width, int height) {
            super.layout(Integer.MAX_VALUE, height);
        }

        @Override
        public float getMinimumSpan(int axis) {
            if (axis == View.X_AXIS) {
                return super.getPreferredSpan(axis);
            }
            return super.getMinimumSpan(axis);
        }
    }

}

