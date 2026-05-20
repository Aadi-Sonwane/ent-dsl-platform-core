package com.enterprise.platform.framework

import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ExecutionUtils

class RetryFramework implements Serializable {
    private static final long serialVersionUID = 1L

    private static final int DEFAULT_MAX_RETRIES = 3
    private static final long DEFAULT_BACKOFF_BASE_MS = 1000
    private static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0
    private static final long MAX_BACKOFF_MS = 60000

    private final Object steps
    private final List<Map> attemptHistory = []

    RetryFramework(Object steps) {
        this.steps = steps
    }

    Map retry(String name, Closure operation) {
        return retry(name, operation, [:])
    }

    Map retry(String name, Closure operation, Map retryConfig) {
        if (name == null) name = "unnamed-retry"
        if (operation == null) {
            throw new IllegalArgumentException("Operation closure must not be null")
        }
        if (retryConfig == null) retryConfig = [:]

        int maxRetries = retryConfig.maxRetries instanceof Number ?
            ((Number) retryConfig.maxRetries).intValue() : DEFAULT_MAX_RETRIES
        long backoffBaseMs = retryConfig.backoffBaseMs instanceof Number ?
            ((Number) retryConfig.backoffBaseMs).longValue() : DEFAULT_BACKOFF_BASE_MS
        double backoffMultiplier = retryConfig.backoffMultiplier instanceof Number ?
            ((Number) retryConfig.backoffMultiplier).doubleValue() : DEFAULT_BACKOFF_MULTIPLIER

        LoggingUtils.info("RetryFramework",
            "Starting retry for '${name}' [maxRetries=${maxRetries}, backoffBaseMs=${backoffBaseMs}]")

        long startTime = ExecutionUtils.currentTimeMillis()
        int attempt = 0
        Exception lastException = null

        while (attempt <= maxRetries) {
            attempt++
            long attemptStartTime = ExecutionUtils.currentTimeMillis()

            try {
                Object result = operation.call()
                long attemptDuration = ExecutionUtils.currentTimeMillis() - attemptStartTime

                Map historyEntry = [
                    attempt: attempt,
                    success: true,
                    durationMs: attemptDuration
                ]
                attemptHistory.add(historyEntry)

                if (attempt > 1) {
                    LoggingUtils.info("RetryFramework",
                        "Operation '${name}' succeeded on attempt ${attempt}/${maxRetries + 1} after ${attemptDuration}ms")
                }

                if (result instanceof Map) {
                    return (Map) result
                }
                return [status: 200, content: result?.toString() ?: ""]

            } catch (Exception e) {
                lastException = e
                long attemptDuration = ExecutionUtils.currentTimeMillis() - attemptStartTime

                Map historyEntry = [
                    attempt: attempt,
                    success: false,
                    durationMs: attemptDuration,
                    error: e.message
                ]
                attemptHistory.add(historyEntry)

                if (attempt > maxRetries) {
                    LoggingUtils.error("RetryFramework",
                        "Operation '${name}' failed after ${attempt} attempts: ${e.message}", e)
                    long totalDuration = ExecutionUtils.currentTimeMillis() - startTime
                    Map errorResult = [:]
                    errorResult["status"] = 0
                    errorResult["content"] = "Retry exhausted after ${attempt} attempts: ${e.message}"
                    errorResult["error"] = e.message
                    errorResult["exception"] = e
                    errorResult["totalDurationMs"] = totalDuration
                    return errorResult
                }

                if (!isRetryable(e)) {
                    LoggingUtils.warn("RetryFramework",
                        "Operation '${name}' failed with non-retryable exception on attempt ${attempt}: ${e.message}")
                    long totalDuration = ExecutionUtils.currentTimeMillis() - startTime
                    Map errorResult = [:]
                    errorResult["status"] = 0
                    errorResult["content"] = "Non-retryable failure: ${e.message}"
                    errorResult["error"] = e.message
                    errorResult["exception"] = e
                    errorResult["totalDurationMs"] = totalDuration
                    return errorResult
                }

                long backoffMs = calculateBackoff(attempt, backoffBaseMs, backoffMultiplier)
                LoggingUtils.warn("RetryFramework",
                    "Operation '${name}' failed on attempt ${attempt}/${maxRetries + 1}, retrying in ${backoffMs}ms: ${e.message}")

                sleepQuietly(backoffMs)
            }
        }

        long totalDuration = ExecutionUtils.currentTimeMillis() - startTime
        LoggingUtils.error("RetryFramework",
            "Operation '${name}' exhausted retries after ${totalDuration}ms", lastException)

        Map finalError = [:]
        finalError["status"] = 0
        finalError["content"] = "Retry exhausted: ${lastException?.message ?: 'Unknown error'}"
        finalError["error"] = lastException?.message ?: "Unknown error"
        finalError["exception"] = lastException
        finalError["totalDurationMs"] = totalDuration
        return finalError
    }

    List<Map> getAttemptHistory() {
        return new ArrayList<>(attemptHistory)
    }

    int getAttemptCount() {
        return attemptHistory.size()
    }

    int getFailureCount() {
        int count = 0
        for (Map entry : attemptHistory) {
            if (!entry.success) count++
        }
        return count
    }

    @NonCPS
    private long calculateBackoff(int attempt, long baseMs, double multiplier) {
        double exponential = baseMs * Math.pow(multiplier, attempt - 1)
        double jitter = Math.random() * baseMs * 0.1
        long backoff = (long) (exponential + jitter)
        return Math.min(backoff, MAX_BACKOFF_MS)
    }

    @NonCPS
    private boolean isRetryable(Exception e) {
        if (e == null) return false
        String className = e.getClass().name
        if (className.contains("HttpResponseException") || className.contains("SocketException") ||
            className.contains("ConnectException") || className.contains("TimeoutException") ||
            className.contains("SocketTimeoutException") || className.contains("IOException")) {
            return true
        }
        if (e.getMessage() != null) {
            String msg = e.getMessage().toLowerCase()
            if (msg.contains("timeout") || msg.contains("refused") || msg.contains("reset") ||
                msg.contains("retry") || msg.contains("503") || msg.contains("502") ||
                msg.contains("429") || msg.contains("too many requests") ||
                msg.contains("connection") || msg.contains("unreachable")) {
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
            try {
                ExecutionUtils.sleepQuietly(this.steps, millis)
            } catch (Exception e) { }
        }
    }
}
