package com.skrstop.ide.databasemcp.toolwindow;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.skrstop.ide.databasemcp.service.McpMethodMetricsService;
import com.skrstop.ide.databasemcp.service.McpRuntimeLogService;
import com.skrstop.ide.databasemcp.service.McpServerManager;
import com.skrstop.ide.databasemcp.settings.CustomToolsConfigurable;
import com.skrstop.ide.databasemcp.settings.DatabaseMcpMessages;
import com.skrstop.ide.databasemcp.settings.McpSettingsState;

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
    private final JButton addCustomToolButton;

    private final JButton clearCounterButton;
    private final JBTable methodTable;
    private final DefaultTableModel methodTableModel;
    private final TableRowSorter<DefaultTableModel> methodTableSorter;

    private final NoWrapTextPane logTextArea;
    private final JBScrollPane logScrollPane;
    private final SearchTextField logSearchField;
    private final JButton logPrevMatchButton;
    private final JButton logNextMatchButton;
    private final JButton clearLogButton;
    private final JButton viewLogFilePathButton;
    private final Timer refreshTimer;
    private final List<int[]> logMatchRanges = new ArrayList<>();
    private int selectedLogMatchIndex = -1;

    // 缓存上次服务状态，避免每秒无意义的 UI 更新
    private boolean lastRunningState = false;
    private String lastServiceUrl = "";

    // 缓存上次方法计数器快照，避免无数据变化时重建表格
    private int lastMethodSnapshotHash = 0;

    // 缓存日志区背景色，避免每秒重读 UIManager
    private Color lastLogBackground = null;

    // 缓存上次已渲染的日志内容版本号，版本未变时完全跳过拷贝与字符串拼接
    private long lastRenderedLogVersion = -1L;

    // 日志染色用的 AttributeSet 静态缓存，避免每行每次刷新重复创建
    private static final SimpleAttributeSet ATTR_INFO = buildAttrs(LOG_INFO_FG);
    private static final SimpleAttributeSet ATTR_WARN = buildAttrs(LOG_WARN_FG);
    private static final SimpleAttributeSet ATTR_ERROR = buildAttrs(LOG_ERROR_FG);
    private static final SimpleAttributeSet ATTR_DEFAULT = new SimpleAttributeSet();

    private static SimpleAttributeSet buildAttrs(Color fg) {
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setForeground(attrs, fg);
        return attrs;
    }

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
        addCustomToolButton = new JButton();
        // 仅在服务运行时显示
        addCustomToolButton.setVisible(false);

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

        methodTable = new JBTable(methodTableModel);
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

        logScrollPane = new JBScrollPane(logTextArea);
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

        // 首次刷新推迟到 invokeLater，让 ToolWindow 面板立即展示，避免首次打开卡顿
        SwingUtilities.invokeLater(() -> {
            logOperation("tool-window", "Tool window opened");
            // 面板初次打开时同步自定义 tool 到统计表，确保已配置的自定义 SQL 立即出现在方法计数列表中
            McpMethodMetricsService.getInstance().syncCustomTools();
            refreshAll();
        });
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
        statusRow.add(addCustomToolButton);

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
        addCustomToolButton.addActionListener(e -> openCustomToolsSettings());

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
            // 清空后重置哈希缓存，强制下次刷新重建表格
            lastMethodSnapshotHash = -1;
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
            // 清空后让日志版本缓存失效并重置行计数，强制下次 refreshLogs 重新渲染
            lastRenderedLogVersion = -1L;
            renderedLineCount = 0;
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
        // 重置缓存，确保状态立即刷新
        lastRunningState = !McpServerManager.getInstance().isRunning();
        refreshAll();
    }

    private void onStopService() {
        McpServerManager.getInstance().stop("tool-window-stop");
        logOperation("tool-window", "Stop service clicked");
        // 重置缓存，确保状态立即刷新
        lastRunningState = true;
        refreshAll();
    }

    private void refreshAll() {
        McpSettingsState.UiLanguage language = McpSettingsState.getInstance().getUiLanguageEffective();
        if (language != currentLanguage) {
            refreshTexts(language);
        }
        refreshServiceStatus();
        refreshMethodCounters();
        refreshLogs();
    }

    private void refreshServiceStatus() {
        McpServerManager manager = McpServerManager.getInstance();
        boolean running = manager.isRunning();
        String url = running ? manager.getServiceUrl() : "";

        // 状态未变化，跳过 UI 更新，避免无意义的标签重绘
        if (running == lastRunningState && url.equals(lastServiceUrl)) {
            return;
        }
        lastRunningState = running;
        lastServiceUrl = url;

        statusValueLabel.setText(running
                ? DatabaseMcpMessages.message(currentLanguage, "toolwindow.statusRunning")
                : DatabaseMcpMessages.message(currentLanguage, "toolwindow.statusStopped"));
        statusValueLabel.setForeground(running ? STATUS_RUNNING : STATUS_STOPPED);

        addressValueLabel.setText(url);
        copyAddressButton.setEnabled(running);
        startServiceButton.setEnabled(!running);
        stopServiceButton.setEnabled(running);
        // 仅在服务运行时显示「添加自定义工具」按钮
        addCustomToolButton.setVisible(running);
    }

    /**
     * 打开「自定义工具」设置页。
     * <p>使用 {@link ShowSettingsUtil#editConfigurable} 直接传入实例，
     * 避免 {@code showSettingsDialog(project, Class)} 在 Settings 树尚未完全初始化时
     * 找不到 applicationConfigurable 而显示 "Nothing to show" 的问题。</p>
     */
    private void openCustomToolsSettings() {
        ShowSettingsUtil.getInstance().editConfigurable(project, new CustomToolsConfigurable());
        logOperation("tool-window", "Open custom tools settings clicked");
    }

    private void refreshMethodCounters() {
        Map<String, McpMethodMetricsService.MethodMetric> snapshot = McpMethodMetricsService.getInstance().snapshot();

        // 计算快照哈希，只有数据变化时才重建表格，避免每秒触发大量 TableModelEvent 重绘
        int snapshotHash = snapshot.entrySet().stream()
                .mapToInt(e -> e.getKey().hashCode() * 31 + e.getValue().hashCode())
                .sum();
        if (snapshotHash == lastMethodSnapshotHash) {
            return;
        }
        lastMethodSnapshotHash = snapshotHash;

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

    // 当前 document 已渲染的日志行数（不含用于无日志提示的占位行）。
    // 用于与 LogSnapshot.keptOldLines 对比，计算需要从 document 开头裁剪的老行数。
    private int renderedLineCount = 0;

    private void refreshLogs() {
        // 先用版本号短路，没有新日志时完全跳过任何工作
        McpRuntimeLogService logService = McpRuntimeLogService.getInstance();
        long currentVersion = logService.version();
        if (currentVersion == lastRenderedLogVersion) {
            return;
        }

        Point viewPosition = logScrollPane.getViewport().getViewPosition();
        McpRuntimeLogService.LogSnapshot snapshot = logService.listSince(lastRenderedLogVersion);
        lastRenderedLogVersion = snapshot.version();

        // 场景一：全量重建（首次打开 / clear 后首次 / 已超出环形缓冲窗口）
        if (snapshot.fullReset()) {
            List<String> lines = snapshot.appended();
            if (lines.isEmpty()) {
                setLogContent(List.of(DatabaseMcpMessages.message(currentLanguage, "toolwindow.noLogs")));
                renderedLineCount = 0;
            } else {
                setLogContent(lines);
                renderedLineCount = lines.size();
            }
            restoreLogViewportState(viewPosition);
            updateLogSearchMatches(true);
            return;
        }

        // 场景二：无新增（仅版本号对齐到最新），什么也不做
        if (snapshot.appended().isEmpty()) {
            return;
        }

        // 场景三：增量追加。先裁剪掉被环形缓冲淘汰的老行，再在末尾 append 新行。
        int linesToRemove = Math.max(0, renderedLineCount - snapshot.keptOldLines());
        if (linesToRemove > 0) {
            removeLeadingLines(logTextArea.getStyledDocument(), linesToRemove);
        }
        appendLogLines(logTextArea.getStyledDocument(), snapshot.appended());
        renderedLineCount = snapshot.keptOldLines() + snapshot.appended().size();

        restoreLogViewportState(viewPosition);
        // 增量渲染后重新计算搜索高亮（document 偏移已变化）
        updateLogSearchMatches(false);
    }

    /**
     * 从 StyledDocument 开头删掉指定行数（按 '\n' 边界计）。
     * 当环形缓冲淘汰老行时用于同步裁剪 UI document。
     */
    private static void removeLeadingLines(StyledDocument document, int lineCount) {
        if (lineCount <= 0 || document.getLength() == 0) {
            return;
        }
        try {
            String text = document.getText(0, document.getLength());
            int idx = 0;
            int removed = 0;
            while (removed < lineCount) {
                int nl = text.indexOf('\n', idx);
                if (nl < 0) {
                    // 剩余不足 lineCount 行，全部删除
                    idx = document.getLength();
                    break;
                }
                idx = nl + 1;
                removed++;
            }
            if (idx > 0) {
                document.remove(0, Math.min(idx, document.getLength()));
            }
        } catch (BadLocationException ignored) {
            // document 状态不一致时忽略；下一轮版本号驱动会再同步
        }
    }

    /**
     * 在 StyledDocument 末尾追加若干日志行（含换行分隔），按级别染色。
     */
    private void appendLogLines(StyledDocument document, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        try {
            int length = document.getLength();
            boolean needsLeadingNewline = length > 0;
            StringBuilder sb = new StringBuilder();
            if (needsLeadingNewline) {
                sb.append(System.lineSeparator());
            }
            // 先一次性插入纯文本，再分段染色，避免每行一次 insertString 带来的大量 DocumentEvent
            int[] lineStartOffsets = new int[lines.size()];
            int cursor = length + (needsLeadingNewline ? System.lineSeparator().length() : 0);
            for (int i = 0; i < lines.size(); i++) {
                lineStartOffsets[i] = cursor;
                String line = lines.get(i);
                sb.append(line);
                cursor += line.length();
                if (i < lines.size() - 1) {
                    sb.append(System.lineSeparator());
                    cursor += System.lineSeparator().length();
                }
            }
            document.insertString(length, sb.toString(), null);
            // 逐行染色
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                document.setCharacterAttributes(lineStartOffsets[i], line.length(), attributesForLine(line), true);
            }
        } catch (BadLocationException ignored) {
            // 发生不一致时交给下一轮 fullReset 恢复
            lastRenderedLogVersion = -1L;
        }
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

    /**
     * 为日志行选择染色属性。
     * 日志实际格式为 `yyyy-MM-dd HH:mm:ss.SSS [LEVEL] message`，因此使用 contains 匹配而非 startsWith。
     * 返回复用的静态 AttributeSet，避免每行创建临时对象。
     */
    private SimpleAttributeSet attributesForLine(String line) {
        if (line == null) {
            return ATTR_DEFAULT;
        }
        if (line.contains(" [ERROR] ")) {
            return ATTR_ERROR;
        }
        if (line.contains(" [WARN] ")) {
            return ATTR_WARN;
        }
        if (line.contains(" [INFO] ")) {
            return ATTR_INFO;
        }
        return ATTR_DEFAULT;
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
        // 语言切换后，重置服务状态缓存，确保下次刷新重绘带有新语言的标签
        lastRunningState = !McpServerManager.getInstance().isRunning();

        setSectionTitle(serviceSection, DatabaseMcpMessages.message(language, "toolwindow.serviceStatus"));
        setSectionTitle(methodCounterSection, DatabaseMcpMessages.message(language, "toolwindow.methodCounter"));
        setSectionTitle(logSection, DatabaseMcpMessages.message(language, "toolwindow.logs"));

        serviceRunningLabel.setText(DatabaseMcpMessages.message(language, "toolwindow.serviceRunning"));
        serviceAddressLabel.setText(DatabaseMcpMessages.message(language, "toolwindow.serviceAddress"));
        copyAddressButton.setText(DatabaseMcpMessages.message(language, "toolwindow.copyAddress"));
        startServiceButton.setText(DatabaseMcpMessages.message(language, "toolwindow.startService"));
        stopServiceButton.setText(DatabaseMcpMessages.message(language, "toolwindow.stopService"));
        addCustomToolButton.setText(DatabaseMcpMessages.message(language, "toolwindow.addCustomTool"));

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
        // 背景色未变化时跳过，避免重复触发 repaint
        if (bg.equals(lastLogBackground)) {
            return;
        }
        lastLogBackground = bg;
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

