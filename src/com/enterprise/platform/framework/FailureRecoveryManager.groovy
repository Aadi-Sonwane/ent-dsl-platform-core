package com.enterprise.platform.framework

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class FailureRecoveryManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final int MAX_RECOVERY_ATTEMPTS = 2
    private static final long RECOVERY_BACKOFF_MS = 10000

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final String correlationId
    private final List<Map> failureHistory = []

    FailureRecoveryManager(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    FailureRecoveryManager(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = correlationId
    }

    Map handleFailure(String stageName, String errorMessage, Throwable exception, Map recoveryConfig) {
        LoggingUtils.info("FailureRecoveryManager",
            "Handling failure in stage '${stageName}': ${errorMessage} [correlationId=${correlationId}]")

        if (recoveryConfig == null) recoveryConfig = [:]

        Map failureRecord = buildFailureRecord(stageName, errorMessage, exception)
        failureHistory.add(failureRecord)

        try {
            if (!isRecoverable(failureRecord, recoveryConfig)) {
                LoggingUtils.error("FailureRecoveryManager",
                    "Failure in '${stageName}' is not recoverable. Terminating.", exception)
                audit.emitAuditEvent("STAGE_FAILURE_NOT_RECOVERABLE",
                    "Stage '${stageName}' failure is not recoverable: ${errorMessage}", correlationId)
                return [
                    recovered: false,
                    action: "TERMINATE",
                    reason: "Failure is not recoverable",
                    stageName: stageName
                ]
            }

            int consecutiveFailures = countConsecutiveFailures(stageName)
            int maxRecoveryAttempts = recoveryConfig.maxRecoveryAttempts instanceof Number ?
                ((Number) recoveryConfig.maxRecoveryAttempts).intValue() : MAX_RECOVERY_ATTEMPTS

            if (consecutiveFailures > maxRecoveryAttempts) {
                LoggingUtils.error("FailureRecoveryManager",
                    "Stage '${stageName}' failed ${consecutiveFailures} times, exceeding max ${maxRecoveryAttempts} recovery attempts", null)
                audit.emitAuditEvent("STAGE_RECOVERY_EXHAUSTED",
                    "Stage '${stageName}' recovery exhausted after ${consecutiveFailures} attempts", correlationId)
                return [
                    recovered: false,
                    action: "TERMINATE",
                    reason: "Recovery attempts exhausted",
                    consecutiveFailures: consecutiveFailures,
                    maxRecoveryAttempts: maxRecoveryAttempts
                ]
            }

            String strategy = resolveRecoveryStrategy(failureRecord, recoveryConfig)
            Map recoveryResult = executeRecoveryStrategy(strategy, stageName, recoveryConfig)

            if (recoveryResult.recovered) {
                LoggingUtils.info("FailureRecoveryManager",
                    "Stage '${stageName}' recovered using strategy '${strategy}' [correlationId=${correlationId}]")
                audit.emitAuditEvent("STAGE_RECOVERED",
                    "Stage '${stageName}' recovered via '${strategy}'", correlationId)
                telemetry.emitEvent("framework.recovery", "recovered", [
                    correlationId: correlationId,
                    stageName: stageName,
                    strategy: strategy,
                    consecutiveFailures: consecutiveFailures
                ])
            } else {
                LoggingUtils.warn("FailureRecoveryManager",
                    "Stage '${stageName}' recovery strategy '${strategy}' failed: ${recoveryResult.reason} [correlationId=${correlationId}]")
            }

            return recoveryResult

        } catch (Exception e) {
            String msg = "Recovery handler error for '${stageName}': ${e.message}"
            LoggingUtils.error("FailureRecoveryManager", msg, e)
            return [recovered: false, action: "TERMINATE", reason: msg]
        }
    }

    Map determineRetryStrategy(String stageName, int failureCount) {
        String action
        String strategy
        long backoffMs

        if (failureCount <= 1) {
            action = "RETRY"
            strategy = "IMMEDIATE_RETRY"
            backoffMs = 2000
        } else if (failureCount <= 3) {
            action = "RETRY"
            strategy = "BACKOFF_RETRY"
            backoffMs = RECOVERY_BACKOFF_MS * failureCount
        } else {
            action = "SKIP"
            strategy = "SKIP_STAGE"
            backoffMs = 0
        }

        return [
            action: action,
            strategy: strategy,
            backoffMs: backoffMs,
            failureCount: failureCount,
            stageName: stageName
        ]
    }

    List<Map> getFailureHistory() {
        return new ArrayList<>(failureHistory)
    }

    boolean hasFailures() {
        return !failureHistory.isEmpty()
    }

    Map generateRetrySummaryReport() {
        Map report = [:]
        report["correlationId"] = correlationId
        report["totalFailures"] = failureHistory.size()
        report["stagesAffected"] = failureHistory.collect { it.stageName }.toUnique()
        report["recoverableCount"] = failureHistory.count { it.recoverable }
        report["unrecoverableCount"] = failureHistory.count { !it.recoverable }
        report["failures"] = new ArrayList<>(failureHistory)

        List<String> chronologicalStages = failureHistory.collect { it.stageName?.toString() ?: "" }
        chronologicalStages.removeAll([""])
        report["chronologicalStageFailures"] = chronologicalStages

        return report
    }

    String getCorrelationId() {
        return this.correlationId
    }

    /*
     * Private helpers
     */

    @NonCPS
    private Map buildFailureRecord(String stageName, String errorMessage, Throwable exception) {
        Map record = [:]
        record["stageName"] = stageName
        record["errorMessage"] = errorMessage
        record["errorClass"] = exception?.getClass()?.name ?: "Unknown"
        record["timestamp"] = formatTimestamp()
        record["recoverable"] = isErrorRecoverable(exception, errorMessage)
        return record
    }

    @NonCPS
    private boolean isRecoverable(Map failureRecord, Map recoveryConfig) {
        if (recoveryConfig.recoverableStages instanceof List) {
            List stages = (List) recoveryConfig.recoverableStages
            if (!stages.contains(failureRecord.stageName?.toString())) return false
        }
        if (recoveryConfig.unrecoverableErrors instanceof List) {
            List errors = (List) recoveryConfig.unrecoverableErrors
            for (Object err : errors) {
                if (err instanceof String && failureRecord.errorClass?.toString()?.contains(err.toString())) {
                    return false
                }
            }
        }
        return failureRecord.recoverable instanceof Boolean ? (Boolean) failureRecord.recoverable : true
    }

    @NonCPS
    private boolean isErrorRecoverable(Throwable exception, String errorMessage) {
        if (exception != null) {
            String className = exception.getClass().name
            if (className.contains("OutOfMemoryError") || className.contains("StackOverflow")) return false
            if (className.contains("InterruptedException") || className.contains("SerializationException")) return false
            if (className.contains("NotSerializableException")) return false
        }
        if (errorMessage != null) {
            String msg = errorMessage.toLowerCase()
            if (msg.contains("not serializable")) return false
            if (msg.contains("out of memory")) return false
            if (msg.contains("stack overflow")) return false
        }
        return true
    }

    @NonCPS
    private String resolveRecoveryStrategy(Map failureRecord, Map recoveryConfig) {
        if (recoveryConfig.recoveryStrategy instanceof String) {
            return recoveryConfig.recoveryStrategy.toString()
        }
        if (failureRecord.errorClass?.toString()?.contains("TimeoutException") ||
            failureRecord.errorMessage?.toString()?.toLowerCase()?.contains("timeout")) {
            return "INCREASE_TIMEOUT"
        }
        if (failureRecord.errorMessage?.toString()?.toLowerCase()?.contains("resource") ||
            failureRecord.errorMessage?.toString()?.toLowerCase()?.contains("lock")) {
            return "WAIT_AND_RETRY"
        }
        return "SIMPLE_RETRY"
    }

    private Map executeRecoveryStrategy(String strategy, String stageName, Map recoveryConfig) {
        switch (strategy) {
            case "SIMPLE_RETRY":
                return [recovered: true, action: "RETRY", strategy: strategy,
                        reason: "Simple retry permitted"]
            case "WAIT_AND_RETRY":
                long waitMs = recoveryConfig.waitBackoffMs instanceof Number ?
                    ((Number) recoveryConfig.waitBackoffMs).longValue() : RECOVERY_BACKOFF_MS
                try {
                    steps.sleep(time: waitMs / 1000, unit: "SECONDS")
                } catch (Exception e) { }
                return [recovered: true, action: "RETRY", strategy: strategy,
                        reason: "Waited ${waitMs}ms before retry"]
            case "INCREASE_TIMEOUT":
                return [recovered: true, action: "RETRY_WITH_INCREASED_TIMEOUT",
                        strategy: strategy, reason: "Timeout will be increased"]
            case "SKIP_STAGE":
                return [recovered: false, action: "SKIP", strategy: strategy,
                        reason: "Stage '${stageName}' will be skipped"]
            default:
                return [recovered: false, action: "TERMINATE", strategy: "UNKNOWN",
                        reason: "Unknown recovery strategy '${strategy}'"]
        }
    }

    @NonCPS
    private int countConsecutiveFailures(String stageName) {
        int count = 0
        for (int i = failureHistory.size() - 1; i >= 0; i--) {
            Map record = failureHistory.get(i)
            if (stageName.equals(record.stageName?.toString())) {
                count++
            } else if (count > 0) {
                break
            }
        }
        return count
    }

    @NonCPS
    private String formatTimestamp() {
        try {
            return new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"))
        } catch (Exception e) {
            return ""
        }
    }
}
