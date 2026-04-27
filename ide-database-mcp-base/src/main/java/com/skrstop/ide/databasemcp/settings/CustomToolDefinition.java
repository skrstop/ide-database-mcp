package com.skrstop.ide.databasemcp.settings;

import com.intellij.util.xmlb.annotations.XCollection;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户在 Settings 中自定义的 MCP Tool 定义。
 *
 * <p>该对象通过 IntelliJ {@code XmlSerializer} 持久化到 {@code McpSettingsState.State.customTools}，
 * 因此字段必须为 {@code public} 且使用基础类型，避免序列化失败。</p>
 *
 * <p>每条定义在运行时被 {@link com.skrstop.ide.databasemcp.mcp.McpToolDefinitions} 转为标准 MCP tool schema，
 * 并由 {@link com.skrstop.ide.databasemcp.mcp.McpProtocolRouter} 路由到 {@code executeQuerySql}。</p>
 *
 * @author skrstop AI
 */
public class CustomToolDefinition {

    /**
     * 内部稳定标识，用于 UI 列表去重与编辑追踪，不暴露给 MCP 客户端。
     */
    public String id = "";

    /**
     * Tool 名称，暴露给 MCP 客户端，需在内置 tool 与其它自定义 tool 中保持唯一。
     * <p>必须以 {@code custom_} 前缀开头。</p>
     */
    public String name = "";

    /**
     * 功能描述，限制 300 字。暴露给 MCP 客户端，作为 LLM 选择 tool 的提示文本。
     */
    public String description = "";

    /**
     * 绑定的 IDE 数据源名称，对应 {@code IdeDatabaseFacade.listDataSources} 返回的 {@code name} 字段。
     */
    public String dataSourceName = "";

    /**
     * 预置 SQL 文本，将作为 SQL 参数执行。
     * <p>支持 {@code ${name}} 形式的命名占位符，运行时按 {@link #parameters} 顺序绑定到
     * {@link java.sql.PreparedStatement}。</p>
     */
    public String sql = "";

    /**
     * SQL 执行模式：{@code "QUERY"} 表示查询（SELECT），{@code "DML"} 表示写操作（INSERT / UPDATE / DELETE）。
     * <p>默认 {@code "QUERY"}；DML 模式下不返回结果集，仅返回影响行数。</p>
     *
     * @see #SQL_MODE_QUERY
     * @see #SQL_MODE_DML
     */
    public String sqlMode = SQL_MODE_QUERY;

    /**
     * SQL 执行模式：查询（SELECT）。
     */
    public static final String SQL_MODE_QUERY = "QUERY";

    /**
     * SQL 执行模式：写操作（INSERT / UPDATE / DELETE）。
     */
    public static final String SQL_MODE_DML = "DML";

    /**
     * 默认最大返回行数。MCP 客户端调用时若传入 {@code maxRows} 则覆盖该值。
     */
    public int maxRows = 100;

    /**
     * 是否启用。{@code false} 时该 tool 不会出现在 MCP {@code tools/list} 中，
     * 也不会被 {@link com.skrstop.ide.databasemcp.mcp.McpProtocolRouter} 路由。
     * <p>UI 在左侧列表项的 checkbox 中编辑。</p>
     */
    public boolean enabled = true;

    /**
     * 自定义 tool 入参列表，与 SQL 中 {@code ${...}} 占位符一一对应。
     *
     * @see CustomToolParameter
     */
    @XCollection(style = XCollection.Style.v2)
    public List<CustomToolParameter> parameters = new ArrayList<>();

    public CustomToolDefinition() {
    }

    public CustomToolDefinition(String id, String name, String description,
                                String dataSourceName, String sql, int maxRows) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.dataSourceName = dataSourceName;
        this.sql = sql;
        this.maxRows = maxRows;
    }

    /**
     * 创建一份字段级深拷贝（含 parameters），用于 UI 编辑缓冲与持久化分离。
     *
     * @return 新对象
     */
    public CustomToolDefinition copy() {
        CustomToolDefinition c = new CustomToolDefinition(id, name, description, dataSourceName, sql, maxRows);
        c.enabled = enabled;
        c.sqlMode = sqlMode;
        if (parameters != null) {
            for (CustomToolParameter p : parameters) {
                if (p != null) {
                    c.parameters.add(p.copy());
                }
            }
        }
        return c;
    }
}
