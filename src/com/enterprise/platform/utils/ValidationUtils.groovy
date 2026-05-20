package com.enterprise.platform.utils

class ValidationUtils implements Serializable {
    private static final long serialVersionUID = 1L

    private static final int MAX_STRING_LENGTH = 4096
    private static final String URL_PATTERN = "^(https?|ssh|git)://.*"
    private static final String EMAIL_PATTERN = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"

    private ValidationUtils() {
        throw new UnsupportedOperationException("Utility class")
    }

    static boolean isNonEmpty(String value) {
        return value != null && value.trim().length() > 0
    }

    static boolean isEmpty(String value) {
        return value == null || value.trim().length() == 0
    }

    static boolean isValidLength(String value, int maxLength) {
        if (value == null) return true
        return value.length() <= maxLength
    }

    static boolean isValidUrl(String url) {
        if (isEmpty(url)) return false
        return url.matches(URL_PATTERN)
    }

    static boolean isValidEmail(String email) {
        if (isEmpty(email)) return false
        return email.matches(EMAIL_PATTERN)
    }

    static boolean isValidRegex(String pattern) {
        if (isEmpty(pattern)) return false
        try {
            java.util.regex.Pattern.compile(pattern)
            return true
        } catch (java.util.regex.PatternSyntaxException e) {
            return false
        }
    }

    static boolean isAlphanumeric(String value) {
        if (isEmpty(value)) return false
        return value.matches("^[a-zA-Z0-9]+$")
    }

    static boolean isAlphanumericWithHyphen(String value) {
        if (isEmpty(value)) return false
        return value.matches("^[a-zA-Z][a-zA-Z0-9_-]*$")
    }

    static boolean isAlphaStart(String value) {
        if (isEmpty(value)) return false
        return value.matches("^[a-zA-Z].*")
    }

    static boolean isValidJenkinsIdentifier(String value) {
        if (isEmpty(value)) return false
        return value.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")
    }

    static boolean isValidFilePath(String path) {
        if (isEmpty(path)) return false
        return !path.contains("..") && !path.contains("~") &&
            path.length() <= 4096 && path.matches("^[a-zA-Z0-9_./\\-\\\\:]+$")
    }

    static boolean isPositiveNumber(Number value) {
        return value != null && value.doubleValue() > 0
    }

    static boolean isInRange(Number value, Number min, Number max) {
        if (value == null) return false
        double v = value.doubleValue()
        return v >= min.doubleValue() && v <= max.doubleValue()
    }

    static boolean isPort(int port) {
        return port > 0 && port <= 65535
    }

    static boolean isHttpStatusCode(int code) {
        return code >= 100 && code <= 599
    }

    static boolean isValidSemanticVersion(String version) {
        if (isEmpty(version)) return false
        return version.matches("^\\d+\\.\\d+(\\.\\d+)?([.-][A-Za-z0-9]+)?$")
    }

    static boolean isValidMavenVersion(String version) {
        if (isEmpty(version)) return false
        return version.matches("^[A-Za-z0-9._-]+$")
    }

    static String requireNonEmpty(String value, String fieldName) {
        if (!isNonEmpty(value)) {
            throw new IllegalArgumentException("${fieldName ?: 'Field'} must not be null or empty")
        }
        return value.trim()
    }

    static <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException("${fieldName ?: 'Value'} must not be null")
        }
        return value
    }

    static void requirePositive(Number value, String fieldName) {
        if (!isPositiveNumber(value)) {
            throw new IllegalArgumentException(
                "${fieldName ?: 'Value'} must be positive, got: ${value}")
        }
    }

    static void requireInRange(Number value, Number min, Number max, String fieldName) {
        if (!isInRange(value, min, max)) {
            throw new IllegalArgumentException(
                "${fieldName ?: 'Value'} must be between ${min} and ${max}, got: ${value}")
        }
    }

    static String sanitizeFileName(String name) {
        if (name == null) return ""
        return name.replaceAll("[^a-zA-Z0-9._-]", "_")
    }

    static String truncate(String value, int maxLength) {
        if (value == null) return ""
        if (value.length() <= maxLength) return value
        return value.substring(0, Math.max(0, maxLength - 3)) + "..."
    }

    static boolean matchesAny(String value, List<String> patterns) {
        if (value == null || patterns == null) return false
        for (String pattern : patterns) {
            if (pattern != null && value.matches(pattern)) return true
        }
        return false
    }

    static boolean isMap(Object value) {
        return value instanceof Map
    }

    static boolean isList(Object value) {
        return value instanceof List
    }

    static boolean isString(Object value) {
        return value instanceof String
    }

    static boolean isNumber(Object value) {
        return value instanceof Number
    }

    static boolean isBoolean(Object value) {
        return value instanceof Boolean
    }
}
