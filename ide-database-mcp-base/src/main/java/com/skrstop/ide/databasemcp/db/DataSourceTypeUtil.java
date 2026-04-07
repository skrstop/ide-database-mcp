package com.skrstop.ide.databasemcp.db;

import com.skrstop.ide.databasemcp.entity.Candidate;
import com.skrstop.ide.databasemcp.entity.DbNameAndVersion;

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

    // ===== 公开推断方法 =====

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
        DbNameAndVersion nameVersion = resolveNameVersionObject(dataSource);
        if (nameVersion != null && nameVersion.getName() != null && !nameVersion.getName().isBlank()
                && !nameVersion.getName().equalsIgnoreCase("unknown")
                && !nameVersion.getName().equalsIgnoreCase("other")) {
            return nameVersion.getName();
        }
        // 优先级2 & 3: 通过 URL / 驱动类名推断
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

    // ===== 数据源属性解析 =====

    /**
     * 按优先级解析数据源的显示名称。
     *
     * <p>依次反射尝试 {@code getName()} / {@code getDisplayName()} / {@code getLabel()} 等方法。
     *
     * @param dataSource IntelliJ 数据源委托对象（可为 null）
     * @return 数据源名称字符串，无法获取时返回 null
     */
    static String resolveDataSourceName(Object dataSource) {
        return DbReflectionUtil.invokeFirstNonBlankMethod(dataSource, "getName", "getDisplayName", "getLabel", "getAlias");
    }

    /**
     * 按优先级解析数据源的 JDBC 驱动类全限定名。
     *
     * <p>依次反射尝试 {@code getDriverClass()} / {@code getDriver()} / {@code getDriverClassName()} 等方法。
     *
     * @param dataSource IntelliJ 数据源委托对象（可为 null）
     * @return 驱动类名字符串，无法获取时返回 null
     */
    static String resolveDataSourceDriverClass(Object dataSource) {
        return DbReflectionUtil.invokeFirstNonBlankMethod(dataSource, "getDriverClass", "getDriver", "getJdbcDriver", "getDriverClassName");
    }

    /**
     * 按优先级解析数据源的 username。
     *
     * <p>依次反射尝试 {@code getUser()} / {@code getUserName()}
     *
     * @param dataSource IntelliJ 数据源委托对象（可为 null）
     * @return db connet username
     */
    static String resolveDataSourceUserName(Object dataSource) {
        return DbReflectionUtil.invokeFirstNonBlankMethod(dataSource, "getUser", "getUsername");
    }

    /**
     * 按优先级解析数据源的连接 URL。
     *
     * <p>获取优先级：
     * <ol>
     *   <li><b>直接获取</b>：反射调用 {@code getUrl()} / {@code getJdbcUrl()} 等方法</li>
     *   <li><b>dbmsInfo 子对象</b>：尝试 {@code getDbmsInfo().getUrl()} 路径（部分驱动支持）</li>
     * </ol>
     *
     * @param dataSource IntelliJ 数据源委托对象（可为 null）
     * @return JDBC URL 字符串，无法获取时返回 null
     */
    static String resolveDataSourceUrl(Object dataSource) {
        if (dataSource == null) {
            return null;
        }
        // 优先级1: 直接从数据源对象获取 URL
        String url = DbReflectionUtil.invokeFirstNonBlankMethod(dataSource, "getUrl", "getJdbcUrl", "getConnectionUrl", "getConnectionString");
        if (url != null) {
            return url;
        }
        // 优先级2: 通过 dbmsInfo 子对象获取 URL
        for (String infoMethod : new String[]{"getDbmsInfo", "getDatabaseInfo"}) {
            String infoUrl = resolveUrlFromSubObject(dataSource, infoMethod);
            if (infoUrl != null) {
                return infoUrl;
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
        // 优先级1: 通过 IntelliJ 数据库 API 获取已存储的服务端版本
        DbNameAndVersion nameVersion = resolveNameVersionObject(dataSource);
        if (nameVersion != null && nameVersion.getVersion() != null && !nameVersion.getVersion().isBlank()) {
            return nameVersion.getVersion().trim();
        }
        // 优先级2: 从 JDBC URL 解析版本参数
        String version = resolveVersionFromUrl(url);
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
     * 获取 IntelliJ 数据库 API 返回的 {@code NameVersion} 对象，映射为 {@link DbNameAndVersion}。
     *
     * <p>优先反射读取 {@code name} / {@code version} 字段，字段不可用时兜底解析 {@code toString()} 输出。
     *
     * @param dataSource IntelliJ 数据源委托对象（可为 null）
     * @return 包含 name/version 的对象，无法获取时返回 null
     */
    private static DbNameAndVersion resolveNameVersionObject(Object dataSource) {
        if (dataSource == null) {
            return null;
        }
        try {
            Object v = dataSource.getClass().getMethod("getDatabaseVersion").invoke(dataSource);
            if (v == null) {
                return null;
            }
            String originStr = v.toString();
            Object rawName = DbReflectionUtil.invokeField(v, "name");
            Object rawVersion = DbReflectionUtil.invokeField(v, "version");
            // 字段反射失败时，从 toString() 兜底解析
            String name = rawName != null ? rawName.toString() : extractFieldFromToString(originStr, "name");
            String version = rawVersion != null ? rawVersion.toString() : extractFieldFromToString(originStr, "version");
            return DbNameAndVersion.builder().name(name).version(version).originStr(originStr).build();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 通过子对象（如 {@code getDbmsInfo()}）间接获取 URL。
     *
     * @param dataSource IntelliJ 数据源委托对象
     * @param infoMethod 获取子对象的方法名
     * @return URL 字符串，无法获取时返回 null
     */
    private static String resolveUrlFromSubObject(Object dataSource, String infoMethod) {
        try {
            Object info = dataSource.getClass().getMethod(infoMethod).invoke(dataSource);
            if (info != null) {
                return DbReflectionUtil.invokeFirstNonBlankMethod(info, "getUrl", "getJdbcUrl");
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 从 {@code NameVersion.toString()} 格式字符串中解析指定字段值。
     *
     * <p>支持两种格式：
     * <ul>
     *   <li>带引号：{@code NameVersion{name='MySQL', version='8.0.36'}}</li>
     *   <li>无引号：{@code NameVersion{name=MySQL, version=8.0.36}}</li>
     * </ul>
     *
     * @param str       {@code toString()} 字符串
     * @param fieldName 要提取的字段名（如 {@code "name"} 或 {@code "version"}）
     * @return 字段值字符串，或 null（未找到）
     */
    private static String extractFieldFromToString(String str, String fieldName) {
        if (str == null || str.isBlank()) {
            return null;
        }
        // 匹配 fieldName='xxx' 格式
        String quotedPrefix = fieldName + "='";
        int idx = str.indexOf(quotedPrefix);
        if (idx >= 0) {
            String after = str.substring(idx + quotedPrefix.length());
            int end = after.indexOf('\'');
            if (end > 0) {
                String v = after.substring(0, end).trim();
                if (!v.isBlank()) {
                    return v;
                }
            }
        }
        // 匹配 fieldName=xxx 格式（无引号）
        String plainPrefix = fieldName + "=";
        idx = str.indexOf(plainPrefix);
        if (idx >= 0) {
            String after = str.substring(idx + plainPrefix.length());
            int end = after.indexOf(',');
            if (end < 0) {
                end = after.indexOf('}');
            }
            String v = (end >= 0 ? after.substring(0, end) : after).trim();
            if (!v.isBlank() && !v.contains("{")) {
                return v;
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

    // ===== 厂商 Token 推断 =====

    private static String pickBestVendorToken(String url, String driverClass) {
        Candidate best = updateBest(null, extractUrlVendorTokens(url), true);
        best = updateBest(best, extractDriverVendorTokens(driverClass), false);
        return (best == null || best.score() <= 0) ? null : best.token();
    }

    /**
     * 从给定 token 列表中更新当前最优 {@link Candidate}。
     *
     * @param best    当前最优候选（可为 null）
     * @param tokens  待评分的 token 列表
     * @param fromUrl tokens 是否来自 URL（影响评分基准）
     * @return 更新后的最优候选
     */
    private static Candidate updateBest(Candidate best, List<String> tokens, boolean fromUrl) {
        for (int i = 0; i < tokens.size(); i++) {
            int score = scoreToken(tokens.get(i), fromUrl, i);
            if (best == null || score > best.score()) {
                best = new Candidate(tokens.get(i), score);
            }
        }
        return best;
    }

    private static List<String> extractUrlVendorTokens(String url) {
        if (url == null || url.isBlank()) {
            return List.of();
        }
        String protocolPart = url.toLowerCase(Locale.ROOT).trim();
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
        List<String> tokens = new ArrayList<>();
        for (String segment : driverClass.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
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
            if (segment.isBlank()) {
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
        while (cleaned.startsWith("jdbc")) {
            cleaned = cleaned.substring(4);
        }
        while (cleaned.endsWith("driver")) {
            cleaned = cleaned.substring(0, cleaned.length() - 6);
        }
        cleaned = cleaned.replaceAll("^[^a-z0-9]+|[^a-z0-9]+$", "");
        if (cleaned.isBlank() || isSkippableDriverSegment(cleaned) || cleaned.length() > 40) {
            return null;
        }
        return cleaned;
    }

    private static int scoreToken(String token, boolean fromUrl, int orderIndex) {
        if (token == null || token.isBlank() || isSkippableDriverSegment(token)) {
            return Integer.MIN_VALUE;
        }
        int score = fromUrl ? 120 : 90;
        score += Math.max(0, 25 - Math.min(orderIndex, 25));
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
        return segment == null || segment.isBlank() || DRIVER_SEGMENT_BLACKLIST.contains(segment);
    }

    // ===== 厂商名称格式化 =====

    private static String formatVendorName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.replaceAll("[^a-z0-9]+", " ").trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        List<String> formatted = new ArrayList<>();
        for (String word : normalized.split("\\s+")) {
            String part = formatVendorWord(word);
            if (part != null) {
                formatted.add(part);
            }
        }
        return formatted.isEmpty() ? null : String.join(" ", formatted);
    }

    private static String formatVendorWord(String word) {
        if (word == null || word.isBlank()) {
            return null;
        }
        if (word.startsWith("sql") && word.length() > 3) {
            return "SQL" + capitalize(word.substring(3));
        }
        if (word.endsWith("sql") && word.length() > 3) {
            return capitalize(word.substring(0, word.length() - 3)) + "SQL";
        }
        if (word.endsWith("db") && word.length() > 2) {
            return capitalize(word.substring(0, word.length() - 2)) + "DB";
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
            sb.append(Character.isLetter(ch) ? Character.toUpperCase(ch) : ch);
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
}

