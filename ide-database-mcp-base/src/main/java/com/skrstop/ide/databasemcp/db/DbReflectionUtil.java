package com.skrstop.ide.databasemcp.db;

import com.skrstop.ide.databasemcp.service.McpRuntimeLogService;

import java.lang.reflect.Field;
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
            McpRuntimeLogService.logInfo("reflection",
                    "Failed invoke " + target.getClass().getName() + "#" + methodName + ": " + ex.getMessage());
            return null;
        }
    }

    /**
     * 遍历方法名列表，依次反射调用，返回第一个非空字符串结果。
     *
     * @param target  目标对象（可为 null）
     * @param methods 方法名列表，按优先级排列
     * @return 第一个非空结果（已 trim），全部失败时返回 null
     */
    public static String invokeFirstNonBlankMethod(Object target, String... methods) {
        if (target == null) {
            return null;
        }
        for (String method : methods) {
            String val = DbReflectionUtil.invokeString(target, method);
            if (val != null && !val.isBlank()) {
                return val.trim();
            }
        }
        return null;
    }

    /**
     * 通过反射直接读取对象上指定名称的字段值，沿类层次向上逐级查找（含父类）。
     *
     * @param target    目标对象（非 null）
     * @param fieldName 字段名
     * @return 字段值，未找到或访问异常时返回 null
     */
    static Object invokeField(Object target, String fieldName) {
        Class<?> clazz = target.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                // 当前类无此字段，继续向父类查找
                clazz = clazz.getSuperclass();
            } catch (Exception ignored) {
                break;
            }
        }
        return null;
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

