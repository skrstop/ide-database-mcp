package com.skrstop.ide.databasemcp.service;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.skrstop.ide.databasemcp.mcp.McpToolDefinitions;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service(Service.Level.APP)
public final class McpMethodMetricsService {
    private static final List<String> KNOWN_METHOD_KEYS = List.of(
            "tool:" + McpToolDefinitions.TOOL_LIST_DATABASES,
            "tool:" + McpToolDefinitions.TOOL_LIST_DATABASES,
            "tool:" + McpToolDefinitions.TOOL_EXECUTE_SQL_QUERY,
            "tool:" + McpToolDefinitions.TOOL_EXECUTE_SQL_DML,
            "tool:" + McpToolDefinitions.TOOL_EXECUTE_SQL_DDL,
            "rpc:unknown",
            "rpc:initialize",
            "rpc:tools/list",
            "rpc:tools/call"
    );

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

    public int incrementUnbounded(String methodKey) {
        Stats stats = metrics.computeIfAbsent(methodKey, ignored -> new Stats());
        return stats.count.incrementAndGet();
    }

    public Map<String, MethodMetric> snapshot() {
        Map<String, MethodMetric> result = new LinkedHashMap<>();

        for (String knownKey : KNOWN_METHOD_KEYS) {
            result.put(knownKey, currentMetric(knownKey));
        }

        List<String> extras = new ArrayList<>(metrics.keySet());
        extras.removeAll(KNOWN_METHOD_KEYS);
        extras.sort(Comparator.naturalOrder());
        for (String extra : extras) {
            result.put(extra, currentMetric(extra));
        }

        return result;
    }

    public void clear() {
        metrics.clear();
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

