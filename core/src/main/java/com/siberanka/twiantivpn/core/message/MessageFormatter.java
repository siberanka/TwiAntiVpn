package com.siberanka.twiantivpn.core.message;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MessageFormatter {
    private static final Pattern LEGACY_HEX_PATTERN = Pattern.compile("(?i)&?#([0-9a-f]{6})");
    private static final Pattern AMPERSAND_HEX_PATTERN = Pattern.compile("(?i)&x(&[0-9a-f]){6}");
    private static final Pattern MINI_HEX_PATTERN = Pattern.compile("(?i)<#([0-9a-f]{6})>");
    private static final Pattern MINI_GRADIENT_PATTERN = Pattern.compile("(?is)<gradient:([^>]+)>(.*?)</gradient>");
    private static final Pattern SECTION_COLOR_PATTERN = Pattern.compile("(?i)\u00a7x(\u00a7[0-9a-f]){6}|\u00a7[0-9a-fk-or]");
    private static final Pattern AMPERSAND_COLOR_PATTERN = Pattern.compile("(?i)&x(&[0-9a-f]){6}|&[0-9a-fk-or]");
    private static final Pattern MINI_TAG_PATTERN = Pattern.compile("(?i)</?[^>]+>");
    private static final int MAX_FORMATTED_LENGTH = 4096;

    private static final Map<String, Character> COLORS;
    private static final Map<String, Character> DECORATIONS;

    static {
        Map<String, Character> colors = new LinkedHashMap<>();
        colors.put("black", '0');
        colors.put("dark_blue", '1');
        colors.put("dark_green", '2');
        colors.put("dark_aqua", '3');
        colors.put("dark_red", '4');
        colors.put("dark_purple", '5');
        colors.put("gold", '6');
        colors.put("gray", '7');
        colors.put("grey", '7');
        colors.put("dark_gray", '8');
        colors.put("dark_grey", '8');
        colors.put("blue", '9');
        colors.put("green", 'a');
        colors.put("aqua", 'b');
        colors.put("red", 'c');
        colors.put("light_purple", 'd');
        colors.put("yellow", 'e');
        colors.put("white", 'f');
        COLORS = Collections.unmodifiableMap(colors);

        Map<String, Character> decorations = new LinkedHashMap<>();
        decorations.put("obfuscated", 'k');
        decorations.put("magic", 'k');
        decorations.put("bold", 'l');
        decorations.put("b", 'l');
        decorations.put("strikethrough", 'm');
        decorations.put("st", 'm');
        decorations.put("underlined", 'n');
        decorations.put("underline", 'n');
        decorations.put("u", 'n');
        decorations.put("italic", 'o');
        decorations.put("i", 'o');
        decorations.put("reset", 'r');
        DECORATIONS = Collections.unmodifiableMap(decorations);
    }

    private MessageFormatter() {
    }

    public static Map<String, String> placeholders(String... values) {
        if (values == null || values.length == 0) {
            return Collections.emptyMap();
        }
        Map<String, String> placeholders = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            placeholders.put(values[i], values[i + 1]);
        }
        return placeholders;
    }

    public static Map<String, String> withPrefix(String prefix, Map<String, String> placeholders) {
        Map<String, String> resolved = new LinkedHashMap<>();
        String safePrefix = prefix == null ? "" : prefix;
        resolved.put("{prefix}", safePrefix);
        resolved.put("{PREFIX}", safePrefix);
        resolved.put("%PREFIX%", safePrefix);
        resolved.put("%prefix%", safePrefix);
        if (placeholders != null && !placeholders.isEmpty()) {
            resolved.putAll(placeholders);
        }
        return resolved;
    }

    public static Map<String, String> placeholdersWithPrefix(String prefix, String... values) {
        return withPrefix(prefix, placeholders(values));
    }

    public static String toLegacyText(String raw, Map<String, String> placeholders) {
        String value = normalize(applyPlaceholders(raw, placeholders));
        value = renderMiniGradients(value);
        value = renderMiniTags(value);
        value = renderHex(value);
        value = translateLegacyAmpersand(value);
        return limit(value);
    }

    public static String toPlainText(String raw, Map<String, String> placeholders) {
        String value = toLegacyText(raw, placeholders);
        value = SECTION_COLOR_PATTERN.matcher(value).replaceAll("");
        value = AMPERSAND_COLOR_PATTERN.matcher(value).replaceAll("");
        value = MINI_TAG_PATTERN.matcher(value).replaceAll("");
        return limit(value);
    }

    public static String applyPlaceholders(String raw, Map<String, String> placeholders) {
        String value = raw == null ? "" : raw;
        if (placeholders == null || placeholders.isEmpty()) {
            return value;
        }
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            value = value.replace(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
        return value;
    }

    private static String normalize(String value) {
        return value
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\r", "\n")
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    private static String renderMiniGradients(String value) {
        Matcher matcher = MINI_GRADIENT_PATTERN.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            int[] colors = parseGradientColors(matcher.group(1));
            String content = matcher.group(2);
            if (colors.length < 2 || content.isEmpty()) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(content));
                continue;
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(applyGradient(content, colors[0], colors[colors.length - 1])));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static int[] parseGradientColors(String definition) {
        String[] parts = definition.split(":");
        int[] colors = new int[parts.length];
        int count = 0;
        for (String part : parts) {
            String normalized = part.trim();
            if (normalized.startsWith("#")) {
                normalized = normalized.substring(1);
            }
            if (normalized.matches("(?i)[0-9a-f]{6}")) {
                colors[count++] = Integer.parseInt(normalized, 16);
            }
        }
        int[] result = new int[count];
        System.arraycopy(colors, 0, result, 0, count);
        return result;
    }

    private static String applyGradient(String content, int startColor, int endColor) {
        int visibleLength = Math.max(1, content.codePointCount(0, content.length()));
        StringBuilder builder = new StringBuilder(content.length() * 16);
        int index = 0;
        for (int offset = 0; offset < content.length(); ) {
            int codePoint = content.codePointAt(offset);
            double ratio = visibleLength == 1 ? 0D : (double) index / (double) (visibleLength - 1);
            builder.append(toSectionHex(interpolate(startColor, endColor, ratio)));
            builder.appendCodePoint(codePoint);
            offset += Character.charCount(codePoint);
            index++;
        }
        return builder.toString();
    }

    private static int interpolate(int startColor, int endColor, double ratio) {
        int sr = (startColor >> 16) & 0xFF;
        int sg = (startColor >> 8) & 0xFF;
        int sb = startColor & 0xFF;
        int er = (endColor >> 16) & 0xFF;
        int eg = (endColor >> 8) & 0xFF;
        int eb = endColor & 0xFF;
        int r = (int) Math.round(sr + (er - sr) * ratio);
        int g = (int) Math.round(sg + (eg - sg) * ratio);
        int b = (int) Math.round(sb + (eb - sb) * ratio);
        return (r << 16) | (g << 8) | b;
    }

    private static String renderMiniTags(String value) {
        Matcher hexMatcher = MINI_HEX_PATTERN.matcher(value);
        StringBuffer hexBuffer = new StringBuffer();
        while (hexMatcher.find()) {
            hexMatcher.appendReplacement(hexBuffer, Matcher.quoteReplacement(toSectionHex(hexMatcher.group(1))));
        }
        hexMatcher.appendTail(hexBuffer);

        StringBuilder builder = new StringBuilder(hexBuffer.length());
        for (int i = 0; i < hexBuffer.length(); ) {
            char current = hexBuffer.charAt(i);
            if (current != '<') {
                builder.append(current);
                i++;
                continue;
            }
            int end = hexBuffer.indexOf(">", i + 1);
            if (end < 0) {
                builder.append(current);
                i++;
                continue;
            }
            String rawTag = hexBuffer.substring(i + 1, end).trim();
            String replacement = miniTagReplacement(rawTag);
            if (replacement != null) {
                builder.append(replacement);
            }
            i = end + 1;
        }
        return builder.toString();
    }

    private static String miniTagReplacement(String rawTag) {
        if (rawTag.isEmpty()) {
            return "";
        }
        boolean closing = rawTag.charAt(0) == '/';
        String tag = closing ? rawTag.substring(1) : rawTag;
        tag = tag.toLowerCase(Locale.ROOT);
        int separator = tag.indexOf(':');
        if (separator >= 0) {
            tag = tag.substring(0, separator);
        }
        if (tag.equals("newline") || tag.equals("br")) {
            return "\n";
        }
        if (closing) {
            return "\u00a7r";
        }
        Character color = COLORS.get(tag);
        if (color != null) {
            return "\u00a7" + color;
        }
        Character decoration = DECORATIONS.get(tag);
        if (decoration != null) {
            return "\u00a7" + decoration;
        }
        return "";
    }

    private static String renderHex(String value) {
        Matcher ampersandHex = AMPERSAND_HEX_PATTERN.matcher(value);
        StringBuffer ampersandBuffer = new StringBuffer();
        while (ampersandHex.find()) {
            String hex = ampersandHex.group().replace("&x", "").replace("&X", "").replace("&", "");
            ampersandHex.appendReplacement(ampersandBuffer, Matcher.quoteReplacement(toSectionHex(hex)));
        }
        ampersandHex.appendTail(ampersandBuffer);

        Matcher legacyHex = LEGACY_HEX_PATTERN.matcher(ampersandBuffer.toString());
        StringBuffer legacyBuffer = new StringBuffer();
        while (legacyHex.find()) {
            legacyHex.appendReplacement(legacyBuffer, Matcher.quoteReplacement(toSectionHex(legacyHex.group(1))));
        }
        legacyHex.appendTail(legacyBuffer);
        return legacyBuffer.toString();
    }

    private static String translateLegacyAmpersand(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '&' && i + 1 < value.length()) {
                char next = Character.toLowerCase(value.charAt(i + 1));
                if ("0123456789abcdefklmnor".indexOf(next) >= 0) {
                    builder.append('\u00a7').append(next);
                    i++;
                    continue;
                }
            }
            builder.append(current);
        }
        return builder.toString();
    }

    private static String toSectionHex(String hex) {
        return toSectionHex(Integer.parseInt(hex, 16));
    }

    private static String toSectionHex(int color) {
        String hex = String.format(Locale.ROOT, "%06x", color);
        StringBuilder builder = new StringBuilder(14);
        builder.append('\u00a7').append('x');
        for (int i = 0; i < hex.length(); i++) {
            builder.append('\u00a7').append(hex.charAt(i));
        }
        return builder.toString();
    }

    private static String limit(String value) {
        if (value.length() <= MAX_FORMATTED_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_FORMATTED_LENGTH);
    }
}
