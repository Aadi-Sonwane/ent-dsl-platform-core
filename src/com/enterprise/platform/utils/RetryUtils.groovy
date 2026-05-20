package com.enterprise.platform.utils

class RetryUtils implements Serializable {
    private static final long serialVersionUID = 1L

    private static final int DEFAULT_MAX_RETRIES = 3
    private static final long DEFAULT_BACKOFF_BASE_MS = 1000
    private static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0
    private static final long MAX_BACKOFF_MS = 60000

    private final Object steps
    private int maxRetries
    private long backoffBaseMs
    private double backoffMultiplier
    private long maxBackoffMs
    private final List<Long> retryDurations = []

    RetryUtils(Object steps) {
        this.steps = steps
        this.maxRetries = DEFAULT_MAX_RETRIES
        this.backoffBaseMs = DEFAULT_BACKOFF_BASE_MS
        this.backoffMultiplier = DEFAULT_BACKOFF_MULTIPLIER
        this.maxBackoffMs = MAX_BACKOFF_MS
    }

    RetryUtils(Object steps, int maxRetries, long backoffBaseMs, double backoffMultiplier) {
        this.steps = steps
        this.maxRetries = maxRetries > 0 ? maxRetries : DEFAULT_MAX_RETRIES
        this.backoffBaseMs = backoffBaseMs > 0 ? backoffBaseMs : DEFAULT_BACKOFF_BASE_MS
        this.backoffMultiplier = backoffMultiplier > 0 ? backoffMultiplier : DEFAULT_BACKOFF_MULTIPLIER
        this.maxBackoffMs = MAX_BACKOFF_MS
    }

    Map execute(String operationName, Closure operation, Map options) {
        if (operationName == null) operationName = "unnamed-operation"
        if (options == null) options = [:]

        int effectiveMaxRetries = options.maxRetries instanceof Number ?
            ((Number) options.maxRetries).intValue() : this.maxRetries
        long effectiveBackoffMs = options.backoffBaseMs instanceof Number ?
            ((Number) options.backoffBaseMs).longValue() : this.backoffBaseMs
        double effectiveMultiplier = options.backoffMultiplier instanceof Number ?
            ((Number) options.backoffMultiplier).doubleValue() : this.backoffMultiplier
        List<Class> retryableExceptions = options.retryableExceptions instanceof List ?
            (List<Class>) options.retryableExceptions : []

        retryDurations.clear()
        long startTime = ExecutionUtils.currentTimeMillis()
        int attempt = 0
        Exception lastException = null

        while (attempt <= effectiveMaxRetries) {
            attempt++
            long attemptStartTime = ExecutionUtils.currentTimeMillis()

            try {
                Object result = operation.call()
                long attemptDuration = ExecutionUtils.currentTimeMillis() - attemptStartTime
                retryDurations.add(attemptDuration)

                if (attempt > 1) {
                    LoggingUtils.info("RetryUtils",
                        "Operation '${operationName}' succeeded on attempt ${attempt}/${effectiveMaxRetries + 1} after ${attemptDuration}ms")
                }

                Map resultMap = [:]
                resultMap["success"] = true
                resultMap["attempt"] = attempt
                resultMap["totalAttempts"] = attempt
                resultMap["durationMs"] = ExecutionUtils.currentTimeMillis() - startTime
                resultMap["attemptDurationsMs"] = new ArrayList<>(retryDurations)
                resultMap["result"] = result
                return resultMap

            } catch (Exception e) {
                lastException = e
                long attemptDuration = ExecutionUtils.currentTimeMillis() - attemptStartTime
                retryDurations.add(attemptDuration)

                if (attempt > effectiveMaxRetries) {
                    LoggingUtils.error("RetryUtils",
                        "Operation '${operationName}' failed after ${attempt} attempts: ${e.message}", e)
                    break
                }

                if (!isRetryable(e, retryableExceptions)) {
                    LoggingUtils.warn("RetryUtils",
                        "Operation '${operationName}' failed with non-retryable exception on attempt ${attempt}: ${e.message}")
                    break
                }

                long backoffMs = calculateBackoff(attempt, effectiveBackoffMs, effectiveMultiplier)
                LoggingUtils.warn("RetryUtils",
                    "Operation '${operationName}' failed on attempt ${attempt}/${effectiveMaxRetries + 1}, retrying in ${backoffMs}ms: ${e.message}")

                sleepQuietly(backoffMs)
            }
        }

        long totalDuration = ExecutionUtils.currentTimeMillis() - startTime
        LoggingUtils.error("RetryUtils",
            "Operation '${operationName}' exhausted ${attempt} attempt(s) after ${totalDuration}ms", lastException)

        Map failureMap = [:]
        failureMap["success"] = false
        failureMap["attempt"] = attempt
        failureMap["totalAttempts"] = attempt
        failureMap["durationMs"] = totalDuration
        failureMap["attemptDurationsMs"] = new ArrayList<>(retryDurations)
        failureMap["error"] = lastException?.message ?: "Unknown error"
        failureMap["exception"] = lastException
        return failureMap
    }

