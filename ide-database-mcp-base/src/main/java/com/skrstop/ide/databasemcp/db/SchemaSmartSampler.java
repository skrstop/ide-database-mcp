package com.skrstop.ide.databasemcp.db;

import com.intellij.openapi.application.ApplicationManager;
import com.skrstop.ide.databasemcp.service.McpRuntimeLogService;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Schema 智能采样器 - 从大型企业数据库中按相关性采样局部 Schema。
 *
 * <p>采样算法：
 * <ol>
 *   <li>通过 JDBC {@link DatabaseMetaData} 加载所有表（支持前缀过滤）</li>
 *   <li>对每张表按关键词匹配（表名、列名、注释）计算相关性评分</li>
 *   <li>通过外键关系（FK）识别核心关联表，并对被引用次数多的表加分</li>
 *   <li>按评分降序取 Top-N 张表，对有剩余名额的情况执行关联扩散</li>
 *   <li>输出每张表的 Schema：表名、注释、列信息（可选索引）</li>
 * </ol>
 *
 * @author skrstop AI
 */
final class SchemaSmartSampler {

    /**
     * 关键词命中表名的评分权重
     */
    private static final int SCORE_TABLE_NAME_KEYWORD = 10;

    /**
     * 关键词命中表注释的评分权重
     */
    private static final int SCORE_TABLE_COMMENT_KEYWORD = 8;

    /**
     * 关键词命中列名的评分权重
     */
    private static final int SCORE_COLUMN_NAME_KEYWORD = 5;

    /**
     * 关键词命中列注释的评分权重
     */
    private static final int SCORE_COLUMN_COMMENT_KEYWORD = 3;

    /**
     * 表名前缀精确匹配的评分权重（优先级最高）
     */
    private static final int SCORE_PREFIX_MATCH = 15;

    /**
     * 每被一张表通过外键引用一次的额外评分
     */
    private static final int SCORE_FK_REFERENCED = 3;

    private SchemaSmartSampler() {}

