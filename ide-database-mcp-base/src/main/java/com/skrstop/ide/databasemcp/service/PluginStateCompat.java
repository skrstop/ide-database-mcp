package com.skrstop.ide.databasemcp.service;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;

import java.lang.reflect.Method;

/**
 * Isolates optional PluginManagerCore APIs to keep runtime compatibility with older IDE builds.
 */
public final class PluginStateCompat {
    private static final Method IS_LOADED_METHOD = resolveIsLoadedMethod();

    private PluginStateCompat() {
    }

    public static boolean isPluginUnavailable(PluginId pluginId) {
        if (PluginManagerCore.getPlugin(pluginId) == null) {
            return true;
        }
        if (PluginManagerCore.isDisabled(pluginId)) {
            return true;
        }
        return !isLoadedCompat(pluginId);
    }

    private static boolean isLoadedCompat(PluginId pluginId) {
        if (IS_LOADED_METHOD == null) {
            // Old platform versions do not expose isLoaded(PluginId); enabled+installed is the best fallback.
            return true;
        }

        try {
            Object loaded = IS_LOADED_METHOD.invoke(null, pluginId);
            return loaded instanceof Boolean && (Boolean) loaded;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Fail open for compatibility: rely on installed + not disabled checks.
            return true;
        }
    }

    private static Method resolveIsLoadedMethod() {
        for (Method method : PluginManagerCore.class.getMethods()) {
            if (!"isLoaded".equals(method.getName())) {
                continue;
            }
            if (method.getParameterCount() != 1 || method.getParameterTypes()[0] != PluginId.class) {
                continue;
            }
            if (method.getReturnType() != boolean.class) {
                continue;
            }
            return method;
        }
        return null;
    }
}

