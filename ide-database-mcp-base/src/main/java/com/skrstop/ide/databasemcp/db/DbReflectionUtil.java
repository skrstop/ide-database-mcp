package com.skrstop.ide.databasemcp.db;

import com.intellij.openapi.application.ApplicationManager;
import com.skrstop.ide.databasemcp.service.McpRuntimeLogService;

import java.lang.reflect.Method;

final class DbReflectionUtil {
    private DbReflectionUtil() {
    }

    /**
     * 通过反射调用目标对象的无参方法，返回字符串结果。
     * 调用失败时静默返回 null，并通过 McpRuntimeLogService 记录调试信息。
     */
    static String invokeString(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            return value == null ? null : String.valueOf(value);
        } catch (Exception ex) {
            McpRuntimeLogService service = logService();
            if (service != null) {
                service.info("reflection",
                        "Failed invoke " + target.getClass().getName() + "#" + methodName + ": " + ex.getMessage());
            }
            return null;
        }
    }

    private static McpRuntimeLogService logService() {
        return ApplicationManager.getApplication() == null
                ? null
                : ApplicationManager.getApplication().getService(McpRuntimeLogService.class);
    }

    static Boolean invokeBoolean(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            if (value instanceof Boolean b) {
                return b;
            }
            return value == null ? null : Boolean.parseBoolean(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }
}