    Map execute(String operationName, Closure operation) {
        return execute(operationName, operation, [:])
    }

    List<Long> getRetryDurations() {
        return new ArrayList<>(retryDurations)
    }

    int getRetryCount() {
        return Math.max(0, retryDurations.size() - 1)
    }

    /*
     * Configuration
     */

    void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries > 0 ? maxRetries : DEFAULT_MAX_RETRIES
    }

    void setBackoffBaseMs(long backoffBaseMs) {
        this.backoffBaseMs = backoffBaseMs > 0 ? backoffBaseMs : DEFAULT_BACKOFF_BASE_MS
    }

    void setBackoffMultiplier(double backoffMultiplier) {
        this.backoffMultiplier = backoffMultiplier > 0 ? backoffMultiplier : DEFAULT_BACKOFF_MULTIPLIER
    }

    void setMaxBackoffMs(long maxBackoffMs) {
        this.maxBackoffMs = maxBackoffMs > 0 ? maxBackoffMs : MAX_BACKOFF_MS
    }

    /*
     * Static convenience
     */

    static Map retry(Closure operation, int maxRetries, long backoffMs) {
        RetryUtils runner = new RetryUtils(null, maxRetries, backoffMs, 1.0)
        return runner.execute("static-retry", operation, [maxRetries: maxRetries - 1])
    }

    /*
     * Private helpers
     */

    @NonCPS
    private long calculateBackoff(int attempt, long baseMs, double multiplier) {
        double exponential = baseMs * Math.pow(multiplier, attempt - 1)
        double jitter = Math.random() * baseMs * 0.1
        long backoff = (long) (exponential + jitter)
        return Math.min(backoff, this.maxBackoffMs)
    }

    @NonCPS
    private boolean isRetryable(Exception e, List<Class> retryableExceptions) {
        if (retryableExceptions == null || retryableExceptions.isEmpty()) {
            return isDefaultRetryable(e)
        }
        for (Class clazz : retryableExceptions) {
            if (clazz.isInstance(e)) return true
        }
        return false
    }

    @NonCPS
    private boolean isDefaultRetryable(Exception e) {
        if (e == null) return false
        String className = e.getClass().name
        if (className.contains("HttpResponseException") || className.contains("SocketException") ||
            className.contains("ConnectException") || className.contains("TimeoutException") ||
            className.contains("SocketTimeoutException")) {
            return true
        }
        if (e.getMessage() != null) {
            String msg = e.getMessage().toLowerCase()
            if (msg.contains("timeout") || msg.contains("refused") || msg.contains("reset") ||
                msg.contains("retry") || msg.contains("503") || msg.contains("502") ||
                msg.contains("429") || msg.contains("too many requests")) {
                return true
            }
        }
        return false
    }

    private void sleepQuietly(long millis) {
        if (this.steps == null) {
            try {
                Thread.sleep(millis)
            } catch (Exception e) { }
        } else {
            ExecutionUtils.sleepQuietly(this.steps, millis)
        }
    }
}
