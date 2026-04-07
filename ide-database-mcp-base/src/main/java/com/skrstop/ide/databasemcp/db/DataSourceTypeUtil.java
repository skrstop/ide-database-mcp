package com.skrstop.ide.databasemcp.db;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class DataSourceTypeUtil {
    private static final String CUSTOM_DATA_SOURCE_TYPE = "Custom";
    private static final Set<String> DRIVER_SEGMENT_BLACKLIST = Set.of(
            "com", "org", "net", "io", "www", "jdbc", "drivers", "driver", "database", "thin",
            "data", "datasource", "access", "connection", "local", "localhost", "server", "client", "official"
    );

    private DataSourceTypeUtil() {
    }

    /**
     * 推断数据源类型（三参数版本，按 databaseApi → jdbcUrl → jdbcDriverClass 优先级）。
     *
     * @param dataSource  IntelliJ 数据源委托对象（可为 null）
     * @param url         JDBC URL
     * @param driverClass JDBC 驱动类全限定名
     * @return 推断出的数据库类型名称，无法推断时返回 {@code "Custom"}
     */
    static String inferDataSourceType(Object dataSource, String url, String driverClass) {
        // 优先级1: 通过 IntelliJ 数据库 API 获取产品名称
        String type = inferTypeViaApi(dataSource);
        if (type != null && !type.isBlank()) {
            return type;
        }
        // 优先级2 & 3: 通过 URL / 驱动类名推断（原有逻辑）
        return inferDataSourceType(url, driverClass);
    }

    /**
     * 推断数据源类型（两参数兜底版本，按 jdbcUrl → jdbcDriverClass 优先级）。
     *
     * @param url         JDBC URL
     * @param driverClass JDBC 驱动类全限定名
     * @return 推断出的数据库类型名称，无法推断时返回 {@code "Custom"}
     */
    static String inferDataSourceType(String url, String driverClass) {
        String vendorToken = pickBestVendorToken(url, driverClass);
        String vendor = formatVendorName(vendorToken);
        return vendor == null || vendor.isBlank() ? CUSTOM_DATA_SOURCE_TYPE : vendor;
    }

    /**
     * 通过反射从 IntelliJ 数据库 API 获取数据库产品类型名称。
     *
     * <p>依次尝试 {@code getDbmsInfo()} / {@code getDatabaseInfo()} 子对象上的
     * {@code getProduct()} / {@code getDatabaseProductName()} 等方法。
     */
    private static String inferTypeViaApi(Object dataSource) {
        if (dataSource == null) {
            return null;
        }
        for (String infoMethod : new String[]{"getDbmsInfo", "getDatabaseInfo", "getServerInfo"}) {
            try {
                Method m = dataSource.getClass().getMethod(infoMethod);
                Object info = m.invoke(dataSource);
                if (info == null) {
                    continue;
                }
                for (String productMethod : new String[]{"getProduct", "getDatabaseProductName", "getProductName"}) {
                    try {
                        Object val = info.getClass().getMethod(productMethod).invoke(info);
                        if (val instanceof String s && !s.isBlank()) {
                            // 过滤掉明显是 "Unknown" 或空语义的返回值
                            String trimmed = s.trim();
                            if (!trimmed.equalsIgnoreCase("unknown") && !trimmed.equalsIgnoreCase("other")) {
                                return trimmed;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 按优先级解析数据源的数据库版本信息。
     *
     * <p>获取优先级：
     * <ol>
     *   <li><b>databaseApi</b>：通过反射调用 IntelliJ 数据库 API 获取已缓存的服务端版本</li>
     *   <li><b>jdbcUrl</b>：从 JDBC URL 的查询参数中解析版本提示（大多数 URL 无此信息）</li>
     *   <li><b>jdbcDriverClass</b>：从驱动类名推断大致版本范围</li>
     * </ol>
     *
     * @param dataSource  IntelliJ 数据源委托对象（可为 null）
     * @param url         JDBC URL
     * @param driverClass JDBC 驱动类全限定名
     * @return 版本字符串；无法获取时返回 {@code "unknown"}
     */
    static String resolveDataSourceVersion(Object dataSource, String url, String driverClass) {
        // 优先级1: 通过 IntelliJ 数据库 API 获取已存储的服务端版本（无需建立新连接）
        String version = resolveVersionViaApi(dataSource);
        if (version != null && !version.isBlank()) {
            return version.trim();
        }
        // 优先级2: 从 JDBC URL 解析版本参数（少数数据库在 URL 中携带版本信息）
        version = resolveVersionFromUrl(url);
        if (version != null && !version.isBlank()) {
            return version.trim();
        }
        // 优先级3: 从驱动类名推断大致版本
        version = resolveVersionFromDriverClass(driverClass);
        if (version != null && !version.isBlank()) {
            return version.trim();
        }
        return "unknown";
    }

    /**
     * 通过反射从 IntelliJ 数据库 API 获取已存储的数据库服务端版本。
     *
     * <p>依次尝试以下反射路径：
     * <ul>
     *   <li>{@code getDbmsInfo()} 子对象上的 {@code getVersion()} 等方法</li>
     *   <li>数据源对象本身的 {@code getDatabaseVersion()} / {@code getServerVersion()} 方法</li>
     * </ul>
     */
    private static String resolveVersionViaApi(Object dataSource) {
        if (dataSource == null) {
            return null;
        }
        // 路径1: 尝试从 dbmsInfo 子对象获取版本
        for (String infoMethod : new String[]{"getDbmsInfo", "getDatabaseInfo", "getServerInfo"}) {
            try {
                Object info = dataSource.getClass().getMethod(infoMethod).invoke(dataSource);
                if (info == null) {
                    continue;
                }
                for (String versionMethod : new String[]{"getVersion", "getDatabaseProductVersion", "getServerVersion", "getVersionString", "getProductVersion"}) {
                    try {
                        Object val = info.getClass().getMethod(versionMethod).invoke(info);
                        if (val instanceof String s && !s.isBlank()) {
                            return s;
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
        // 路径2: 尝试数据源对象本身的版本方法
        for (String versionMethod : new String[]{"getDatabaseVersion", "getServerVersion"}) {
            try {
                Object val = dataSource.getClass().getMethod(versionMethod).invoke(dataSource);
                if (val == null) {
                    continue;
                }
                if (val instanceof String s && !s.isBlank()) {
                    return s;
                }
                // 尝试从版本对象中提取字符串（如 DatabaseVersion 对象）
                for (String strMethod : new String[]{"getVersionString", "toString"}) {
                    try {
                        Object str = val.getClass().getMethod(strMethod).invoke(val);
                        if (str instanceof String s && !s.isBlank()
                                && !s.startsWith(val.getClass().getPackageName())) {
                            return s;
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 从 JDBC URL 的查询参数中尝试解析版本信息（最佳努力，大多数标准 JDBC URL 无此信息）。
     */
    private static String resolveVersionFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        // 检查 URL 中是否有 version= 参数（少数私有协议支持）
        int versionIdx = url.toLowerCase(Locale.ROOT).indexOf("version=");
        if (versionIdx >= 0) {
            String after = url.substring(versionIdx + 8);
            int end = after.indexOf('&');
            String value = end >= 0 ? after.substring(0, end) : after;
            if (!value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * 从 JDBC 驱动类名推断数据库大致版本范围（仅供参考，非精确服务端版本）。
     *
     * <p>已知映射：
     * <ul>
     *   <li>{@code com.mysql.cj.jdbc.Driver} → MySQL 8.x+（Connector/J 8.x 系列驱动）</li>
     *   <li>{@code com.mysql.jdbc.Driver} → MySQL 5.x（Connector/J 5.x 旧版驱动）</li>
     * </ul>
     */
    private static String resolveVersionFromDriverClass(String driverClass) {
        if (driverClass == null || driverClass.isBlank()) {
            return null;
        }
        String lower = driverClass.toLowerCase(Locale.ROOT);
        if (lower.contains("mysql") && lower.contains(".cj.")) {
            return "8.x+ (inferred from driver)";
        }
        if (lower.startsWith("com.mysql.jdbc.")) {
            return "5.x (inferred from driver)";
        }
        return null;
    }

    private static String pickBestVendorToken(String url, String driverClass) {
        Candidate best = null;
        List<String> urlTokens = extractUrlVendorTokens(url);
        for (int i = 0; i < urlTokens.size(); i++) {
            String token = urlTokens.get(i);
            int score = scoreToken(token, true, i);
            if (best == null || score > best.score) {
                best = new Candidate(token, score);
            }
        }

        List<String> driverTokens = extractDriverVendorTokens(driverClass);
        for (int i = 0; i < driverTokens.size(); i++) {
            String token = driverTokens.get(i);
            int score = scoreToken(token, false, i);
            if (best == null || score > best.score) {
                best = new Candidate(token, score);
            }
        }

        if (best == null || best.score <= 0) {
            return null;
        }
        return best.token;
    }

    private static List<String> extractUrlVendorTokens(String url) {
        if (url == null || url.isBlank()) {
            return List.of();
        }

        String normalized = url.toLowerCase(Locale.ROOT).trim();
        if (normalized.isBlank()) {
            return List.of();
        }

        String protocolPart = normalized;
        int queryStart = indexOfAny(protocolPart, '?', ';');
        if (queryStart >= 0) {
            protocolPart = protocolPart.substring(0, queryStart);
        }

        int slashSlash = protocolPart.indexOf("://");
        if (slashSlash >= 0) {
            protocolPart = protocolPart.substring(0, slashSlash);
        }

        int at = protocolPart.indexOf('@');
        if (at >= 0) {
            protocolPart = protocolPart.substring(0, at);
        }

        return tokenizeProtocolSection(protocolPart);
    }

    private static List<String> extractDriverVendorTokens(String driverClass) {
        if (driverClass == null || driverClass.isBlank()) {
            return List.of();
        }
        String normalized = driverClass.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        for (String segment : normalized.split("[^a-z0-9]+")) {
            String cleaned = normalizeToken(segment);
            if (cleaned != null) {
                tokens.add(cleaned);
            }
        }
        return tokens;
    }

    private static List<String> tokenizeProtocolSection(String protocolPart) {
        List<String> tokens = new ArrayList<>();
        if (protocolPart == null || protocolPart.isBlank()) {
            return tokens;
        }
        for (String segment : protocolPart.split("[:/+]+")) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            String cleaned = normalizeToken(segment);
            if (cleaned != null) {
                tokens.add(cleaned);
            }
        }
        return tokens;
    }

    private static String normalizeToken(String token) {
        if (token == null) {
            return null;
        }
        String cleaned = token.trim().toLowerCase(Locale.ROOT);
        if (cleaned.isBlank()) {
            return null;
        }
        while (cleaned.startsWith("jdbc")) {
            cleaned = cleaned.substring(4);
        }
        while (cleaned.endsWith("driver")) {
            cleaned = cleaned.substring(0, cleaned.length() - 6);
        }
        cleaned = cleaned.replaceAll("^[^a-z0-9]+|[^a-z0-9]+$", "");
        if (cleaned.isBlank()) {
            return null;
        }
        if (isSkippableDriverSegment(cleaned)) {
            return null;
        }
        if (cleaned.length() > 40) {
            return null;
        }
        return cleaned;
    }

    private static int scoreToken(String token, boolean fromUrl, int orderIndex) {
        if (token == null || token.isBlank() || isSkippableDriverSegment(token)) {
            return Integer.MIN_VALUE;
        }
        int score = fromUrl ? 120 : 90;

        int positionBonus = Math.max(0, 25 - Math.min(orderIndex, 25));
        score += positionBonus;

        if (token.length() >= 3 && token.length() <= 18) {
            score += 12;
        }
        if (token.chars().anyMatch(Character::isDigit)) {
            score += 4;
        }
        if (token.contains("sql")) {
            score += 10;
        }
        if (token.contains("db")) {
            score += 8;
        }
        if (token.startsWith("x") && token.length() == 1) {
            score -= 50;
        }
        return score;
    }

    private static int indexOfAny(String value, char... chars) {
        int result = -1;
        for (char c : chars) {
            int idx = value.indexOf(c);
            if (idx >= 0 && (result < 0 || idx < result)) {
                result = idx;
            }
        }
        return result;
    }

    private static boolean isSkippableDriverSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            return true;
        }
        return DRIVER_SEGMENT_BLACKLIST.contains(segment);
    }

    private static String formatVendorName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.replaceAll("[^a-z0-9]+", " ").trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        String[] words = normalized.split("\\s+");
        List<String> formatted = new ArrayList<>();
        for (String word : words) {
            String part = formatVendorWord(word);
            if (part != null) {
                formatted.add(part);
            }
        }
        if (formatted.isEmpty()) {
            return null;
        }
        return String.join(" ", formatted);
    }

    private static String formatVendorWord(String word) {
        if (word == null || word.isBlank()) {
            return null;
        }
        if (word.startsWith("sql") && word.length() > 3) {
            return "SQL" + capitalize(word.substring(3));
        }
        if (word.endsWith("sql") && word.length() > 3) {
            String prefix = word.substring(0, word.length() - 3);
            return capitalize(prefix) + "SQL";
        }
        if (word.endsWith("db") && word.length() > 2) {
            String prefix = word.substring(0, word.length() - 2);
            return capitalize(prefix) + "DB";
        }
        if (word.chars().anyMatch(Character::isDigit)) {
            return uppercaseLettersKeepDigits(word);
        }
        return capitalize(word);
    }

    private static String uppercaseLettersKeepDigits(String word) {
        StringBuilder sb = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); i++) {
            char ch = word.charAt(i);
            if (Character.isLetter(ch)) {
                sb.append(Character.toUpperCase(ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String capitalize(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        if (input.length() == 1) {
            return input.toUpperCase(Locale.ROOT);
        }
        return input.substring(0, 1).toUpperCase(Locale.ROOT) + input.substring(1).toLowerCase(Locale.ROOT);
    }

    private record Candidate(String token, int score) {
    }
}

