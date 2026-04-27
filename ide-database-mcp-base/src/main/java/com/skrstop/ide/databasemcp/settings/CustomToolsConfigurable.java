package com.skrstop.ide.databasemcp.settings;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.AnActionButton;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.skrstop.ide.databasemcp.db.IdeDatabaseFacade;
import com.skrstop.ide.databasemcp.mcp.McpToolDefinitions;
import com.skrstop.ide.databasemcp.service.McpMethodMetricsService;
import com.skrstop.ide.databasemcp.service.McpRuntimeLogService;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自定义 MCP Tool 配置页：左侧列表 + 右侧详情，支持新增 / 删除 / 重命名 / 修改 SQL、参数与默认行数。
 *
 * <p>每条自定义 tool 在保存后会通过 {@link com.skrstop.ide.databasemcp.mcp.McpToolDefinitions#getAllTools()}
 * 注入到 {@code tools/list}，由 {@link com.skrstop.ide.databasemcp.mcp.McpProtocolRouter} 使用绑定数据源
 * 与预置 SQL 执行查询；SQL 中 {@code ${name}} 占位符会运行时安全绑定到 PreparedStatement。</p>
 *
 * @author skrstop AI
 */
public final class CustomToolsConfigurable implements Configurable {

    /**
     * 描述字段最大字符数。
     */
    private static final int MAX_DESCRIPTION_LENGTH = 300;
    /**
     * 工具名称合法格式：字母/数字/下划线，必须以字母开头。
     */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");
    /**
     * 工具名称强制前缀，UI 中以不可编辑 Label 形式展示。
     */
    private static final String NAME_PREFIX = "custom_";
    /**
     * SQL 中 {@code ${var}} 占位符匹配规则；与 router 中保持一致。
     */
    private static final Pattern SQL_VAR_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    /**
     * 参数表格类型下拉的可选值。
     */
    private static final String[] PARAM_TYPES = {
            CustomToolParameter.TYPE_STRING,
            CustomToolParameter.TYPE_INTEGER,
            CustomToolParameter.TYPE_NUMBER,
            CustomToolParameter.TYPE_BOOLEAN
    };
    /**
     * SQL 测试时未配置 maxRows 时使用的默认行数，避免误触大表造成 IDE 内存压力。
     */
    private static final int TEST_PREVIEW_DEFAULT_ROWS = 100;

    private JPanel rootPanel;
    private JBList<CustomToolDefinition> toolList;
    private DefaultListModel<CustomToolDefinition> listModel;

    private JBTextField nameField;
    private JTextArea descriptionArea;
    private JLabel descriptionCounterLabel;
    private ComboBox<String> dataSourceCombo;
    private JButton refreshDataSourceButton;
    /**
     * SQL 类型下拉：Query（SELECT）或 DML（INSERT/UPDATE/DELETE）。
     */
    private ComboBox<String> sqlModeCombo;
    /**
     * maxRows 所在行容器，DML 模式下隐藏。
     */
    private JLabel maxRowsLabel;
    private JTextArea sqlArea;
    private JBTextField maxRowsField;

    private JButton testButton;
    private JBLabel testStatusLabel;
    private DefaultTableModel previewModel;
    private JBTable previewTable;

    private ParameterTableModel paramTableModel;
    private JBTable paramTable;
    private JBLabel paramHintLabel;
    /**
     * 参数表滚动容器，高度随参数行数动态调整。
     */
    private JScrollPane paramScroll;

    private JPanel detailPanel;
    private JLabel emptyHintLabel;
    private CardLayout detailCardLayout;
    private JPanel detailContainer;
    /**
     * 左右面板分割条，提升为实例字段以便在 {@link #disposeUIResources()} 中兜底保存最终比例。
     */
    private OnePixelSplitter splitter;

    private List<CustomToolDefinition> editingList = new ArrayList<>();
    private CustomToolDefinition currentEditing;
    private boolean updatingFromModel;
    /**
     * 缓存当前已加载的数据源名称列表，用于校验用户输入的数据源是否真实存在。
     * 仅在 {@link #applyDataSourceOptions} 成功加载后更新，初始为空表示尚未加载。
     */
    private Set<String> knownDataSourceNames = new HashSet<>();
    /**
     * 标记当前条目的 SQL 类型是否已被用户手动修改过。
     * 为 {@code true} 时，SQL 内容变化不再自动推断并覆盖下拉值。
     * 每次切换到新条目时重置为 {@code false}。
     */
    private boolean sqlModeManuallyChanged;

    @Override
    public @Nls String getDisplayName() {
        return DatabaseMcpMessages.message("settings.customTools.displayName");
    }

    @Override
    public @Nullable JComponent createComponent() {
        editingList = McpSettingsState.getInstance().getCustomTools(currentScope());

        // 左侧列表
        listModel = new DefaultListModel<>();
        for (CustomToolDefinition def : editingList) {
            listModel.addElement(def);
        }
        toolList = new JBList<>(listModel);
        toolList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        toolList.setCellRenderer(new ToolListRenderer());
        toolList.addListSelectionListener(buildSelectionListener());
        // 鼠标点击落在 checkbox 区域时切换 enabled，并刷新该行 + 联动 detail
        toolList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int index = toolList.locationToIndex(e.getPoint());
                if (index < 0) {
                    return;
                }
                Rectangle bounds = toolList.getCellBounds(index, index);
                if (bounds == null || !bounds.contains(e.getPoint())) {
                    return;
                }
                // checkbox 区域为左侧 ToolListRenderer.CHECKBOX_WIDTH 像素
                if (e.getX() - bounds.x <= ToolListRenderer.CHECKBOX_WIDTH) {
                    CustomToolDefinition def = listModel.get(index);
                    def.enabled = !def.enabled;
                    listModel.set(index, def);
                }
            }
        });

        JPanel listPanel = ToolbarDecorator.createDecorator(toolList)
                .setAddAction(b -> addNewTool())
                .setRemoveAction(b -> removeSelectedTool())
                .addExtraAction(new AnActionButton(
                        DatabaseMcpMessages.message("settings.customTools.action.copy"),
                        DatabaseMcpMessages.message("settings.customTools.action.copy.description"),
                        AllIcons.Actions.Copy) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        copySelectedTool();
                    }

                    @Override
                    public void updateButton(@NotNull AnActionEvent e) {
                        e.getPresentation().setEnabled(toolList.getSelectedIndex() >= 0);
                    }
                })
                .disableUpDownActions()
                .createPanel();
        // 默认高度缩短一半（原 360 → 180），用户拖动 splitter 后宽度会持久化
        listPanel.setPreferredSize(new Dimension(220, 180));

        // 右侧详情
        detailPanel = buildDetailPanel();
        emptyHintLabel = new JBLabel(DatabaseMcpMessages.message("settings.customTools.emptyHint"));
        emptyHintLabel.setHorizontalAlignment(SwingConstants.CENTER);
        emptyHintLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

        detailCardLayout = new CardLayout();
        detailContainer = new JPanel(detailCardLayout);
        // 详情区使用纵向滚动；横向方向上让组件自适应宽度，避免内部固定 PreferredSize 撑出横滚
        JBScrollPane detailScroll = new JBScrollPane(detailPanel,
                JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        detailScroll.setBorder(JBUI.Borders.empty());
        detailContainer.add(detailScroll, "DETAIL");
        detailContainer.add(emptyHintLabel, "EMPTY");
        detailCardLayout.show(detailContainer, "EMPTY");

        // Splitter 初始比例从持久化状态读取，用户拖动后写回
        float initialProportion = McpSettingsState.getInstance().getCustomToolsSplitterProportion();
        splitter = new OnePixelSplitter(false, initialProportion);
        splitter.setFirstComponent(listPanel);
        splitter.setSecondComponent(detailContainer);
        // 实时保存：用户每次拖动分割条时同步写入持久化状态
        splitter.addPropertyChangeListener("proportion", evt -> {
            Object nv = evt.getNewValue();
            if (nv instanceof Float f) {
                McpSettingsState.getInstance().setCustomToolsSplitterProportion(f);
            } else if (nv instanceof Number n) {
                McpSettingsState.getInstance().setCustomToolsSplitterProportion(n.floatValue());
            }
        });
        // 组件布局完成后再次应用持久化比例，确保初始显示时宽度正确还原
        // （构造函数传入的比例可能因面板尚未可见而未能正确应用）
        SwingUtilities.invokeLater(() -> splitter.setProportion(initialProportion));

        rootPanel = new JPanel(new BorderLayout());
        rootPanel.add(splitter, BorderLayout.CENTER);

        if (!listModel.isEmpty()) {
            toolList.setSelectedIndex(0);
        }
        // 数据源列表延迟加载，避免设置对话框初始化阶段阻塞 EDT。
        SwingUtilities.invokeLater(this::loadDataSourceOptions);
        return rootPanel;
    }

    private JPanel buildDetailPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(8));

        JLabel nameLabel = new JBLabel(DatabaseMcpMessages.message("settings.customTools.field.name"));
        JLabel descriptionLabel = new JBLabel(DatabaseMcpMessages.message("settings.customTools.field.description"));
        // 数据源为必填项，标签末尾加红色星号提示
        JLabel dataSourceLabel = new JBLabel("<html>"
                + escapeHtml(DatabaseMcpMessages.message("settings.customTools.field.dataSource"))
                + " <span style='color:red'>*</span></html>");
        JLabel sqlModeLabel = new JBLabel(DatabaseMcpMessages.message("settings.customTools.field.sqlMode"));
        JLabel sqlLabel = new JBLabel(DatabaseMcpMessages.message("settings.customTools.field.sql"));
        maxRowsLabel = new JBLabel(DatabaseMcpMessages.message("settings.customTools.field.maxRows"));
        JLabel paramLabel = new JBLabel(DatabaseMcpMessages.message("settings.customTools.field.parameters"));

        nameField = new JBTextField();
        ((AbstractDocument) nameField.getDocument()).setDocumentFilter(new NameCharsDocumentFilter());

        // name 行：固定前缀 Label + 输入框
        JBLabel prefixLabel = new JBLabel(DatabaseMcpMessages.message("settings.customTools.namePrefix"));
        prefixLabel.setBorder(JBUI.Borders.emptyRight(2));
        prefixLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        JPanel namePanel = new JPanel(new BorderLayout());
        namePanel.add(prefixLabel, BorderLayout.WEST);
        namePanel.add(nameField, BorderLayout.CENTER);

        descriptionArea = new JTextArea(2, 30);
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        ((AbstractDocument) descriptionArea.getDocument()).setDocumentFilter(new MaxLengthDocumentFilter(MAX_DESCRIPTION_LENGTH));
        descriptionCounterLabel = new JBLabel("0/" + MAX_DESCRIPTION_LENGTH);
        descriptionCounterLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

        dataSourceCombo = new ComboBox<>();
        dataSourceCombo.setEditable(false);
        refreshDataSourceButton = new JButton(DatabaseMcpMessages.message("settings.customTools.refreshDataSource"));
        refreshDataSourceButton.addActionListener(e -> loadDataSourceOptions());

        // SQL 类型下拉：QUERY / DML
        sqlModeCombo = new ComboBox<>(new String[]{
                DatabaseMcpMessages.message("settings.customTools.sqlMode.query"),
                DatabaseMcpMessages.message("settings.customTools.sqlMode.dml")
        });
        // 与 dataSourceCombo 保持一致的边框样式（部分 LAF 下非 editable ComboBox 默认无边框）
        sqlModeCombo.setBorder(defaultComboBorder());

        sqlArea = new JTextArea(8, 30);
        sqlArea.setLineWrap(true);
        sqlArea.setWrapStyleWord(false);

        maxRowsField = new JBTextField();
        maxRowsField.setColumns(8);
        ((AbstractDocument) maxRowsField.getDocument()).setDocumentFilter(new DigitsOnlyDocumentFilter());

        // 测试按钮 + 状态 + 预览表格
        testButton = new JButton(DatabaseMcpMessages.message("settings.customTools.test.button"));
        testButton.addActionListener(e -> runSqlTest());
        testStatusLabel = new JBLabel(" ");
        testStatusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        previewModel = new DefaultTableModel();
        previewTable = new JBTable(previewModel);
        // 使用 AUTO_RESIZE_LAST_COLUMN：列宽分配自适应容器，避免固定列宽撑出横向滚动条；
        // 预览不需要精确列宽，列内若过长由 ScrollPane 内部横滚处理。
        previewTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        JScrollPane previewScroll = new JBScrollPane(previewTable);
        // 仅约束高度，宽度由父容器决定，避免外层 detailPanel 被强制最小宽度撑大。
        previewScroll.setPreferredSize(new Dimension(0, 260));
        previewScroll.setMinimumSize(new Dimension(0, 200));

        // 参数表格（来自 SQL ${} 扫描）
        paramTableModel = new ParameterTableModel();
        paramTable = new JBTable(paramTableModel);
        paramTable.setRowHeight(22);
        configureParamTable();
        paramScroll = new JBScrollPane(paramTable);
        // 初始高度由 updateParamScrollHeight() 动态计算；宽度由父容器决定
        updateParamScrollHeight();
        // hint 文本较长，使用 HTML 包裹让其在窄宽时自动换行；外层包 BorderLayout 配合
        paramHintLabel = new JBLabel("<html>" + escapeHtml(
                DatabaseMcpMessages.message("settings.customTools.parameters.hint")) + "</html>");
        paramHintLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

        // 写回监听
        addWriteBackListener(nameField, () -> {
            if (currentEditing != null) {
                currentEditing.name = NAME_PREFIX + nameField.getText().trim();
                refreshListItem();
            }
        });
        addWriteBackListener(descriptionArea, () -> {
            if (currentEditing != null) {
                currentEditing.description = descriptionArea.getText();
                updateDescriptionCounter();
            }
        });
        addWriteBackListener(sqlArea, () -> {
            if (currentEditing != null) {
                currentEditing.sql = sqlArea.getText();
                syncParameterRows();
                // 若用户尚未手动改过类型，则根据 SQL 内容自动推断并更新下拉
                if (!sqlModeManuallyChanged) {
                    autoDetectSqlMode(currentEditing.sql);
                }
            }
        });
        addWriteBackListener(maxRowsField, () -> {
            if (currentEditing != null) {
                try {
                    currentEditing.maxRows = Integer.parseInt(maxRowsField.getText().trim());
                } catch (NumberFormatException ignored) {
                    currentEditing.maxRows = 0;
                }
            }
        });
        dataSourceCombo.addActionListener(e -> {
            if (updatingFromModel || currentEditing == null) {
                return;
            }
            Object selected = dataSourceCombo.getSelectedItem();
            currentEditing.dataSourceName = selected == null ? "" : selected.toString().trim();
            updateDataSourceValidation();
        });
        sqlModeCombo.addActionListener(e -> {
            if (updatingFromModel || currentEditing == null) {
                return;
            }
            boolean isDml = sqlModeCombo.getSelectedIndex() == 1;
            currentEditing.sqlMode = isDml ? CustomToolDefinition.SQL_MODE_DML : CustomToolDefinition.SQL_MODE_QUERY;
            // 用户手动修改后，不再自动覆盖
            sqlModeManuallyChanged = true;
            updateMaxRowsVisibility();
        });

        // 数据源行：下拉 + 刷新按钮
        JPanel dataSourceRow = new JPanel(new BorderLayout(4, 0));
        dataSourceRow.add(dataSourceCombo, BorderLayout.CENTER);
        dataSourceRow.add(refreshDataSourceButton, BorderLayout.EAST);

        // 描述行：文本框 + 计数 label
        JPanel descPanel = new JPanel(new BorderLayout(0, 2));
        descPanel.add(new JBScrollPane(descriptionArea), BorderLayout.CENTER);
        descPanel.add(descriptionCounterLabel, BorderLayout.SOUTH);

        // 测试区：按钮 + 状态 + 预览表
        JPanel testHeader = new JPanel(new BorderLayout(8, 0));
        testHeader.add(testButton, BorderLayout.WEST);
        testHeader.add(testStatusLabel, BorderLayout.CENTER);
        JPanel testPanel = new JPanel(new BorderLayout(0, 4));
        testPanel.add(testHeader, BorderLayout.NORTH);
        testPanel.add(previewScroll, BorderLayout.CENTER);

        // 参数区：仅放表格（hint 已移到 SQL 输入框上方，便于用户写 SQL 时实时查看）
        JPanel paramPanel = new JPanel(new BorderLayout(0, 4));
        paramPanel.add(paramScroll, BorderLayout.CENTER);

        // SQL 区：hint(顶部) + 输入框；hint 解释 ${} 用法
        JPanel sqlPanel = new JPanel(new BorderLayout(0, 4));
        sqlPanel.add(paramHintLabel, BorderLayout.NORTH);
        sqlPanel.add(new JBScrollPane(sqlArea), BorderLayout.CENTER);

        int row = 0;
        addLabeledRow(panel, nameLabel, namePanel, row++, 0.0);       // 固定高度
        addLabeledRow(panel, descriptionLabel, descPanel, row++, 0.5); // 少量弹性
        addLabeledRow(panel, dataSourceLabel, dataSourceRow, row++, 0.0);
        addLabeledRow(panel, sqlModeLabel, sqlModeCombo, row++, 0.0);
        addLabeledRow(panel, sqlLabel, sqlPanel, row++, 2.0);           // SQL 输入框：占最多纵向空间
        addLabeledRow(panel, maxRowsLabel, maxRowsField, row++, 0.0);
        addLabeledRow(panel, paramLabel, paramPanel, row++, 0.0);       // 参数区：高度由 updateParamScrollHeight() 动态控制
        addLabeledRow(panel, new JBLabel(""), testPanel, row, 1.0);    // 测试区：适中弹性

        return panel;
    }

    private void configureParamTable() {
        // type 列使用下拉
        JComboBox<String> typeCombo = new ComboBox<>(PARAM_TYPES);
        paramTable.getColumnModel().getColumn(ParameterTableModel.COL_TYPE)
                .setCellEditor(new DefaultCellEditor(typeCombo));
        // 列宽
        paramTable.getColumnModel().getColumn(ParameterTableModel.COL_NAME).setPreferredWidth(120);
        paramTable.getColumnModel().getColumn(ParameterTableModel.COL_TYPE).setPreferredWidth(80);
        paramTable.getColumnModel().getColumn(ParameterTableModel.COL_REQUIRED).setPreferredWidth(60);
        paramTable.getColumnModel().getColumn(ParameterTableModel.COL_DEFAULT).setPreferredWidth(100);
        paramTable.getColumnModel().getColumn(ParameterTableModel.COL_DESC).setPreferredWidth(200);

        // 渲染：文本类列鼠标悬浮时通过 tooltip 展示完整内容，避免被列宽截断后用户看不到全文
        DefaultTableCellRenderer tooltipRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                           boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (c instanceof JComponent jc) {
                    String text = value == null ? "" : value.toString();
                    jc.setToolTipText(text.isEmpty() ? null : text);
                }
                return c;
            }
        };
        // name 列禁灰显示，强调只读，同时也带 tooltip
        DefaultTableCellRenderer dim = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                           boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (!isSelected) {
                    c.setForeground(UIManager.getColor("Label.disabledForeground"));
                }
                if (c instanceof JComponent jc) {
                    String text = value == null ? "" : value.toString();
                    jc.setToolTipText(text.isEmpty() ? null : text);
                }
                return c;
            }
        };
        paramTable.getColumnModel().getColumn(ParameterTableModel.COL_NAME).setCellRenderer(dim);
        paramTable.getColumnModel().getColumn(ParameterTableModel.COL_DEFAULT).setCellRenderer(tooltipRenderer);
        paramTable.getColumnModel().getColumn(ParameterTableModel.COL_DESC).setCellRenderer(tooltipRenderer);
    }

    /**
     * 向 GridBagLayout 面板添加一行（label + field）。
     *
     * @param weighty {@code 0} 时使用 {@code HORIZONTAL} fill，field 高度由 preferred size 决定；
     *                {@code > 0} 时使用 {@code BOTH} fill，按权重分配剩余垂直空间。
     */
    private void addLabeledRow(JPanel panel, JLabel label, JComponent field, int row, double weighty) {
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0;
        lc.gridy = row;
        lc.anchor = GridBagConstraints.NORTHWEST;
        lc.insets = JBUI.insets(4, 0, 4, 8);
        panel.add(label, lc);

        GridBagConstraints fc = new GridBagConstraints();
        fc.gridx = 1;
        fc.gridy = row;
        fc.weightx = 1;
        fc.fill = weighty > 0 ? GridBagConstraints.BOTH : GridBagConstraints.HORIZONTAL;
        fc.weighty = weighty;
        fc.anchor = GridBagConstraints.WEST;
        fc.insets = JBUI.insets(4, 0, 4, 0);
        panel.add(field, fc);
    }

    /**
     * @deprecated 请使用 {@link #addLabeledRow(JPanel, JLabel, JComponent, int, double)}
     */
    @Deprecated
    @SuppressWarnings("unused")
    private void addLabeledRow(JPanel panel, JLabel label, JComponent field, int row, boolean stretchVertical) {
        addLabeledRow(panel, label, field, row, stretchVertical ? 0.5 : 0.0);
    }

    private ListSelectionListener buildSelectionListener() {
        return e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            CustomToolDefinition selected = toolList.getSelectedValue();
            loadIntoDetail(selected);
        };
    }

    private void loadIntoDetail(CustomToolDefinition def) {
        // 切换前如果表格正在编辑，强制提交，避免编辑器把旧值写到新对象上
        stopParamTableEditing();
        currentEditing = def;
        // 切换条目时重置手动标志，新条目允许 SQL 内容自动推断类型
        sqlModeManuallyChanged = false;
        updatingFromModel = true;
        try {
            if (def == null) {
                detailCardLayout.show(detailContainer, "EMPTY");
                return;
            }
            detailCardLayout.show(detailContainer, "DETAIL");
            String displayName = def.name == null ? "" : def.name;
            // 兼容历史数据：去除已存在的 custom_ 前缀，UI 仅显示用户可编辑的尾部。
            if (displayName.startsWith(NAME_PREFIX)) {
                displayName = displayName.substring(NAME_PREFIX.length());
            }
            nameField.setText(displayName);
            descriptionArea.setText(def.description == null ? "" : def.description);
            sqlArea.setText(def.sql == null ? "" : def.sql);
            maxRowsField.setText(String.valueOf(def.maxRows > 0 ? def.maxRows : 100));
            dataSourceCombo.setSelectedItem(def.dataSourceName == null ? "" : def.dataSourceName);
            // 同步 SQL 类型下拉：0=QUERY，1=DML
            sqlModeCombo.setSelectedIndex(CustomToolDefinition.SQL_MODE_DML.equalsIgnoreCase(def.sqlMode) ? 1 : 0);
            updateDescriptionCounter();
            updateMaxRowsVisibility();

            // 同步参数表（基于 SQL 中 ${} 的扫描结果与已存定义合并）
            syncParameterRows();
            // 清空预览
            clearPreview();
            testStatusLabel.setText(" ");
            // 切换条目后重新校验数据源必填状态
            updateDataSourceValidation();
        } finally {
            updatingFromModel = false;
        }
    }

    private void updateDescriptionCounter() {
        int len = descriptionArea.getText() == null ? 0 : descriptionArea.getText().length();
        descriptionCounterLabel.setText(len + "/" + MAX_DESCRIPTION_LENGTH);
    }

    /**
     * 根据数据源下拉框当前值实时更新必填校验边框，三种状态：
     * <ul>
     *   <li><b>空值</b>：红色边框，提示必填；</li>
     *   <li><b>不存在</b>：橙色边框 + tooltip 提示该数据源未在当前 IDE 中找到（可能尚未加载或已删除）；</li>
     *   <li><b>正常</b>：恢复默认边框，清空 tooltip。</li>
     * </ul>
     */
    private void updateDataSourceValidation() {
        if (dataSourceCombo == null) {
            return;
        }
        Object item = dataSourceCombo.getSelectedItem();
        String value = item == null ? "" : item.toString().trim();
        if (value.isEmpty()) {
            // 必填未填：红色边框
            dataSourceCombo.setBorder(javax.swing.BorderFactory.createLineBorder(com.intellij.ui.JBColor.RED, 1));
            dataSourceCombo.setToolTipText(DatabaseMcpMessages.message("settings.customTools.dataSource.required"));
        } else if (!knownDataSourceNames.isEmpty() && !knownDataSourceNames.contains(value)) {
            // 已加载列表但选中值不存在：橙色边框提示
            dataSourceCombo.setBorder(javax.swing.BorderFactory.createLineBorder(
                    new com.intellij.ui.JBColor(new java.awt.Color(255, 140, 0), new java.awt.Color(200, 120, 0)), 1));
            dataSourceCombo.setToolTipText(DatabaseMcpMessages.message("settings.customTools.dataSource.notFound", value));
        } else {
            // 正常：恢复默认边框
            dataSourceCombo.setBorder(defaultComboBorder());
            dataSourceCombo.setToolTipText(null);
        }
    }

    /**
     * 获取 ComboBox 的默认可见边框。
     *
     * <p>优先使用 LAF 中的 {@code ComboBox.border}；若当前主题返回 {@code null}（部分 IntelliJ
     * 主题不注册该 key），则回退到与主题配色一致的 1px 细线边框，确保边框始终可见。</p>
     */
    private static javax.swing.border.Border defaultComboBorder() {
        javax.swing.border.Border lafBorder = UIManager.getBorder("ComboBox.border");
        if (lafBorder != null) {
            return lafBorder;
        }
        // 回退：使用主题 Component.borderColor，若也不存在则用中性灰
        java.awt.Color borderColor = UIManager.getColor("Component.borderColor");
        if (borderColor == null) {
            borderColor = com.intellij.ui.JBColor.GRAY;
        }
        return javax.swing.BorderFactory.createLineBorder(borderColor, 1);
    }

    /**
     * 根据当前 SQL 类型（QUERY / DML）决定「最大行数」字段的可见性。
     * DML 不返回结果集，maxRows 对其无意义，隐藏以减少干扰。
     */
    private void updateMaxRowsVisibility() {
        if (maxRowsLabel == null || maxRowsField == null) {
            return;
        }
        boolean isDml = sqlModeCombo != null && sqlModeCombo.getSelectedIndex() == 1;
        maxRowsLabel.setVisible(!isDml);
        maxRowsField.setVisible(!isDml);
    }

    /**
     * 根据 SQL 内容自动推断类型并更新下拉（仅在用户未手动改过时调用）。
     *
     * <p>判断策略：剥离前置行注释（{@code --}）与块注释（{@code /* ... *\/}），
     * 取第一个有效 token，若匹配 DML 关键字则切换为 DML；否则保持 QUERY。
     * 不直接判断 SELECT，因为复杂查询（CTE、子查询等）不以 SELECT 开头。</p>
     *
     * @param sql 当前 SQL 文本
     */
    private void autoDetectSqlMode(String sql) {
        if (sql == null || sql.isBlank() || sqlModeCombo == null) {
            return;
        }
        String firstToken = extractFirstSqlToken(sql);
        boolean isDml = DML_KEYWORDS.contains(firstToken.toUpperCase());
        int targetIndex = isDml ? 1 : 0;
        if (sqlModeCombo.getSelectedIndex() != targetIndex) {
            updatingFromModel = true;
            try {
                sqlModeCombo.setSelectedIndex(targetIndex);
            } finally {
                updatingFromModel = false;
            }
            if (currentEditing != null) {
                currentEditing.sqlMode = isDml ? CustomToolDefinition.SQL_MODE_DML : CustomToolDefinition.SQL_MODE_QUERY;
            }
            updateMaxRowsVisibility();
        }
    }

    /**
     * 剥离 SQL 前置注释后提取第一个有效 token（单词）。
     * 支持行注释（{@code --}）和块注释（{@code /* ... *\/}）。
     */
    private static String extractFirstSqlToken(String sql) {
        if (sql == null) {
            return "";
        }
        // 去除块注释 /* ... */（非贪婪）
        String stripped = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        // 逐行处理：跳过以 -- 开头的行
        StringBuilder sb = new StringBuilder();
        for (String line : stripped.split("\n")) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("--")) {
                continue;
            }
            // 去掉行内 -- 注释
            int dashIdx = trimmed.indexOf("--");
            sb.append(dashIdx >= 0 ? trimmed.substring(0, dashIdx) : trimmed).append(' ');
        }
        String cleaned = sb.toString().trim();
        if (cleaned.isEmpty()) {
            return "";
        }
        // 取第一个由字母构成的 token
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-Za-z]+").matcher(cleaned);
        return m.find() ? m.group() : "";
    }

    /**
     * DML 关键字集合，用于自动推断 SQL 类型。
     * 不包含 SELECT / WITH 等查询关键字，避免误判复杂查询。
     */
    private static final java.util.Set<String> DML_KEYWORDS = java.util.Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "REPLACE", "UPSERT", "TRUNCATE"
    );

    private void refreshListItem() {
        int idx = toolList.getSelectedIndex();
        if (idx >= 0) {
            listModel.set(idx, currentEditing);
        }
    }

    private void addNewTool() {
        CustomToolDefinition def = new CustomToolDefinition();
        def.id = UUID.randomUUID().toString().replace("-", "").substring(0, 11);
        def.name = NAME_PREFIX + "tool_" + def.id;
        def.maxRows = 100;
        editingList.add(def);
        listModel.addElement(def);
        toolList.setSelectedIndex(listModel.size() - 1);
    }

    private void removeSelectedTool() {
        int idx = toolList.getSelectedIndex();
        if (idx < 0) {
            return;
        }
        CustomToolDefinition def = listModel.get(idx);
        editingList.remove(def);
        listModel.remove(idx);
        if (!listModel.isEmpty()) {
            toolList.setSelectedIndex(Math.min(idx, listModel.size() - 1));
        } else {
            loadIntoDetail(null);
        }
    }

    /**
     * 复制当前选中的自定义 tool：基于 {@link CustomToolDefinition#copy()} 做字段级深拷贝，
     * 重新生成 {@code id} 与「不与现有 tool / 内置 tool 重名」的新名称，插入到列表末尾并选中。
     * <p>新名称规则：在原名后追加 {@code _copy}（如已重复则继续追加 {@code _<shortId>}）。</p>
     */
    private void copySelectedTool() {
        int idx = toolList.getSelectedIndex();
        if (idx < 0) {
            return;
        }
        CustomToolDefinition source = listModel.get(idx);
        if (source == null) {
            return;
        }
        // 提交未保存的编辑，确保最新参数表内容也一并复制
        stopParamTableEditing();

        CustomToolDefinition copy = source.copy();
        copy.id = UUID.randomUUID().toString().replace("-", "").substring(0, 11);
        copy.name = generateUniqueCopyName(source.name == null ? "" : source.name, copy.id);
        editingList.add(copy);
        listModel.addElement(copy);
        toolList.setSelectedIndex(listModel.size() - 1);
    }

    /**
     * 基于源 tool 名生成不与「内置 tool / 已存在自定义 tool」重名的拷贝名。
     * 优先使用 {@code <baseName>_copy}，若仍冲突则追加短 id。
     */
    private String generateUniqueCopyName(String baseName, String shortId) {
        Set<String> taken = new HashSet<>(McpToolDefinitions.getBuiltinToolNames());
        for (CustomToolDefinition def : editingList) {
            if (def != null && def.name != null) {
                taken.add(def.name);
            }
        }
        String trimmed = baseName.trim();
        if (trimmed.isEmpty()) {
            trimmed = NAME_PREFIX + "tool";
        }
        String candidate = trimmed + "_copy";
        if (!taken.contains(candidate) && NAME_PATTERN.matcher(candidate).matches()) {
            return candidate;
        }
        candidate = trimmed + "_copy_" + shortId;
        // 极端情况下仍重复，则继续累加随机后缀
        while (taken.contains(candidate)) {
            candidate = trimmed + "_copy_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        }
        return candidate;
    }

    /**
     * 扫描 SQL 中的 {@code ${var}} 占位符，与现有 parameters 列表合并：
     * <ul>
     *   <li>保留已存在变量的描述/类型/默认值/必填配置；</li>
     *   <li>新增 SQL 中出现但未声明的变量；</li>
     *   <li>移除 SQL 中已不存在的变量；</li>
     *   <li>顺序按 SQL 中首次出现顺序排列。</li>
     * </ul>
     */
    private void syncParameterRows() {
        if (currentEditing == null) {
            return;
        }
        // 解析 SQL 中变量名（按出现顺序去重）
        Set<String> varsInOrder = new LinkedHashSet<>();
        if (currentEditing.sql != null) {
            Matcher m = SQL_VAR_PATTERN.matcher(currentEditing.sql);
            while (m.find()) {
                varsInOrder.add(m.group(1));
            }
        }
        // 现有声明索引
        Map<String, CustomToolParameter> existing = new LinkedHashMap<>();
        if (currentEditing.parameters != null) {
            for (CustomToolParameter p : currentEditing.parameters) {
                if (p != null && p.name != null) {
                    existing.put(p.name, p);
                }
            }
        }
        List<CustomToolParameter> merged = new ArrayList<>();
        for (String name : varsInOrder) {
            CustomToolParameter p = existing.get(name);
            if (p == null) {
                p = new CustomToolParameter(name, "", CustomToolParameter.TYPE_STRING, true, "");
            } else {
                p.name = name;
            }
            merged.add(p);
        }
        currentEditing.parameters = merged;
        paramTableModel.fireTableDataChanged();
        // 参数行数变化后动态调整滚动容器高度
        updateParamScrollHeight();
    }

    /**
     * 根据参数表当前行数动态调整 {@link #paramScroll} 的预设高度。
     *
     * <p>计算公式：表头高度 + 行数 × 行高，并限制在 [{@code MIN_PARAM_HEIGHT}, {@code MAX_PARAM_HEIGHT}] 区间内。
     * 高度改变后重新触发父容器布局刷新，确保 Splitter 内内容正确重绘。</p>
     */
    private void updateParamScrollHeight() {
        if (paramScroll == null || paramTable == null) {
            return;
        }
        // 表头高度（fallback 到默认值 24）
        int headerH = paramTable.getTableHeader() != null
                ? paramTable.getTableHeader().getPreferredSize().height
                : 24;
        int rowCount = paramTableModel != null ? paramTableModel.getRowCount() : 0;
        int rowH = paramTable.getRowHeight();
        // 最少显示 2 行空间（用于空状态提示），最多 10 行
        int clampedRows = Math.max(2, Math.min(10, rowCount));
        int height = headerH + clampedRows * rowH + 4; // +4 为边框留白
        Dimension d = new Dimension(0, height);
        paramScroll.setPreferredSize(d);
        paramScroll.setMinimumSize(d);
        // 触发父容器重新布局（paramPanel → GridBagLayout panel）
        if (paramScroll.getParent() != null) {
            JComponent paramPanel = (JComponent) paramScroll.getParent();
            paramPanel.setPreferredSize(new Dimension(0, height));
            paramPanel.setMinimumSize(new Dimension(0, height));
            if (paramPanel.getParent() != null) {
                paramPanel.getParent().revalidate();
            }
        }
    }

    private void stopParamTableEditing() {
        if (paramTable == null) {
            return;
        }
        TableCellEditor editor = paramTable.getCellEditor();
        if (editor != null) {
            editor.stopCellEditing();
        }
    }

    private void clearPreview() {
        previewModel.setColumnCount(0);
        previewModel.setRowCount(0);
    }

    /**
     * 触发"测试 SQL"按钮逻辑：先校验数据源 / SQL，若 SQL 中有 {@code ${}} 变量则弹窗收集测试值；
     * 真正执行通过后台 {@link Task.Backgroundable} 跑，结果回 EDT 渲染。
     * 根据当前 SQL 类型（QUERY / DML）分别调用对应方法。
     */
    private void runSqlTest() {
        if (currentEditing == null) {
            return;
        }
        stopParamTableEditing();
        final String dataSource = currentEditing.dataSourceName == null ? "" : currentEditing.dataSourceName.trim();
        final String sql = currentEditing.sql == null ? "" : currentEditing.sql;
        if (dataSource.isEmpty()) {
            Messages.showWarningDialog(DatabaseMcpMessages.message("settings.customTools.test.noDataSource"),
                    DatabaseMcpMessages.message("settings.customTools.test.title"));
            return;
        }
        if (sql.isBlank()) {
            Messages.showWarningDialog(DatabaseMcpMessages.message("settings.customTools.test.noSql"),
                    DatabaseMcpMessages.message("settings.customTools.test.title"));
            return;
        }

        final boolean isDml = CustomToolDefinition.SQL_MODE_DML.equalsIgnoreCase(currentEditing.sqlMode);

        // 收集变量顺序与测试值（含重复：同一占位符在 SQL 中出现多次时 bindValues 需要多个绑定值）
        List<String> varsInOrder = new ArrayList<>();
        Matcher m = SQL_VAR_PATTERN.matcher(sql);
        while (m.find()) {
            varsInOrder.add(m.group(1));
        }
        final List<Object> bindValues;
        if (varsInOrder.isEmpty()) {
            bindValues = Collections.emptyList();
        } else {
            // 去重后传入弹窗，同名参数只需用户输入一次；bindValues 仍按原始顺序（含重复）构建，
            // 确保 PreparedStatement 每个 ? 占位符都有对应绑定值
            List<String> uniqueVars = new ArrayList<>(new LinkedHashSet<>(varsInOrder));
            Map<String, Object> entered = promptTestValues(uniqueVars);
            if (entered == null) {
                return; // 用户取消
            }
            bindValues = new ArrayList<>(varsInOrder.size());
            for (String name : varsInOrder) {
                bindValues.add(entered.get(name));
            }
        }
        final String preparedSql = SQL_VAR_PATTERN.matcher(sql).replaceAll("?");
        // 预览行数沿用用户在「最大行数」字段配置的值；DML 模式忽略
        final int maxRows;
        if (!isDml && currentEditing.maxRows > 0) {
            maxRows = currentEditing.maxRows;
        } else {
            maxRows = TEST_PREVIEW_DEFAULT_ROWS;
        }

        testButton.setEnabled(false);
        testStatusLabel.setText(DatabaseMcpMessages.message("settings.customTools.test.running"));
        clearPreview();

        Project project = currentProject();
        Task.Backgroundable task = new Task.Backgroundable(project,
                DatabaseMcpMessages.message("settings.customTools.test.title"), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                long startNanos = System.nanoTime();
                try {
                    IdeDatabaseFacade facade = new IdeDatabaseFacade();
                    Map<String, Object> result;
                    if (isDml) {
                        result = facade.executeDmlSqlPrepared("", dataSource, preparedSql, bindValues,
                                McpSettingsState.DataSourceScope.ALL);
                    } else {
                        result = facade.executeQuerySqlPrepared("", dataSource, preparedSql, bindValues, maxRows,
                                McpSettingsState.DataSourceScope.ALL);
                    }
                    long costMs = (System.nanoTime() - startNanos) / 1_000_000L;
                    ApplicationManager.getApplication().invokeLater(() -> renderPreview(result, costMs, isDml));
                } catch (Throwable ex) {
                    String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                    McpRuntimeLogService.logError("settings.customTools", "Test SQL failed: " + msg);
                    ApplicationManager.getApplication().invokeLater(() -> renderError(msg));
                }
            }
        };
        ProgressManager.getInstance().run(task);
    }

    /**
     * SQL 执行失败时，将错误信息内联展示到预览表格区域，避免用户关掉弹窗后丢失错误上下文。
     *
     * @param errorMsg 错误信息
     */
    private void renderError(String errorMsg) {
        testButton.setEnabled(true);
        testStatusLabel.setText(DatabaseMcpMessages.message("settings.customTools.test.failed"));
        testStatusLabel.setForeground(com.intellij.ui.JBColor.RED);
        // 将错误详情展示在预览表格中，方便用户对照 SQL 排查
        previewModel.setColumnIdentifiers(new Object[]{
                DatabaseMcpMessages.message("settings.customTools.test.errorColumn")
        });
        previewModel.setRowCount(0);
        // 换行拆分，使长错误信息按行展示
        String[] lines = errorMsg == null ? new String[]{""} : errorMsg.split("\\r?\\n");
        for (String line : lines) {
            previewModel.addRow(new Object[]{line});
        }
    }

    @SuppressWarnings("unchecked")
    private void renderPreview(Map<String, Object> result, long costMs, boolean isDml) {
        testButton.setEnabled(true);
        // 成功时重置颜色，消除上一次失败的红色残留
        testStatusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        if (isDml) {
            // DML 不返回结果集，展示影响行数
            Object affected = result.get("affectedRows");
            int affectedRows = affected instanceof Number n ? n.intValue() : 0;
            testStatusLabel.setText(DatabaseMcpMessages.message("settings.customTools.test.dml.success",
                    affectedRows, costMs));
            clearPreview();
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.getOrDefault("rows", Collections.emptyList());
        // 收集列顺序：以首行 keys 为基准；后续行新增列追加。
        Set<String> columns = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            if (row != null) {
                columns.addAll(row.keySet());
            }
        }
        previewModel.setColumnIdentifiers(columns.toArray());
        previewModel.setRowCount(0);
        for (Map<String, Object> row : rows) {
            Object[] vector = new Object[columns.size()];
            int i = 0;
            for (String col : columns) {
                vector[i++] = formatPreviewCell(row.get(col));
            }
            previewModel.addRow(vector);
        }
        testStatusLabel.setText(DatabaseMcpMessages.message("settings.customTools.test.success",
                rows.size(), costMs));
    }

    private @Nullable Map<String, Object> promptTestValues(List<String> varsInOrder) {
        // 简单参数对话框：每个变量一个文本框（按 declared.type 转换）；返回 name -> value
        Map<String, CustomToolParameter> declared = new LinkedHashMap<>();
        if (currentEditing != null && currentEditing.parameters != null) {
            for (CustomToolParameter p : currentEditing.parameters) {
                if (p != null && p.name != null) {
                    declared.put(p.name, p);
                }
            }
        }
        TestParamDialog dialog = new TestParamDialog(varsInOrder, declared);
        if (!dialog.showAndGet()) {
            return null;
        }
        return dialog.collect();
    }

    /**
     * 加载当前生效作用域下的数据源名称到下拉框。
     */
    private void loadDataSourceOptions() {
        if (dataSourceCombo == null) {
            return;
        }
        List<String> names = new ArrayList<>();
        String errorMsg = null;
        try {
            IdeDatabaseFacade facade = new IdeDatabaseFacade();
            List<Map<String, Object>> rows = facade.listDataSources("", McpSettingsState.DataSourceScope.ALL);
            for (Map<String, Object> row : rows) {
                Object n = row.get("name");
                if (n == null) {
                    continue;
                }
                String name = n.toString();
                if (name.startsWith("__discovery_error__")) {
                    Object msg = row.get("message");
                    errorMsg = msg == null ? "discovery error" : msg.toString();
                    continue;
                }
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
        } catch (Throwable ex) {
            errorMsg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            McpRuntimeLogService.logError("settings.customTools",
                    "Load data sources failed: " + errorMsg);
        }
        applyDataSourceOptions(names, errorMsg);
    }

    private void applyDataSourceOptions(List<String> names, String errorMsg) {
        if (dataSourceCombo == null) {
            return;
        }
        String preferred = currentEditing != null && currentEditing.dataSourceName != null
                ? currentEditing.dataSourceName.trim()
                : "";
        if (preferred.isEmpty()) {
            Object selectedItem = dataSourceCombo.getSelectedItem();
            preferred = selectedItem == null ? "" : selectedItem.toString().trim();
        }
        updatingFromModel = true;
        try {
            dataSourceCombo.removeAllItems();
            for (String n : names) {
                dataSourceCombo.addItem(n);
            }
            // 更新已知数据源缓存（仅在成功加载到数据源时刷新，避免加载失败时清空缓存）
            if (!names.isEmpty()) {
                knownDataSourceNames = new HashSet<>(names);
            }
            if (names.isEmpty()) {
                // 不可编辑模式下用占位 item 显示提示信息（不写入 dataSourceName）
                String placeholder = errorMsg != null
                        ? DatabaseMcpMessages.message("settings.customTools.dataSource.loadError", errorMsg)
                        : DatabaseMcpMessages.message("settings.customTools.dataSource.empty");
                dataSourceCombo.addItem(placeholder);
                dataSourceCombo.setSelectedIndex(0);
            } else if (!preferred.isEmpty()) {
                dataSourceCombo.setSelectedItem(preferred);
            } else {
                dataSourceCombo.setSelectedIndex(0);
            }
        } finally {
            updatingFromModel = false;
        }
        // 数据源列表加载完成后重新触发校验，更新"不存在"提示状态
        updateDataSourceValidation();
    }

    @Override
    public boolean isModified() {
        if (rootPanel == null) {
            return false;
        }
        // 注意：此处禁止调用 stopParamTableEditing()。
        // IntelliJ Settings 框架在用户每次按键后都会反复调用 isModified() 来刷新 Apply 按钮状态，
        // 若在此提交单元格编辑器，将导致用户无法在参数表格里输入第二个字符。
        // 编辑器的提交时机交由 loadIntoDetail / runSqlTest / apply 显式控制。
        List<CustomToolDefinition> persisted = McpSettingsState.getInstance().getCustomTools(currentScope());
        if (persisted.size() != editingList.size()) {
            return true;
        }
        for (int i = 0; i < persisted.size(); i++) {
            if (!equalsDefinition(persisted.get(i), editingList.get(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean equalsDefinition(CustomToolDefinition a, CustomToolDefinition b) {
        return java.util.Objects.equals(a.id, b.id)
                && java.util.Objects.equals(a.name, b.name)
                && java.util.Objects.equals(a.description, b.description)
                && java.util.Objects.equals(a.dataSourceName, b.dataSourceName)
                && java.util.Objects.equals(a.sql, b.sql)
                && a.maxRows == b.maxRows
                && a.enabled == b.enabled
                && equalsParameters(a.parameters, b.parameters);
    }

    private boolean equalsParameters(List<CustomToolParameter> a, List<CustomToolParameter> b) {
        int sa = a == null ? 0 : a.size();
        int sb = b == null ? 0 : b.size();
        if (sa != sb) {
            return false;
        }
        for (int i = 0; i < sa; i++) {
            CustomToolParameter pa = a.get(i);
            CustomToolParameter pb = b.get(i);
            if (!java.util.Objects.equals(pa.name, pb.name)
                    || !java.util.Objects.equals(pa.description, pb.description)
                    || !java.util.Objects.equals(pa.type, pb.type)
                    || pa.required != pb.required
                    || !java.util.Objects.equals(pa.defaultValue, pb.defaultValue)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void apply() throws ConfigurationException {
        stopParamTableEditing();
        Set<String> seenNames = new HashSet<>();
        Set<String> builtin = McpToolDefinitions.getBuiltinToolNames();
        for (CustomToolDefinition def : editingList) {
            String name = def.name == null ? "" : def.name.trim();
            if (name.isEmpty()) {
                throw new ConfigurationException(DatabaseMcpMessages.message("settings.customTools.error.emptyName"));
            }
            if (!name.startsWith(NAME_PREFIX)) {
                // 兼容历史数据：apply 时强制补前缀
                name = NAME_PREFIX + name;
            }
            if (!NAME_PATTERN.matcher(name).matches()) {
                throw new ConfigurationException(DatabaseMcpMessages.message("settings.customTools.error.invalidName", name));
            }
            if (builtin.contains(name)) {
                throw new ConfigurationException(DatabaseMcpMessages.message("settings.customTools.error.builtinConflict", name));
            }
            if (!seenNames.add(name)) {
                throw new ConfigurationException(DatabaseMcpMessages.message("settings.customTools.error.duplicateName", name));
            }
            String desc = def.description == null ? "" : def.description;
            if (desc.length() > MAX_DESCRIPTION_LENGTH) {
                throw new ConfigurationException(DatabaseMcpMessages.message("settings.customTools.error.descTooLong", name, MAX_DESCRIPTION_LENGTH));
            }
            if (def.dataSourceName == null || def.dataSourceName.trim().isEmpty()) {
                throw new ConfigurationException(DatabaseMcpMessages.message("settings.customTools.error.emptyDataSource", name));
            }
            if (def.sql == null || def.sql.trim().isEmpty()) {
                throw new ConfigurationException(DatabaseMcpMessages.message("settings.customTools.error.emptySql", name));
            }
            if (def.maxRows <= 0) {
                def.maxRows = 100;
            }
            // 校验：SQL 中所有占位符必须在 parameters 中声明
            Set<String> sqlVars = new LinkedHashSet<>();
            Matcher m = SQL_VAR_PATTERN.matcher(def.sql);
            while (m.find()) {
                sqlVars.add(m.group(1));
            }
            Set<String> declared = new HashSet<>();
            if (def.parameters != null) {
                for (CustomToolParameter p : def.parameters) {
                    if (p != null && p.name != null) {
                        declared.add(p.name);
                    }
                }
            }
            for (String v : sqlVars) {
                if (!declared.contains(v)) {
                    throw new ConfigurationException(DatabaseMcpMessages.message("settings.customTools.error.missingParam", name, v));
                }
            }
            // 规范化
            def.name = name;
            def.dataSourceName = def.dataSourceName.trim();
        }
        McpSettingsState.getInstance().setCustomTools(currentScope(), editingList);
        editingList = McpSettingsState.getInstance().getCustomTools(currentScope());
        rebuildListModel();
        // 同步统计表：删除/新增/启用/禁用 后，让工具窗口的方法计数表立刻反映变更
        McpMethodMetricsService.getInstance().syncCustomTools();
    }

    private void rebuildListModel() {
        int selected = toolList.getSelectedIndex();
        listModel.clear();
        for (CustomToolDefinition def : editingList) {
            listModel.addElement(def);
        }
        if (selected >= 0 && selected < listModel.size()) {
            toolList.setSelectedIndex(selected);
        } else if (!listModel.isEmpty()) {
            toolList.setSelectedIndex(0);
        } else {
            loadIntoDetail(null);
        }
    }

    @Override
    public void reset() {
        editingList = McpSettingsState.getInstance().getCustomTools(currentScope());
        rebuildListModel();
    }

    @Override
    public void disposeUIResources() {
        // 兜底保存：无论用户点击 OK / Cancel / 关闭按钮，均保存最终分割比例，
        // 避免 PropertyChangeListener 未触发时丢失用户调整的结果
        if (splitter != null) {
            McpSettingsState.getInstance().setCustomToolsSplitterProportion(splitter.getProportion());
            splitter = null;
        }
        rootPanel = null;
        toolList = null;
        listModel = null;
        nameField = null;
        descriptionArea = null;
        descriptionCounterLabel = null;
        dataSourceCombo = null;
        refreshDataSourceButton = null;
        sqlModeCombo = null;
        maxRowsLabel = null;
        sqlArea = null;
        maxRowsField = null;
        testButton = null;
        testStatusLabel = null;
        previewModel = null;
        previewTable = null;
        paramTable = null;
        paramTableModel = null;
        paramScroll = null;
        paramHintLabel = null;
        detailPanel = null;
        detailCardLayout = null;
        detailContainer = null;
        emptyHintLabel = null;
        currentEditing = null;
        editingList = Collections.emptyList();
        knownDataSourceNames = new HashSet<>();
    }

    private McpSettingsState.PluginSettingsScope currentScope() {
        return McpSettingsState.getInstance().getPluginSettingsScope();
    }

    private @Nullable Project currentProject() {
        Project[] open = ProjectManager.getInstance().getOpenProjects();
        return open.length == 0 ? null : open[0];
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 预览时间格式：所有 SQL 时间/日期类型字段在测试预览中按此统一格式渲染。
     */
    private static final java.time.format.DateTimeFormatter PREVIEW_DATETIME_FORMATTER =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final java.time.format.DateTimeFormatter PREVIEW_DATE_FORMATTER =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final java.time.format.DateTimeFormatter PREVIEW_TIME_FORMATTER =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * 将单元格原始值格式化为预览字符串。
     * <p>对常见时间/日期类型按 {@code yyyy-MM-dd HH:mm:ss} 输出；
     * {@link java.sql.Date} 输出 {@code yyyy-MM-dd}；
     * {@link java.sql.Time} 输出 {@code HH:mm:ss}；
     * 其它类型直接 {@code toString()}。</p>
     */
    private static String formatPreviewCell(Object value) {
        if (value == null) {
            return "";
        }
        // java.sql.Timestamp 是 java.util.Date 的子类，必须先判断
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().format(PREVIEW_DATETIME_FORMATTER);
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate().format(PREVIEW_DATE_FORMATTER);
        }
        if (value instanceof java.sql.Time t) {
            return t.toLocalTime().format(PREVIEW_TIME_FORMATTER);
        }
        if (value instanceof java.time.LocalDateTime ldt) {
            return ldt.format(PREVIEW_DATETIME_FORMATTER);
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toLocalDateTime().format(PREVIEW_DATETIME_FORMATTER);
        }
        if (value instanceof java.time.ZonedDateTime zdt) {
            return zdt.toLocalDateTime().format(PREVIEW_DATETIME_FORMATTER);
        }
        if (value instanceof java.time.LocalDate ld) {
            return ld.format(PREVIEW_DATE_FORMATTER);
        }
        if (value instanceof java.time.LocalTime lt) {
            return lt.format(PREVIEW_TIME_FORMATTER);
        }
        if (value instanceof java.util.Date d) {
            // 走系统默认时区
            return java.time.LocalDateTime
                    .ofInstant(d.toInstant(), java.time.ZoneId.systemDefault())
                    .format(PREVIEW_DATETIME_FORMATTER);
        }
        if (value instanceof java.time.Instant ins) {
            return java.time.LocalDateTime
                    .ofInstant(ins, java.time.ZoneId.systemDefault())
                    .format(PREVIEW_DATETIME_FORMATTER);
        }
        return value.toString();
    }


    private void addWriteBackListener(JTextField field, Runnable action) {
        field.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            if (!updatingFromModel) {
                action.run();
            }
        }));
    }

    private void addWriteBackListener(JTextArea area, Runnable action) {
        area.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            if (!updatingFromModel) {
                action.run();
            }
        }));
    }

    /**
     * 参数表格 model：列依次为 name(只读) / type(下拉) / required(checkbox) / default / description。
     * 数据源直接绑定到 {@link #currentEditing} 的 parameters 列表。
     */
    private final class ParameterTableModel extends AbstractTableModel {
        static final int COL_NAME = 0;
        static final int COL_TYPE = 1;
        static final int COL_REQUIRED = 2;
        static final int COL_DEFAULT = 3;
        static final int COL_DESC = 4;

        private final String[] columnNames = new String[]{
                DatabaseMcpMessages.message("settings.customTools.parameters.column.name"),
                DatabaseMcpMessages.message("settings.customTools.parameters.column.type"),
                DatabaseMcpMessages.message("settings.customTools.parameters.column.required"),
                DatabaseMcpMessages.message("settings.customTools.parameters.column.default"),
                DatabaseMcpMessages.message("settings.customTools.parameters.column.description")
        };

        @Override
        public int getRowCount() {
            return currentEditing == null || currentEditing.parameters == null ? 0 : currentEditing.parameters.size();
        }

        @Override
        public int getColumnCount() {
            return 5;
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == COL_REQUIRED ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex != COL_NAME;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            CustomToolParameter p = currentEditing.parameters.get(rowIndex);
            switch (columnIndex) {
                case COL_NAME:
                    return p.name;
                case COL_TYPE:
                    return p.type == null ? CustomToolParameter.TYPE_STRING : p.type;
                case COL_REQUIRED:
                    return p.required;
                case COL_DEFAULT:
                    return p.defaultValue == null ? "" : p.defaultValue;
                case COL_DESC:
                    return p.description == null ? "" : p.description;
                default:
                    return "";
            }
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (currentEditing == null || rowIndex >= currentEditing.parameters.size()) {
                return;
            }
            CustomToolParameter p = currentEditing.parameters.get(rowIndex);
            switch (columnIndex) {
                case COL_TYPE:
                    p.type = aValue == null ? CustomToolParameter.TYPE_STRING : aValue.toString();
                    break;
                case COL_REQUIRED:
                    p.required = Boolean.TRUE.equals(aValue);
                    break;
                case COL_DEFAULT:
                    p.defaultValue = aValue == null ? "" : aValue.toString();
                    break;
                case COL_DESC:
                    p.description = aValue == null ? "" : aValue.toString();
                    break;
                default:
                    return;
            }
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }

    /**
     * 测试时若 SQL 含 ${} 变量，弹出对话框收集每个变量的实际值，按 declared.type 转换。
     */
    private static final class TestParamDialog extends DialogWrapper {
        private final List<String> vars;
        private final Map<String, CustomToolParameter> declared;
        private final Map<String, JTextField> fields = new LinkedHashMap<>();

        TestParamDialog(List<String> vars, Map<String, CustomToolParameter> declared) {
            super(true);
            this.vars = vars;
            this.declared = declared;
            setTitle(DatabaseMcpMessages.message("settings.customTools.test.paramDialogTitle"));
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            panel.setBorder(JBUI.Borders.empty(8));
            int row = 0;
            for (String name : vars) {
                CustomToolParameter d = declared.get(name);
                String type = d == null ? CustomToolParameter.TYPE_STRING : d.type;
                String hint = type + (d != null && d.required ? " *" : "");

                GridBagConstraints lc = new GridBagConstraints();
                lc.gridx = 0;
                lc.gridy = row;
                lc.anchor = GridBagConstraints.WEST;
                lc.insets = JBUI.insets(4, 0, 4, 8);
                panel.add(new JBLabel(name + " (" + hint + "):"), lc);

                GridBagConstraints fc = new GridBagConstraints();
                fc.gridx = 1;
                fc.gridy = row;
                fc.weightx = 1;
                fc.fill = GridBagConstraints.HORIZONTAL;
                fc.insets = JBUI.insets(4, 0, 4, 0);
                JBTextField field = new JBTextField();
                field.setColumns(28);
                if (d != null && d.defaultValue != null && !d.defaultValue.isBlank()) {
                    field.setText(d.defaultValue);
                }
                panel.add(field, fc);
                fields.put(name, field);
                row++;
            }
            return panel;
        }

        Map<String, Object> collect() {
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<String, JTextField> e : fields.entrySet()) {
                String name = e.getKey();
                String raw = e.getValue().getText();
                CustomToolParameter d = declared.get(name);
                String type = d == null ? CustomToolParameter.TYPE_STRING : d.type;
                values.put(name, convert(type, raw));
            }
            return values;
        }

        private Object convert(String type, String raw) {
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            try {
                switch (type == null ? CustomToolParameter.TYPE_STRING : type) {
                    case CustomToolParameter.TYPE_INTEGER:
                        return Long.parseLong(raw.trim());
                    case CustomToolParameter.TYPE_NUMBER:
                        return Double.parseDouble(raw.trim());
                    case CustomToolParameter.TYPE_BOOLEAN:
                        return Boolean.parseBoolean(raw.trim());
                    case CustomToolParameter.TYPE_STRING:
                    default:
                        return raw;
                }
            } catch (NumberFormatException ex) {
                return raw;
            }
        }
    }

    private static final class SimpleDocumentListener implements DocumentListener {
        private final Runnable action;

        SimpleDocumentListener(Runnable action) {
            this.action = action;
        }

        @Override
        public void insertUpdate(DocumentEvent e) {
            action.run();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            action.run();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            action.run();
        }
    }

    private static final class MaxLengthDocumentFilter extends DocumentFilter {
        private final int maxLength;

        MaxLengthDocumentFilter(int maxLength) {
            this.maxLength = maxLength;
        }

        @Override
        public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
            if (string == null) {
                return;
            }
            int newLength = fb.getDocument().getLength() + string.length();
            if (newLength <= maxLength) {
                super.insertString(fb, offset, string, attr);
            } else {
                int allowed = maxLength - fb.getDocument().getLength();
                if (allowed > 0) {
                    super.insertString(fb, offset, string.substring(0, allowed), attr);
                }
            }
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
            if (text == null) {
                super.replace(fb, offset, length, "", attrs);
                return;
            }
            int newLength = fb.getDocument().getLength() - length + text.length();
            if (newLength <= maxLength) {
                super.replace(fb, offset, length, text, attrs);
            } else {
                int allowed = maxLength - (fb.getDocument().getLength() - length);
                if (allowed > 0) {
                    super.replace(fb, offset, length, text.substring(0, allowed), attrs);
                } else {
                    super.replace(fb, offset, length, "", attrs);
                }
            }
        }
    }

    /**
     * 方法名输入过滤器：仅允许字母、数字、下划线（不含 custom_ 前缀本身，前缀由独立 Label 渲染）。
     */
    private static final class NameCharsDocumentFilter extends DocumentFilter {
        @Override
        public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
            String filtered = sanitize(string);
            if (!filtered.isEmpty()) {
                super.insertString(fb, offset, filtered, attr);
            }
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
            String filtered = sanitize(text);
            super.replace(fb, offset, length, filtered, attrs);
        }

        private String sanitize(String value) {
            if (value == null || value.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
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

    /**
     * 列表项渲染：左侧 checkbox 控制 enabled，右侧主标题为 tool name，副标题为 description 截断显示。
     * <p>checkbox 仅作可视化指示，实际状态切换由 {@link CustomToolsConfigurable} 中的 mouse listener
     * 通过点击区域判定。</p>
     */
    private static final class ToolListRenderer implements ListCellRenderer<CustomToolDefinition> {
        /**
         * checkbox 命中区域宽度（含左右内边距），点击落在此区域内视为切换 enabled。
         */
        static final int CHECKBOX_WIDTH = 28;

        private final JPanel panel = new JPanel(new BorderLayout());
        private final JCheckBox checkBox = new JCheckBox();
        private final JLabel textLabel = new JLabel();

        ToolListRenderer() {
            checkBox.setOpaque(false);
            checkBox.setBorder(JBUI.Borders.empty(0, 4, 0, 4));
            checkBox.setFocusable(false);
            textLabel.setBorder(JBUI.Borders.emptyLeft(2));
            panel.add(checkBox, BorderLayout.WEST);
            panel.add(textLabel, BorderLayout.CENTER);
            panel.setBorder(JBUI.Borders.empty(2, 0));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends CustomToolDefinition> list,
                                                      CustomToolDefinition def,
                                                      int index,
                                                      boolean isSelected,
                                                      boolean cellHasFocus) {
            if (isSelected) {
                panel.setBackground(list.getSelectionBackground());
                textLabel.setForeground(list.getSelectionForeground());
            } else {
                panel.setBackground(list.getBackground());
                textLabel.setForeground(list.getForeground());
            }
            panel.setOpaque(true);

            String name = def == null || def.name == null || def.name.isBlank()
                    ? DatabaseMcpMessages.message("settings.customTools.unnamed")
                    : def.name;
            String desc = def == null || def.description == null
                    ? "" : def.description.replaceAll("\\s+", " ").trim();
            if (desc.length() > 60) {
                desc = desc.substring(0, 57) + "...";
            }
            textLabel.setText("<html><b>" + escapeHtml(name) + "</b>"
                    + (desc.isEmpty() ? "" : "<br><small style='color:gray'>" + escapeHtml(desc) + "</small>")
                    + "</html>");
            checkBox.setSelected(def != null && def.enabled);
            return panel;
        }
    }
}
