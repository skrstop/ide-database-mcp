package com.skrstop.ide.databasemcp.service;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.skrstop.ide.databasemcp.mcp.McpToolDefinitions;
import com.skrstop.ide.databasemcp.settings.CustomToolDefinition;
import com.skrstop.ide.databasemcp.settings.McpSettingsState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service(Service.Level.APP)
public final class McpMethodMetricsService {
    private static final List<String> KNOWN_METHOD_KEYS = List.of(
            "tool:" + McpToolDefinitions.TOOL_LIST_DATASOURCES,
            "tool:" + McpToolDefinitions.TOOL_LIST_DATABASES,
            "tool:" + McpToolDefinitions.TOOL_LIST_TABLE_SCHEMA,
            "tool:" + McpToolDefinitions.TOOL_EXECUTE_SQL_QUERY,
            "tool:" + McpToolDefinitions.TOOL_EXECUTE_SQL_DML,
            "tool:" + McpToolDefinitions.TOOL_EXECUTE_SQL_DDL,
            "tool:" + McpToolDefinitions.TOOL_EXECUTE_NOSQL_QUERY,
            "tool:" + McpToolDefinitions.TOOL_EXECUTE_NOSQL_WRITE_DELETE,
            "rpc:unknown",
            "rpc:initialize",
            "rpc:tools/list",
            "rpc:tools/call"
    );

    // 用于 snapshot() 中高效过滤，避免 List.removeAll 的 O(n*m) 复杂度
    private static final Set<String> KNOWN_METHOD_KEY_SET = new HashSet<>(KNOWN_METHOD_KEYS);

    private final ConcurrentHashMap<String, Stats> metrics = new ConcurrentHashMap<>();

    public static McpMethodMetricsService getInstance() {
        return ApplicationManager.getApplication().getService(McpMethodMetricsService.class);
    }

    public long record(String methodKey, long elapsedNanos) {
        Stats stats = metrics.computeIfAbsent(methodKey, ignored -> new Stats());
        long count = stats.count.incrementAndGet();
        if (elapsedNanos > 0) {
            stats.totalNanos.addAndGet(elapsedNanos);
        }
        return count;
    }

    public Map<String, MethodMetric> snapshot() {
        Map<String, MethodMetric> result = new LinkedHashMap<>();

        for (String knownKey : KNOWN_METHOD_KEYS) {
            result.put(knownKey, currentMetric(knownKey));
        }

        List<String> extras = new ArrayList<>(metrics.keySet());
        extras.removeAll(KNOWN_METHOD_KEY_SET);
        extras.sort(Comparator.naturalOrder());
        for (String extra : extras) {
            result.put(extra, currentMetric(extra));
        }

        return result;
    }

    public void clear() {
        metrics.clear();
    }

    /**
     * 自定义 tool 在统计表中使用的 key 前缀，用于在 {@link #syncCustomTools()} 中过滤需要清理的条目。
     */
    private static final String CUSTOM_TOOL_KEY_PREFIX = "tool:custom_";

    /**
     * 将统计表与「当前生效的自定义 tool」对齐：
     * <ul>
     *   <li>已删除 / 已禁用 的自定义 tool 条目从 metrics 中移除，使其立刻从统计表消失；</li>
     *   <li>新增 / 启用 的自定义 tool 预置 0 计数 {@link Stats}，使其立刻显示在统计表（即便尚未被调用）。</li>
     * </ul>
     * <p>调用方：{@code CustomToolsConfigurable#apply()} 在持久化新配置后触发。</p>
     */
    public void syncCustomTools() {
        Set<String> activeKeys = new HashSet<>();
        for (CustomToolDefinition def : McpSettingsState.getInstance().getCustomToolsEffective()) {
            if (def != null && def.enabled && def.name != null && !def.name.isBlank()) {
                activeKeys.add("tool:" + def.name.trim());
            }
        }
        // 1. 移除已不再生效的自定义 tool 计数条目
        Iterator<Map.Entry<String, Stats>> it = metrics.entrySet().iterator();
        while (it.hasNext()) {
            String key = it.next().getKey();
            if (key.startsWith(CUSTOM_TOOL_KEY_PREFIX) && !activeKeys.contains(key)) {
                it.remove();
            }
        }
        // 2. 为生效的自定义 tool 预置 0 计数条目，使其立刻出现在统计表中
        for (String key : activeKeys) {
            metrics.computeIfAbsent(key, ignored -> new Stats());
        }
    }

    private MethodMetric currentMetric(String key) {
        Stats value = metrics.get(key);
        if (value == null) {
            return new MethodMetric(0, 0L);
        }
        return new MethodMetric(value.count.get(), value.totalNanos.get());
    }

    private static final class Stats {
        private final AtomicInteger count = new AtomicInteger();
        private final AtomicLong totalNanos = new AtomicLong();
    }

    public record MethodMetric(int count, long totalNanos) {
        public double averageMillis() {
            if (count <= 0) {
                return 0d;
            }
            return (totalNanos / 1_000_000d) / count;
        }

        public double totalMillis() {
            return totalNanos / 1_000_000d;
        }
    }
}

