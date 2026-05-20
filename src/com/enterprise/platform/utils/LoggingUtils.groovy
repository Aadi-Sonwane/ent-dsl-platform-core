package com.enterprise.platform.utils

class LoggingUtils implements Serializable {
    private static final long serialVersionUID = 1L

    private static final String ANSI_RESET = "\u001B[0m"
    private static final String ANSI_RED = "\u001B[31m"
    private static final String ANSI_YELLOW = "\u001B[33m"
    private static final String ANSI_BLUE = "\u001B[34m"
    private static final String ANSI_CYAN = "\u001B[36m"

    private static boolean colorEnabled = true
    private static String prefix = "[BuildOS]"

    private LoggingUtils() {
        throw new UnsupportedOperationException("Utility class")
    }

    static void info(String source, String message) {
        String formatted = formatLog("INFO", source, message)
        echoWithPrefix(formatted)
    }

    static void warn(String source, String message) {
        String formatted = formatLog("WARN", source, message)
        if (colorEnabled) {
            echoWithPrefix("${ANSI_YELLOW}${formatted}${ANSI_RESET}")
        } else {
            echoWithPrefix(formatted)
        }
    }

    static void error(String source, String message, Throwable throwable) {
        String formatted = formatLog("ERROR", source, message)
        if (colorEnabled) {
            echoWithPrefix("${ANSI_RED}${formatted}${ANSI_RESET}")
        } else {
            echoWithPrefix(formatted)
        }
        if (throwable != null) {
            echoWithPrefix("${ANSI_RED}[BuildOS] ERROR ${source}: ${throwable.getClass().name}: ${throwable.message}${ANSI_RESET}")
        }
    }

    static void debug(String source, String message) {
        String formatted = formatLog("DEBUG", source, message)
        if (colorEnabled) {
            echoWithPrefix("${ANSI_CYAN}${formatted}${ANSI_RESET}")
        } else {
            echoWithPrefix(formatted)
        }
    }

    static void audit(String action, String message) {
        String formatted = "[AUDIT] ${action}: ${message}"
        echoWithPrefix(formatted)
    }

    static void metric(String metricName, Object value, String unit) {
        String formatted = "[METRIC] ${metricName}=${value}${unit ? unit : ''}"
        echoWithPrefix(formatted)
    }

    static void setColorEnabled(boolean enabled) {
        colorEnabled = enabled
    }

    static void setPrefix(String newPrefix) {
        if (newPrefix != null) {
            prefix = newPrefix
        }
    }

    @NonCPS
    private static String formatLog(String level, String source, String message) {
        String timestamp = formatTimestamp()
        return "[${timestamp}] [${level}] [${source}] ${message}"
    }

    @NonCPS
    private static String formatTimestamp() {
        try {
            return new Date().format("yyyy-MM-dd HH:mm:ss.SSS")
        } catch (Exception e) {
            return "unknown-time"
        }
    }

    @NonCPS
    private static void echoWithPrefix(String message) {
        if (message == null) return
        String prefixed = "${prefix} ${message}"
        System.out.println(prefixed)
    }

    @NonCPS
    static String truncate(String message, int maxLength) {
        if (message == null) return ""
        if (message.length() <= maxLength) return message
        return message.substring(0, maxLength - 3) + "..."
    }

    @NonCPS
    static String sanitizeForLog(String input) {
        if (input == null) return ""
        return input
            .replaceAll("(?i)(password|passwd|secret|token|credential|api[_-]?key)[=:][^\\s,;}\"]+", "\$1:***")
            .replaceAll("(?i)(-----BEGIN[^\\-]+-----)[^\\-]+(-----END[^\\-]+-----)", "\$1...\$2")
            .replaceAll("(?i)(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9_]{36,}", "gh?_***")
            .replaceAll("(?i)xox[baprs]-[0-9A-Za-z-]{10,}", "xox?-***")
            .replaceAll("(?i)sk-[A-Za-z0-9]{20,}", "sk-***")
            .replaceAll("(?i)(AKIA|ASIA)[0-9A-Z]{16}", "AKIA***")
    }
}
