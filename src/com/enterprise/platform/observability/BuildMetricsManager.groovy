package com.enterprise.platform.observability

import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class BuildMetricsManager implements Serializable {
    private static final long serialVersionUID = 1L

    private final Object steps
    private final TelemetryManager telemetry
    private final String correlationId
    private final List<Map<String, Object>> stageMetrics = []
    private final Map<String, Object> globalMetrics = [:]
    private long buildStartTime = 0

    BuildMetricsManager(Object steps) {
        this.steps = steps
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    BuildMetricsManager(Object steps, String correlationId) {
        this.steps = steps
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = correlationId
    }

    BuildMetricsManager recordBuildStart() {
        this.buildStartTime = currentTimeMillis()
        globalMetrics["buildStartedAt"] = this.buildStartTime
        globalMetrics["buildStartedAtISO"] = formatTimestamp(this.buildStartTime)
        return this
    }

    BuildMetricsManager recordStageStart(String stageName) {
        if (!ValidationUtils.isNonEmpty(stageName)) return this

        Map<String, Object> metric = findOrCreateStageMetric(stageName)
        metric["startTime"] = currentTimeMillis()
        metric["status"] = "IN_PROGRESS"
        return this
    }

    BuildMetricsManager recordStageEnd(String stageName, String status, String resultSummary) {
        if (!ValidationUtils.isNonEmpty(stageName)) return this
        if (status == null) status = "COMPLETED"

        Map<String, Object> metric = findOrCreateStageMetric(stageName)
        long endTime = currentTimeMillis()
        metric["endTime"] = endTime
        metric["status"] = status
        if (metric.containsKey("startTime")) {
            long start = metric["startTime"] instanceof Number ?
                ((Number) metric["startTime"]).longValue() : 0
            metric["durationMs"] = endTime - start
        }
        if (ValidationUtils.isNonEmpty(resultSummary)) {
            metric["resultSummary"] = resultSummary
        }

        return this
    }

    BuildMetricsManager recordCustomMetric(String metricName, Object value, Map<String, String> tags) {
        if (!ValidationUtils.isNonEmpty(metricName)) return this
        if (tags == null) tags = [:]

        Map<String, Object> metric = [:]
        metric["name"] = metricName
        metric["value"] = value
        metric["tags"] = tags
        metric["timestamp"] = currentTimeMillis()

        if (!globalMetrics.containsKey("customMetrics")) {
            globalMetrics["customMetrics"] = []
        }
        Object customMetricsObj = globalMetrics.get("customMetrics")
        if (customMetricsObj instanceof List) {
            ((List) customMetricsObj).add(metric)
        }

        return this
    }

    Map<String, Object> finalizeAndReport() {
        long buildEndTime = currentTimeMillis()
        long totalBuildDuration = 0
        if (buildStartTime > 0) {
            totalBuildDuration = buildEndTime - buildStartTime
        }

        globalMetrics["buildEndedAt"] = buildEndTime
        globalMetrics["buildEndedAtISO"] = formatTimestamp(buildEndTime)
        globalMetrics["totalDurationMs"] = totalBuildDuration
        globalMetrics["stageCount"] = stageMetrics.size()

        int completedStages = 0
        int failedStages = 0
        int skippedStages = 0

        for (Map<String, Object> stage : stageMetrics) {
            String status = stage.status?.toString() ?: "UNKNOWN"
            switch (status) {
                case "COMPLETED": case "SUCCESS": completedStages++; break
                case "FAILED": case "ERROR": failedStages++; break
                case "SKIPPED": skippedStages++; break
            }
        }

        globalMetrics["completedStages"] = completedStages
        globalMetrics["failedStages"] = failedStages
        globalMetrics["skippedStages"] = skippedStages

        Map<String, Object> buildReport = [:]
        buildReport["correlationId"] = correlationId
        buildReport["totalDurationMs"] = totalBuildDuration
        buildReport["stages"] = new ArrayList<>(stageMetrics)
        buildReport["metrics"] = new HashMap<>(globalMetrics)

        String buildStatus = failedStages > 0 ? "FAILED" : "COMPLETED"
        buildReport["buildStatus"] = buildStatus

        LoggingUtils.info("BuildMetricsManager",
            "Build finalized: ${buildStatus}, ${stageMetrics.size()} stages, ${totalBuildDuration}ms [correlationId=${correlationId}]")

        telemetry.emitLatencyMetric("build.total_duration", totalBuildDuration, [
            correlationId: correlationId,
            status: buildStatus,
            stages: String.valueOf(stageMetrics.size())
        ])

        telemetry.emitEvent("metrics", "build_finalized", [
            correlationId: correlationId,
            totalDurationMs: totalBuildDuration,
            totalStages: stageMetrics.size(),
            completedStages: completedStages,
            failedStages: failedStages,
            skippedStages: skippedStages,
            buildStatus: buildStatus
        ])

        for (Map<String, Object> stage : stageMetrics) {
            Object stageDuration = stage.get("durationMs")
            if (stageDuration instanceof Number) {
                telemetry.emitLatencyMetric("stage.${stage.name}.duration",
                    ((Number) stageDuration).longValue(),
                    [correlationId: correlationId, status: stage.status?.toString() ?: ""])
            }
        }

        return buildReport
    }

    Map<String, Object> getStageMetric(String stageName) {
        if (!ValidationUtils.isNonEmpty(stageName)) return [:]
        for (Map<String, Object> metric : stageMetrics) {
            if (stageName.equals(metric.name?.toString())) {
                return new HashMap<>(metric)
            }
        }
        return [:]
    }

    List<Map<String, Object>> getAllStageMetrics() {
        return new ArrayList<>(stageMetrics)
    }

    long getTotalDuration() {
        if (buildStartTime <= 0) return 0
        return currentTimeMillis() - buildStartTime
    }

    String getCorrelationId() {
        return this.correlationId
    }

    /*
     * Private helpers
     */

    @NonCPS
    private Map<String, Object> findOrCreateStageMetric(String stageName) {
        for (Map<String, Object> existing : stageMetrics) {
            if (stageName.equals(existing.get("name"))) {
                return existing
            }
        }
        Map<String, Object> newMetric = [:]
        newMetric["name"] = stageName
        newMetric["status"] = "PENDING"
        stageMetrics.add(newMetric)
        return newMetric
    }

    @NonCPS
    private long currentTimeMillis() {
        return System.currentTimeMillis()
    }

    @NonCPS
    private String formatTimestamp(long epochMs) {
        try {
            return new Date(epochMs).format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", TimeZone.getTimeZone("UTC"))
        } catch (Exception e) {
            return ""
        }
    }
}