    /**
     * 执行 Schema 智能采样。
     *
     * @param conn           数据库连接（调用方负责生命周期管理）
     * @param catalogFilter  指定 catalog / database 过滤（可为 null）
     * @param schemaFilter   指定 schema 过滤（可为 null）
     * @param keywords       关键词列表，用于相关性评分（可为 null 或空）
     * @param tablePrefix    表名前缀过滤，如 {@code order_}（可为 null）
     * @param maxTables      最多返回的表数量，需大于 0
     * @param includeIndexes 是否在结果中附带索引信息
     * @return 采样结果，包含 totalTablesFound / sampledCount / tables 等字段
     * @throws SQLException 访问数据库元数据失败时抛出
     */
    static Map<String, Object> sample(
            Connection conn,
            String catalogFilter,
            String schemaFilter,
            List<String> keywords,
            String tablePrefix,
            int maxTables,
            boolean includeIndexes
    ) throws SQLException {
        // 1. 加载所有表基本信息
        List<TableInfo> allTables = loadTableList(conn, catalogFilter, schemaFilter, tablePrefix);

        if (allTables.isEmpty()) {
            return buildEmptyResult(keywords, tablePrefix);
        }

        // 2. 规范化关键词（转小写、去空白）
        List<String> normalizedKeywords = normalizeKeywords(keywords);

        // 3. 计算表级评分（表名、表注释、前缀）
        for (TableInfo table : allTables) {
            scoreTableNameAndComment(table, normalizedKeywords, tablePrefix);
        }

        // 4. 加载列信息并叠加列级评分
        loadColumnsAndScore(conn, allTables, catalogFilter, schemaFilter, normalizedKeywords);

        // 5. 加载外键关系（{源表 -> 引用的目标表集合}）
        Map<String, Set<String>> fkRelations = loadForeignKeyRelations(conn, allTables, catalogFilter, schemaFilter);

        // 6. 对被频繁引用的表叠加 FK 评分
        applyFkReferencedScore(allTables, fkRelations);

        // 7. 按评分排序，取 Top-N，关联扩散补位
        List<TableInfo> sampledTables = selectTopTables(allTables, fkRelations, maxTables, normalizedKeywords);

        // 8. 构建最终输出
        List<Map<String, Object>> tableResults = buildTableResults(
                sampledTables, includeIndexes, conn, catalogFilter, schemaFilter
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalTablesFound", allTables.size());
        result.put("sampledCount", sampledTables.size());
        result.put("keywords", keywords != null ? keywords : Collections.emptyList());
        result.put("tablePrefix", tablePrefix != null ? tablePrefix : "");
        result.put("tables", tableResults);
        return result;
    }

    // =========================================================================
    //  私有实现方法
    // =========================================================================

    /**
     * 通过 {@link DatabaseMetaData#getTables} 加载表列表。
     * 若指定了 tablePrefix，则通过 LIKE 模式进行前缀过滤。
     */
    private static List<TableInfo> loadTableList(
            Connection conn,
            String catalogFilter,
            String schemaFilter,
            String tablePrefix
    ) throws SQLException {
        List<TableInfo> tables = new ArrayList<>();
        DatabaseMetaData meta = conn.getMetaData();
        String namePattern = (tablePrefix != null && !tablePrefix.isBlank()) ? tablePrefix + "%" : null;

        try (ResultSet rs = meta.getTables(catalogFilter, schemaFilter, namePattern, new String[]{"TABLE"})) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                if (tableName == null || tableName.isBlank()) {
                    continue;
                }
                String tableComment;
                try {
                    tableComment = rs.getString("REMARKS");
                } catch (Exception e) {
                    tableComment = null;
                }
                String catalog = safeGetString(rs, "TABLE_CAT");
                String schema = safeGetString(rs, "TABLE_SCHEM");
                tables.add(new TableInfo(tableName, tableComment, catalog, schema));
            }
        }
        return tables;
    }

    /**
     * 计算表名和表注释的关键词匹配评分，以及前缀精确匹配加分。
     *
     * @param table       待评分的表信息
     * @param keywords    已规范化的关键词列表
     * @param tablePrefix 表名前缀过滤词
     */
    private static void scoreTableNameAndComment(TableInfo table, List<String> keywords, String tablePrefix) {
        // 前缀精确匹配：即使没有关键词也应加分
        if (tablePrefix != null && !tablePrefix.isBlank()
                && table.tableName.toLowerCase(Locale.ROOT).startsWith(tablePrefix.toLowerCase(Locale.ROOT))) {
            table.score += SCORE_PREFIX_MATCH;
        }

        for (String keyword : keywords) {
            if (containsIgnoreCase(table.tableName, keyword)) {
                table.score += SCORE_TABLE_NAME_KEYWORD;
            }
            if (containsIgnoreCase(table.comment, keyword)) {
                table.score += SCORE_TABLE_COMMENT_KEYWORD;
            }
        }
    }

    /**
     * 加载每张表的列信息，并将关键词匹配到列名/列注释的得分累加到表的总评分。
     */
    private static void loadColumnsAndScore(
            Connection conn,
            List<TableInfo> tables,
            String catalogFilter,
            String schemaFilter,
            List<String> keywords
    ) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        for (TableInfo table : tables) {
            String effectiveCatalog = catalogFilter != null ? catalogFilter : table.catalog;
            String effectiveSchema = schemaFilter != null ? schemaFilter : table.schema;
            try (ResultSet rs = meta.getColumns(effectiveCatalog, effectiveSchema, table.tableName, null)) {
                while (rs.next()) {
                    String columnName = rs.getString("COLUMN_NAME");
                    if (columnName == null || columnName.isBlank()) {
                        continue;
                    }
                    String typeName = safeGetString(rs, "TYPE_NAME");
                    int columnSize = safeGetInt(rs, "COLUMN_SIZE");
                    String comment = safeGetString(rs, "REMARKS");
                    boolean nullable;
                    try {
                        nullable = rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
                    } catch (Exception e) {
                        nullable = true;
                    }
                    String defaultValue = safeGetString(rs, "COLUMN_DEF");
                    int ordinalPosition = safeGetInt(rs, "ORDINAL_POSITION");

                    ColumnInfo col = new ColumnInfo(columnName, typeName, columnSize, comment, nullable, defaultValue, ordinalPosition);
                    table.columns.add(col);

                    for (String keyword : keywords) {
                        if (containsIgnoreCase(columnName, keyword)) {
                            table.score += SCORE_COLUMN_NAME_KEYWORD;
                        }
                        if (containsIgnoreCase(comment, keyword)) {
                            table.score += SCORE_COLUMN_COMMENT_KEYWORD;
                        }
                    }
                }
            } catch (Exception e) {
                logWarn("加载表 [" + table.tableName + "] 列信息失败: " + e.getMessage());
            }
        }
    }

    /**
     * 加载各表的外键引用关系。
     *
     * @return Map：{当前表名 -> 该表通过 FK 引用的目标表名集合}
     */
    private static Map<String, Set<String>> loadForeignKeyRelations(
            Connection conn,
            List<TableInfo> tables,
            String catalogFilter,
            String schemaFilter
    ) {
        Map<String, Set<String>> relations = new LinkedHashMap<>();
        DatabaseMetaData meta;
        try {
            meta = conn.getMetaData();
        } catch (Exception e) {
            logWarn("获取 DatabaseMetaData 失败，外键关系将被忽略: " + e.getMessage());
            return relations;
        }

        for (TableInfo table : tables) {
            String effectiveCatalog = catalogFilter != null ? catalogFilter : table.catalog;
            String effectiveSchema = schemaFilter != null ? schemaFilter : table.schema;
            try (ResultSet rs = meta.getImportedKeys(effectiveCatalog, effectiveSchema, table.tableName)) {
                while (rs.next()) {
                    String pkTable = safeGetString(rs, "PKTABLE_NAME");
                    if (pkTable != null && !pkTable.isBlank()) {
                        relations.computeIfAbsent(table.tableName, k -> new LinkedHashSet<>()).add(pkTable);
                    }
                }
            } catch (Exception e) {
                logInfo("加载表 [" + table.tableName + "] 外键失败（已跳过）: " + e.getMessage());
            }
        }
        return relations;
    }

    /**
     * 统计每张表被其他表通过外键引用的次数，被引用越多代表该表越核心，给予额外加分。
     */
    private static void applyFkReferencedScore(List<TableInfo> tables, Map<String, Set<String>> fkRelations) {
        Map<String, Integer> referenceCount = new HashMap<>();
        for (Set<String> pkTables : fkRelations.values()) {
            for (String pkTable : pkTables) {
                referenceCount.merge(pkTable, 1, Integer::sum);
            }
        }
        for (TableInfo table : tables) {
            int refCount = referenceCount.getOrDefault(table.tableName, 0);
            table.score += refCount * SCORE_FK_REFERENCED;
        }
    }

    /**
     * 按评分降序选出 Top-N 张表，若有剩余名额则通过外键关联扩散补充相关表。
     *
     * <p>当关键词为空时，所有表评分均为 0（仅前缀命中有分），直接按表名字母序取前 N 张。
     */
    private static List<TableInfo> selectTopTables(
            List<TableInfo> allTables,
            Map<String, Set<String>> fkRelations,
            int maxTables,
            List<String> keywords
    ) {
        // 按评分降序排列，同分则按表名升序
        List<TableInfo> sorted = allTables.stream()
                .sorted(Comparator.comparingInt((TableInfo t) -> t.score).reversed()
                        .thenComparing(t -> t.tableName))
                .toList();

        // 取 Top-N
        Set<String> selectedNames = new LinkedHashSet<>();
        List<TableInfo> selected = new ArrayList<>();
        for (TableInfo t : sorted) {
            if (selected.size() >= maxTables) {
                break;
            }
            selectedNames.add(t.tableName);
            selected.add(t);
        }

        // 关联扩散：仅在有关键词时执行（无关键词时顺序选取即可）
        if (!keywords.isEmpty() && selected.size() < maxTables) {
            Map<String, TableInfo> tableByName = allTables.stream()
                    .collect(Collectors.toMap(t -> t.tableName, t -> t));

            Set<String> frontier = new HashSet<>(selectedNames);
            while (selected.size() < maxTables) {
                Set<String> expansion = new HashSet<>();
                for (String tableName : frontier) {
                    Set<String> related = fkRelations.getOrDefault(tableName, Collections.emptySet());
                    for (String rel : related) {
                        if (!selectedNames.contains(rel) && tableByName.containsKey(rel)) {
                            expansion.add(rel);
                        }
                    }
                }
                if (expansion.isEmpty()) {
                    break;
                }
                // 对扩散进来的表按评分排序后补充
                List<TableInfo> expansionSorted = expansion.stream()
                        .map(tableByName::get)
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparingInt((TableInfo t) -> t.score).reversed()
                                .thenComparing(t -> t.tableName))
                        .toList();
                for (TableInfo t : expansionSorted) {
                    if (selected.size() >= maxTables) {
                        break;
                    }
                    selectedNames.add(t.tableName);
                    selected.add(t);
                }
                frontier = new HashSet<>(expansion);
            }
        }

        return selected;
    }

    /**
     * 将选出的表列表组装成 JSON 友好的 Map 列表。
     */
    private static List<Map<String, Object>> buildTableResults(
            List<TableInfo> tables,
            boolean includeIndexes,
            Connection conn,
            String catalogFilter,
            String schemaFilter
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (TableInfo table : tables) {
            Map<String, Object> tableMap = new LinkedHashMap<>();
            tableMap.put("tableName", table.tableName);
            tableMap.put("comment", table.comment != null ? table.comment : "");
            tableMap.put("relevanceScore", table.score);

            // 按照 ordinalPosition 排序列
            List<Map<String, Object>> columns = table.columns.stream()
                    .sorted(Comparator.comparingInt(c -> c.ordinalPosition))
                    .map(col -> {
                        Map<String, Object> colMap = new LinkedHashMap<>();
                        colMap.put("name", col.columnName);
                        colMap.put("type", col.typeName != null ? col.typeName : "UNKNOWN");
                        colMap.put("size", col.columnSize);
                        colMap.put("nullable", col.nullable);
                        colMap.put("defaultValue", col.defaultValue != null ? col.defaultValue : "");
                        colMap.put("comment", col.comment != null ? col.comment : "");
                        return colMap;
                    })
                    .collect(Collectors.toList());

            tableMap.put("columns", columns);
            tableMap.put("columnCount", columns.size());

            if (includeIndexes) {
                tableMap.put("indexes", loadIndexes(conn, table, catalogFilter, schemaFilter));
            }

            result.add(tableMap);
        }
        return result;
    }

    /**
     * 加载指定表的索引信息（按索引名聚合列）。
     */
    private static List<Map<String, Object>> loadIndexes(
            Connection conn,
            TableInfo table,
            String catalogFilter,
            String schemaFilter
    ) {
        List<Map<String, Object>> indexes = new ArrayList<>();
        try {
            DatabaseMetaData meta = conn.getMetaData();
            String effectiveCatalog = catalogFilter != null ? catalogFilter : table.catalog;
            String effectiveSchema = schemaFilter != null ? schemaFilter : table.schema;
            try (ResultSet rs = meta.getIndexInfo(effectiveCatalog, effectiveSchema, table.tableName, false, true)) {
                Map<String, Map<String, Object>> indexMap = new LinkedHashMap<>();
                while (rs.next()) {
                    String indexName = safeGetString(rs, "INDEX_NAME");
                    if (indexName == null || indexName.isBlank()) {
                        continue;
                    }
                    Map<String, Object> idx = indexMap.computeIfAbsent(indexName, k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("indexName", k);
                        m.put("unique", false);
                        m.put("columns", new ArrayList<String>());
                        return m;
                    });
                    try {
                        idx.put("unique", !rs.getBoolean("NON_UNIQUE"));
                    } catch (Exception ignored) {
                    }
                    String columnName = safeGetString(rs, "COLUMN_NAME");
                    if (columnName != null) {
                        @SuppressWarnings("unchecked")
                        List<String> cols = (List<String>) idx.get("columns");
                        cols.add(columnName);
                    }
                }
                indexes.addAll(indexMap.values());
            }
        } catch (Exception e) {
            logInfo("加载表 [" + table.tableName + "] 索引失败（已跳过）: " + e.getMessage());
        }
        return indexes;
    }

    /**
     * 规范化关键词列表：转小写并去除空白项。
     */
    private static List<String> normalizeKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return Collections.emptyList();
        }
        return keywords.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(k -> k.trim().toLowerCase(Locale.ROOT))
                .toList();
    }

    /**
     * 不区分大小写地判断目标字符串是否包含关键词。
     */
    private static boolean containsIgnoreCase(String target, String keyword) {
        if (target == null || keyword == null) {
            return false;
        }
        return target.toLowerCase(Locale.ROOT).contains(keyword);
    }

    /**
     * 安全读取 ResultSet 中的字符串列，忽略列不存在的异常。
     */
    private static String safeGetString(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 安全读取 ResultSet 中的整型列，异常时返回 0。
     */
    private static int safeGetInt(ResultSet rs, String column) {
        try {
            return rs.getInt(column);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 构建空结果（无表时直接返回）。
     */
    private static Map<String, Object> buildEmptyResult(List<String> keywords, String tablePrefix) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalTablesFound", 0);
        result.put("sampledCount", 0);
        result.put("keywords", keywords != null ? keywords : Collections.emptyList());
        result.put("tablePrefix", tablePrefix != null ? tablePrefix : "");
        result.put("tables", Collections.emptyList());
        return result;
    }

    // =========================================================================
    //  日志辅助方法（统一使用 McpRuntimeLogService，避免直接依赖 IntelliJ Logger）
    // =========================================================================

    private static McpRuntimeLogService logService() {
        return ApplicationManager.getApplication() == null
                ? null
                : ApplicationManager.getApplication().getService(McpRuntimeLogService.class);
    }

    private static void logWarn(String message) {
        McpRuntimeLogService service = logService();
        if (service != null) {
            service.warn("sampler", message);
        }
    }

    private static void logInfo(String message) {
        McpRuntimeLogService service = logService();
        if (service != null) {
            service.info("sampler", message);
        }
    }

    // =========================================================================
    //  内部数据模型
    // =========================================================================

    /**
     * 表的元信息，包含评分和列列表。
     */
    static final class TableInfo {
        /**
         * 表名
         */
        final String tableName;
        /**
         * 表注释
         */
        final String comment;
        /**
         * 所属 catalog
         */
        final String catalog;
        /**
         * 所属 schema
         */
        final String schema;
        /**
         * 相关性综合评分（越高越优先被采样）
         */
        int score;
        /**
         * 列信息列表
         */
        final List<ColumnInfo> columns = new ArrayList<>();

        TableInfo(String tableName, String comment, String catalog, String schema) {
            this.tableName = tableName;
            this.comment = comment;
            this.catalog = catalog;
            this.schema = schema;
        }
    }

    /**
     * 列的元信息。
     */
    static final class ColumnInfo {
        /**
         * 列名
         */
        final String columnName;
        /**
         * 数据类型名称
         */
        final String typeName;
        /**
         * 列的精度/长度
         */
        final int columnSize;
        /**
         * 列注释
         */
        final String comment;
        /**
         * 是否允许为 NULL
         */
        final boolean nullable;
        /**
         * 默认值
         */
        final String defaultValue;
        /**
         * 在表中的排列位置（1-based）
         */
        final int ordinalPosition;

        ColumnInfo(String columnName, String typeName, int columnSize, String comment,
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
}
