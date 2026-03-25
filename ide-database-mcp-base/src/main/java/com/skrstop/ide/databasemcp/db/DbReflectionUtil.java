package com.skrstop.ide.databasemcp.db;

import com.intellij.openapi.diagnostic.Logger;

import java.lang.reflect.Method;

final class DbReflectionUtil {
    private DbReflectionUtil() {
    }

    static String invokeString(Logger log, Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            return value == null ? null : String.valueOf(value);
        } catch (Exception ex) {
            if (log != null) {
                log.debug("Failed invoke " + target.getClass().getName() + "#" + methodName + ": " + ex.getMessage());
            }
            return null;
        }
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

