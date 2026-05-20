package com.enterprise.platform.utils

class ExecutionUtils implements Serializable {
    private static final long serialVersionUID = 1L

    private ExecutionUtils() {
        throw new UnsupportedOperationException("Utility class")
    }

    static String resolveEnvironmentVariable(Object steps, String name, String defaultValue) {
        if (name == null) return defaultValue
        try {
            String value = steps.env.getProperty(name)
            if (ValidationUtils.isNonEmpty(value)) return value
        } catch (Exception e) {
            LoggingUtils.debug("ExecutionUtils",
                "Failed to resolve env var '${name}': ${e.message}")
        }
        return defaultValue
    }

    static boolean environmentFlagEnabled(Object steps, String name, boolean defaultValue) {
        String value = resolveEnvironmentVariable(steps, name, null)
        if (value == null) return defaultValue
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value)
    }

    static Map safeCastToMap(Object value) {
        if (value instanceof Map) return (Map) value
        if (value == null) return [:]
        return [:]
    }

    static List safeCastToList(Object value) {
        if (value instanceof List) return (List) value
        if (value == null) return []
        return []
    }

    static String safeCastToString(Object value, String defaultValue) {
        if (value instanceof String) return (String) value
        if (value != null) return value.toString()
        return defaultValue
    }

    static int safeCastToInt(Object value, int defaultValue) {
        if (value instanceof Number) return ((Number) value).intValue()
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value)
            } catch (Exception e) { }
        }
        return defaultValue
    }

    static long safeCastToLong(Object value, long defaultValue) {
        if (value instanceof Number) return ((Number) value).longValue()
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value)
            } catch (Exception e) { }
        }
        return defaultValue
    }

    static double safeCastToDouble(Object value, double defaultValue) {
        if (value instanceof Number) return ((Number) value).doubleValue()
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value)
            } catch (Exception e) { }
        }
        return defaultValue
    }

    static boolean safeCastToBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean) return (Boolean) value
        if (value instanceof String) {
            String s = (String) value
            return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s)
        }
        if (value instanceof Number) return ((Number) value).intValue() != 0
        return defaultValue
    }

    @NonCPS
    static String generateShortId() {
        StringBuilder sb = new StringBuilder()
        for (int i = 0; i < 8; i++) {
            sb.append(Integer.toHexString((int) (Math.random() * 16)))
        }
        return sb.toString()
    }

    @NonCPS
    static Map deepMerge(Map base, Map override) {
        if (base == null && override == null) return [:]
        if (base == null) return cloneMap(override)
        if (override == null) return cloneMap(base)

        Map result = cloneMap(base)
        for (Map.Entry entry : override.entrySet()) {
            String key = entry.key.toString()
            Object overrideValue = entry.value
            Object baseValue = result.get(key)

            if (overrideValue instanceof Map && baseValue instanceof Map) {
                result[key] = deepMerge((Map) baseValue, (Map) overrideValue)
            } else if (overrideValue != null) {
                result[key] = overrideValue
            }
        }
        return result
    }

    @NonCPS
    static Map cloneMap(Map source) {
        if (source == null) return [:]
        Map result = [:]
        for (Map.Entry entry : source.entrySet()) {
            String key = entry.key.toString()
            Object value = entry.value
            if (value instanceof Map) {
                result[key] = cloneMap((Map) value)
            } else if (value instanceof List) {
                result[key] = cloneList((List) value)
            } else {
                result[key] = value
            }
        }
        return result
    }

    @NonCPS
    static List cloneList(List source) {
        if (source == null) return []
        List result = []
        for (Object item : source) {
            if (item instanceof Map) {
                result.add(cloneMap((Map) item))
            } else if (item instanceof List) {
                result.add(cloneList((List) item))
            } else {
                result.add(item)
            }
        }
        return result
    }

    @NonCPS
    static String extractStringField(Map map, String key) {
        if (map == null) return null
        Object value = map.get(key)
        if (value instanceof String) return (String) value
        if (value != null) return value.toString()
        return null
    }

    @NonCPS
    static String extractStringField(Map map, String key, String defaultValue) {
        String value = extractStringField(map, key)
        return value != null ? value : defaultValue
    }

    @NonCPS
    static Map extractMapField(Map parent, String key) {
        if (parent == null) return null
        Object value = parent.get(key)
        if (value instanceof Map) return (Map) value
        return null
    }

    @NonCPS
    static List extractListField(Map parent, String key) {
        if (parent == null) return []
        Object value = parent.get(key)
        if (value instanceof List) return (List) value
        return []
    }

    @NonCPS
    static long currentTimeMillis() {
        return System.currentTimeMillis()
    }

    @NonCPS
    static String formatTimestamp(long epochMs) {
        try {
            return new Date(epochMs).format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", TimeZone.getTimeZone("UTC"))
        } catch (Exception e) {
            return epochMs.toString()
        }
    }

    @NonCPS
    static String formatTimestamp() {
        return formatTimestamp(System.currentTimeMillis())
    }

    @NonCPS
    static void sleepQuietly(Object steps, long millis) {
        if (millis <= 0) return
        try {
            steps.sleep(time: millis / 1000, unit: "SECONDS")
        } catch (Exception e) {
            LoggingUtils.warn("ExecutionUtils",
                "Sleep interrupted: ${e.message}")
        }
    }
}
