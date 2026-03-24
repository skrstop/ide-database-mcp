package io.skrstop.ide.databasemcp.mcp;

import java.util.Locale;

public final class ReadOnlySqlGuard {
    private static final String[] READ_ONLY_PREFIXES = {
            "select", "with", "show", "describe", "desc", "explain"
    };
    private static final String[] DML_PREFIXES = {
            "insert", "update", "delete", "merge", "replace"
    };
    private static final String[] DDL_PREFIXES = {
            "create", "alter", "drop", "truncate", "rename", "comment"
    };

    private ReadOnlySqlGuard() {
    }

    public static boolean isReadOnlySql(String sql) {
        return startsWithAny(normalize(sql), READ_ONLY_PREFIXES);
    }

    public static boolean isDmlSql(String sql) {
        return startsWithAny(normalize(sql), DML_PREFIXES);
    }

    public static boolean isDdlSql(String sql) {
        return startsWithAny(normalize(sql), DDL_PREFIXES);
    }

    private static String normalize(String sql) {
        if (sql == null) {
            return "";
        }
        return sql.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean startsWithAny(String sql, String[] prefixes) {
        if (sql.isEmpty()) {
            return false;
        }
        for (String prefix : prefixes) {
            if (sql.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
