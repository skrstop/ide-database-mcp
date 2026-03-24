package io.skrstop.ide.databasemcp.settings;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

public final class DatabaseMcpMessages {
    private static final String BUNDLE_NAME = "messages.DatabaseMcpBundle";
    private static final ResourceBundle.Control NO_FALLBACK_CONTROL =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT);

    private DatabaseMcpMessages() {
    }

    public static String message(String key) {
        return message(McpSettingsState.getInstance().getUiLanguageEffective(), key);
    }

    public static String message(String key, Object... args) {
        return message(McpSettingsState.getInstance().getUiLanguageEffective(), key, args);
    }

    public static String message(McpSettingsState.UiLanguage language, String key) {
        return message(language, key, new Object[0]);
    }

    public static String message(McpSettingsState.UiLanguage language, String key, Object... args) {
        Locale locale = toLocale(language);
        String template = key;
        try {
            ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE_NAME, locale, NO_FALLBACK_CONTROL);
            if (bundle.containsKey(key)) {
                template = bundle.getString(key);
                return format(locale, template, args);
            }
        } catch (MissingResourceException ignored) {
            // Use fallback below.
        }

        try {
            ResourceBundle fallback = ResourceBundle.getBundle(BUNDLE_NAME, Locale.US, NO_FALLBACK_CONTROL);
            if (fallback.containsKey(key)) {
                template = fallback.getString(key);
                return format(Locale.US, template, args);
            }
        } catch (MissingResourceException ignored) {
            // Fall through to key.
        }

        return format(locale, template, args);
    }

    private static String format(Locale locale, String template, Object... args) {
        if (args == null || args.length == 0) {
            return template;
        }
        return String.format(locale, template, args);
    }

    private static Locale toLocale(McpSettingsState.UiLanguage language) {
        if (language == McpSettingsState.UiLanguage.ZH_CN) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        return Locale.US;
    }
}

