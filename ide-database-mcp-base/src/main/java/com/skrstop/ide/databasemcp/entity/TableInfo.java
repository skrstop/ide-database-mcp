package com.skrstop.ide.databasemcp.entity;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 表的元信息，包含评分和列列表。
 */
@Getter
@Setter
public class TableInfo {
    /**
     * 表名
     */
    private String tableName;
    /**
     * 表注释
     */
    private String comment;
    /**
     * 所属 catalog
     */
    private String catalog;
    /**
     * 所属 schema
     */
    private String schema;
    /**
     * 相关性综合评分（越高越优先被采样）
     */
    private int score;
    /**
     * 列信息列表
     */
    private List<ColumnInfo> columns = new ArrayList<>();

    public TableInfo(String tableName, String comment, String catalog, String schema) {
        this.tableName = tableName;
        this.comment = comment;
        this.catalog = catalog;
        this.schema = schema;
    }
}
