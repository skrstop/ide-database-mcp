package com.skrstop.ide.databasemcp.db;

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

    static String inferDataSourceType(String url, String driverClass) {
        String vendorToken = pickBestVendorToken(url, driverClass);
        String vendor = formatVendorName(vendorToken);
        return vendor == null || vendor.isBlank() ? CUSTOM_DATA_SOURCE_TYPE : vendor;
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

