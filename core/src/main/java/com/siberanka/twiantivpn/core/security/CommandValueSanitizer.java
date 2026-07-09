package com.siberanka.twiantivpn.core.security;

public final class CommandValueSanitizer {
    private static final int MAX_LENGTH = 128;

    private CommandValueSanitizer() {
    }

    public static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder(Math.min(value.length(), MAX_LENGTH));
        for (int offset = 0; offset < value.length() && result.length() < MAX_LENGTH; ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)
                    || codePoint == '.'
                    || codePoint == '_'
                    || codePoint == '-'
                    || codePoint == ':') {
                result.appendCodePoint(codePoint);
            } else if (result.length() == 0 || result.charAt(result.length() - 1) != '_') {
                result.append('_');
            }
        }
        return result.toString();
    }
}
