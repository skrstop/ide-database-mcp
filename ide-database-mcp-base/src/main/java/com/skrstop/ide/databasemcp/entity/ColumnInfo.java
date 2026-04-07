package com.skrstop.ide.databasemcp.entity;

import lombok.Getter;
import lombok.Setter;

/**
 * 列的元信息。
 */
@Getter
@Setter
public class ColumnInfo {
    /**
     * 列名
     */
    private String columnName;
    /**
     * 数据类型名称
     */
    private String typeName;
    /**
     * 列的精度/长度
     */
    private int columnSize;
    /**
     * 列注释
     */
    private String comment;
    /**
     * 是否允许为 NULL
     */
    private boolean nullable;
    /**
     * 默认值
     */
    private String defaultValue;
    /**
     * 在表中的排列位置（1-based）
     */
    private int ordinalPosition;

    public ColumnInfo(String columnName, String typeName, int columnSize, String comment,
                      boolean nullable, String defaultValue, int ordinalPosition) {
        this.columnName = columnName;
        this.typeName = typeName;
        this.columnSize = columnSize;
        this.comment = comment;
        this.nullable = nullable;
        this.defaultValue = defaultValue;
        this.ordinalPosition = ordinalPosition;
    }
}
