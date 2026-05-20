package com.enterprise.platform.framework

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class ParallelExecutionManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final int DEFAULT_MAX_PARALLELISM = 4
    private static final int DEFAULT_MAX_CONCURRENCY = 2
    private static final int MIN_PARALLELISM = 1
    private static final int MAX_PARALLELISM = 16

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final String correlationId

    private int maxParallelism
    private int maxConcurrency

    ParallelExecutionManager(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = telemetry.generateCorrelationId()
        this.maxParallelism = DEFAULT_MAX_PARALLELISM
        this.maxConcurrency = DEFAULT_MAX_CONCURRENCY
    }

    ParallelExecutionManager(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = correlationId
        this.maxParallelism = DEFAULT_MAX_PARALLELISM
        this.maxConcurrency = DEFAULT_MAX_CONCURRENCY
    }

    Map executeBoundedParallel(List<Map> stageDefinitions, Map options) {
        LoggingUtils.info("ParallelExecutionManager",
            "Executing ${stageDefinitions.size()} stages with bounded parallelism [correlationId=${correlationId}]")

        if (stageDefinitions == null || stageDefinitions.isEmpty()) {
            return [status: "COMPLETED", results: []]
        }
        if (options == null) options = [:]

        int effectiveParallelism = resolveParallelism(options)
        int effectiveConcurrency = options.maxConcurrency instanceof Number ?
            ((Number) options.maxConcurrency).intValue() : this.maxConcurrency

        long startTime = System.currentTimeMillis()
        List<Map> results = []
        int successCount = 0
        int failureCount = 0
        int skipCount = 0
        List<String> failedStages = []

        List<Map> batchList = new ArrayList<>(stageDefinitions)
        int batchSize = Math.min(effectiveParallelism, effectiveConcurrency)

        LoggingUtils.info("ParallelExecutionManager",
            "Batching ${batchList.size()} stages into batches of ${batchSize} [correlationId=${correlationId}]")

        for (int batchStart = 0; batchStart < batchList.size(); batchStart += batchSize) {
            int batchEnd = Math.min(batchStart + batchSize, batchList.size())
            List<Map> currentBatch = batchList.subList(batchStart, batchEnd)

            LoggingUtils.info("ParallelExecutionManager",
                "Executing parallel batch ${(batchStart / batchSize) + 1}: stages ${batchStart + 1}-${batchEnd} of ${batchList.size()} [correlationId=${correlationId}]")

            List<Map> batchResults = executeBatch(currentBatch, options)
            results.addAll(batchResults)

            for (Map result : batchResults) {
                String status = result.status?.toString() ?: "UNKNOWN"
                String stageName = result.stageName?.toString() ?: ""
                switch (status) {
                    case "SUCCESS": case "COMPLETED": successCount++; break
                    case "FAILED": case "ERROR": failureCount++; failedStages.add(stageName); break
                    case "SKIPPED": skipCount++; break
                }
            }

            if (failureCount > 0 && options.failFast instanceof Boolean && (Boolean) options.failFast) {
                LoggingUtils.warn("ParallelExecutionManager",
                    "Fail-fast triggered after ${failureCount} failure(s) in batch [correlationId=${correlationId}]")
                break
            }
        }

        long duration = System.currentTimeMillis() - startTime
        String summary = "${successCount} success, ${failureCount} failed, ${skipCount} skipped in ${duration}ms"

        LoggingUtils.info("ParallelExecutionManager",
            "Parallel execution completed: ${summary} [correlationId=${correlationId}]")
        audit.emitAuditEvent("PARALLEL_EXECUTION_COMPLETED",
            "Parallel execution: ${summary}", correlationId)
        telemetry.emitEvent("framework.parallel", "completed", [
            correlationId: correlationId,
            totalStages: stageDefinitions.size(),
            successCount: successCount,
            failureCount: failureCount,
            skipCount: skipCount,
            durationMs: duration,
            batchSize: batchSize
        ])

        return [
            status: failureCount > 0 ? "COMPLETED_WITH_FAILURES" : "COMPLETED",
            results: results,
            successCount: successCount,
            failureCount: failureCount,
            skipCount: skipCount,
            totalStages: stageDefinitions.size(),
            durationMs: duration,
            failedStages: failedStages
        ]
    }

    Map configureParallelism(Map config) {
        if (config == null) config = [:]

        if (config.maxParallelism instanceof Number) {
            int p = ((Number) config.maxParallelism).intValue()
            this.maxParallelism = clampParallelism(p)
        }
        if (config.maxConcurrency instanceof Number) {
            this.maxConcurrency = ((Number) config.maxConcurrency).intValue()
        }

        LoggingUtils.info("ParallelExecutionManager",
            "Configured: maxParallelism=${maxParallelism}, maxConcurrency=${maxConcurrency} [correlationId=${correlationId}]")

        return [
            maxParallelism: this.maxParallelism,
            maxConcurrency: this.maxConcurrency
        ]
    }

    boolean canRunParallel(List<String> stageGroup) {
        if (stageGroup == null || stageGroup.size() <= 1) return false

        List<String> safeParallelGroups = [
            "security", "scan", "audit",
            "quality", "coverage",
            "notifications", "telemetry"
        ]

        for (String stage : stageGroup) {
            String lower = stage.toLowerCase()
            boolean safe = false
            for (String group : safeParallelGroups) {
                if (lower.contains(group)) {
                    safe = true
                    break
                }
            }
            if (!safe) return false
        }

        return stageGroup.size() <= maxConcurrency
    }

    String getCorrelationId() {
        return this.correlationId
    }

    /*
     * Private helpers
     */

    private List<Map> executeBatch(List<Map> batch, Map options) {
        List<Map> results = []

        try {
            Map branches = [:]
            for (int i = 0; i < batch.size(); i++) {
                Map stageDef = batch.get(i)
                String stageName = stageDef.stageName instanceof String ?
                    stageDef.stageName.toString() : "parallel-stage-${i}"

                branches["stage-${i}"] = {
                    try {
                        Closure execution = stageDef.execution instanceof Closure ?
                            stageDef.execution : null
                        Object stageResult = execution != null ? execution.call() : [status: "SKIPPED"]
                        results.add([
                            stageName: stageName,
                            index: i,
                            status: "SUCCESS",
                            result: stageResult
                        ])
                    } catch (Exception e) {
                        results.add([
                            stageName: stageName,
                            index: i,
                            status: "FAILED",
                            error: e.message
                        ])
                        LoggingUtils.error("ParallelExecutionManager",
                            "Parallel stage '${stageName}' failed: ${e.message}", e)
                    }
                }
            }

            if (branches.size() > 0) {
                steps.parallel(branches)
            }

        } catch (Exception e) {
            LoggingUtils.error("ParallelExecutionManager",
                "Parallel batch execution error: ${e.message}", e)
        }

        return results
    }

    @NonCPS
    private int resolveParallelism(Map options) {
        int p = options.maxParallelism instanceof Number ?
            ((Number) options.maxParallelism).intValue() : this.maxParallelism
        return clampParallelism(p)
    }

    @NonCPS
    private int clampParallelism(int value) {
        return Math.max(MIN_PARALLELISM, Math.min(MAX_PARALLELISM, value))
    }
}
