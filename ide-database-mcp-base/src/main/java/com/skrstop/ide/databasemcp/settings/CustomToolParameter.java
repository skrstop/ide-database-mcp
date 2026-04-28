package com.skrstop.ide.databasemcp.settings;

import com.intellij.util.xmlb.annotations.Property;

/**
 * 自定义 MCP Tool 的入参声明。对应 SQL 中以 {@code ${name}} 形式出现的占位符。
 *
 * <p>该对象通过 IntelliJ {@code XmlSerializer} 持久化在 {@link CustomToolDefinition#parameters} 中，
 * 因此字段必须为 {@code public} 并保持基础类型，避免序列化失败。</p>
 *
 * <p>关键字段（type / description / required / defaultValue）均使用
 * {@link Property#alwaysWrite()} 强制持久化，避免与 Java 默认值相同时被 XmlSerializer 省略，
 * 便于排查、可读性以及未来字段语义变更后的兼容。</p>
 *
 * <p>运行时由 {@link com.skrstop.ide.databasemcp.mcp.McpToolDefinitions} 转换为 JSON Schema
 * 暴露给 MCP 客户端，并由 {@link com.skrstop.ide.databasemcp.mcp.McpProtocolRouter} 在 tools/call
 * 时按声明顺序绑定到 {@link java.sql.PreparedStatement}。</p>
 *
 * @author skrstop AI
 */
public class CustomToolParameter {

    /**
     * 支持的参数类型，对应 JSON Schema 与 PreparedStatement 绑定语义。
     */
    public static final String TYPE_STRING = "string";
    public static final String TYPE_INTEGER = "integer";
    public static final String TYPE_NUMBER = "number";
    public static final String TYPE_BOOLEAN = "boolean";

    /**
     * 参数名，等同于 SQL 中 {@code ${xxx}} 占位符里的 xxx。必须为合法的 JSON 字段名。
     */
    public String name = "";

    /**
     * 参数描述，暴露给 MCP 客户端，作为 LLM 选择填值的提示文本。
     */
    @Property(alwaysWrite = true)
    public String description = "";

    /**
     * 参数类型，取值见 {@link #TYPE_STRING} / {@link #TYPE_INTEGER} / {@link #TYPE_NUMBER} / {@link #TYPE_BOOLEAN}。
     *
     * <p>{@code alwaysWrite = true} 强制持久化，避免默认值 {@code "string"} 被 XmlSerializer 省略，
     * 否则在 XML 中无法直观看到参数类型。</p>
     */
    @Property(alwaysWrite = true)
    public String type = TYPE_STRING;

    /**
     * 是否必填。{@code false} 时若客户端未提供，将使用 {@link #defaultValue}。
     */
    @Property(alwaysWrite = true)
    public boolean required = true;

    /**
     * 默认值（字符串形式，按 {@link #type} 解析）。可为空。
     */
    @Property(alwaysWrite = true)
    public String defaultValue = "";

    public CustomToolParameter() {
    }

    public CustomToolParameter(String name, String description, String type,
                               boolean required, String defaultValue) {
        this.name = name;
        this.description = description;
        this.type = type;
        this.required = required;
        this.defaultValue = defaultValue;
    }

    /**
     * @return 字段级浅拷贝
     */
    public CustomToolParameter copy() {
        return new CustomToolParameter(name, description, type, required, defaultValue);
    }
}

