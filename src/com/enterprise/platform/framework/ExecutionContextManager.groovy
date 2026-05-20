package com.enterprise.platform.framework

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ExecutionUtils
import com.enterprise.platform.utils.ValidationUtils

class ExecutionContextManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final List<String> SYSTEM_KEYS = ["_meta", "_context", "_state", "_spans"]

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final String correlationId
    private final Map<String, Object> context = [:]
    private final Map<String, Object> state = [:]

    private String currentStageName = ""
    private int currentStageIndex = 0
    private boolean pipelineAborted = false
    private long pipelineStartTime = 0

    ExecutionContextManager(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = telemetry.generateCorrelationId()
        initializeDefaultContext()
    }

    ExecutionContextManager(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = correlationId
        initializeDefaultContext()
    }

    ExecutionContextManager initialize(String projectName, String businessUnit, Map resolvedConfig) {
        this.pipelineStartTime = System.currentTimeMillis()
        this.pipelineAborted = false
        this.currentStageName = ""
        this.currentStageIndex = 0

        context["projectName"] = projectName
        context["businessUnit"] = businessUnit
        context["pipelineStartTime"] = this.pipelineStartTime
        context["pipelineStartTimeISO"] = formatTimestamp(this.pipelineStartTime)
        context["correlationId"] = correlationId
        context["stages"] = []

        if (resolvedConfig != null) {
            context["resolvedConfig"] = ExecutionUtils.cloneMap(resolvedConfig)
        }

        LoggingUtils.info("ExecutionContextManager",
            "Context initialized for '${projectName}' [${businessUnit}] [correlationId=${correlationId}]")

        return this
    }

    ExecutionContextManager enterStage(String stageName) {
        if (!ValidationUtils.isNonEmpty(stageName)) {
            stageName = "stage-${currentStageIndex + 1}"
        }

        Map stageEntry = findStage(stageName)
        if (stageEntry == null) {
            stageEntry = [:]
            stageEntry["name"] = stageName
            stageEntry["index"] = currentStageIndex + 1
            stageEntry["enteredAt"] = System.currentTimeMillis()
            stageEntry["enteredAtISO"] = formatTimestamp()
            stageEntry["status"] = "IN_PROGRESS"

            List stages = getStagesList()
            stages.add(stageEntry)
        }

        this.currentStageName = stageName
        this.currentStageIndex++

        state["currentStageName"] = stageName
        state["currentStageIndex"] = this.currentStageIndex

        LoggingUtils.info("ExecutionContextManager",
            "Entering stage ${this.currentStageIndex}: '${stageName}' [correlationId=${correlationId}]")

        return this
    }

    ExecutionContextManager exitStage(String stageName, String status, Map resultData) {
        if (!ValidationUtils.isNonEmpty(stageName)) {
            stageName = this.currentStageName
        }
        if (status == null) status = "COMPLETED"

        Map stageEntry = findStage(stageName)
        if (stageEntry != null) {
            stageEntry["exitedAt"] = System.currentTimeMillis()
            stageEntry["exitedAtISO"] = formatTimestamp()
            stageEntry["status"] = status
            stageEntry["durationMs"] = calculateStageDuration(stageEntry)

            if (resultData != null && !resultData.isEmpty()) {
                stageEntry["result"] = ExecutionUtils.cloneMap(resultData)
            }
        }

        if (stageName.equals(this.currentStageName)) {
            this.currentStageName = ""
        }

        return this
    }

    void setVariable(String key, Object value) {
        if (key == null) return
        if (key.startsWith("_")) {
            LoggingUtils.warn("ExecutionContextManager",
                "Cannot set system variable '${key}' via setVariable. Use system methods instead.")
            return
        }
        state[key] = value
    }

    Object getVariable(String key) {
        if (key == null) return null
        return state.get(key)
    }

    void setContextData(String key, Object value) {
        if (key != null && !SYSTEM_KEYS.contains(key)) {
            context[key] = value
        }
    }

    Object getContextData(String key) {
        if (key == null) return null
        return context.get(key)
    }

    boolean isStageCompleted(String stageName) {
        if (!ValidationUtils.isNonEmpty(stageName)) return false
        Map entry = findStage(stageName)
        if (entry == null) return false
        String status = entry.status?.toString() ?: ""
        return "COMPLETED".equals(status) || "SUCCESS".equals(status) ||
            "SKIPPED".equals(status)
    }

    boolean allStagesCompleted(List<String> stageNames) {
        if (stageNames == null) return true
        for (String name : stageNames) {
            if (!isStageCompleted(name)) return false
        }
        return true
    }

    void abortPipeline() {
        this.pipelineAborted = true
        LoggingUtils.warn("ExecutionContextManager",
            "Pipeline aborted by execution context [correlationId=${correlationId}]")
    }

    boolean isAborted() {
        return this.pipelineAborted
    }

    Map getStageSummary() {
        List stages = getStagesList()

        int inProgress = 0
        int completed = 0
        int failed = 0
        int skipped = 0
        int pending = 0

        for (Object stageObj : stages) {
            if (!(stageObj instanceof Map)) continue
            Map s = (Map) stageObj
            String status = s.status?.toString() ?: "PENDING"
            switch (status) {
                case "COMPLETED": case "SUCCESS": completed++; break
                case "FAILED": case "ERROR": failed++; break
                case "SKIPPED": skipped++; break
                case "IN_PROGRESS": inProgress++; break
                default: pending++; break
            }
        }

        return [
            totalStages: stages.size(),
            completed: completed,
            failed: failed,
            skipped: skipped,
            inProgress: inProgress,
            pending: pending,
            currentStage: this.currentStageName,
            currentStageIndex: this.currentStageIndex,
            pipelineAborted: this.pipelineAborted,
            elapsedMs: calculateElapsed()
        ]
    }

    Map exportContext() {
        Map export = ExecutionUtils.cloneMap(context)
        export["_state"] = ExecutionUtils.cloneMap(state)
        export["_stageSummary"] = getStageSummary()
        return export
    }

    String currentStage() {
        return this.currentStageName
    }

    int currentStageIndex() {
        return this.currentStageIndex
    }

    long elapsedMs() {
        return calculateElapsed()
    }

    String getCorrelationId() {
        return this.correlationId
    }

    /*
     * Private helpers
     */

    private void initializeDefaultContext() {
        context["_state"] = state
        context["_context"] = [:]
        context["_stages"] = []
        context["correlationId"] = correlationId
        context["frameworkVersion"] = "1.0.0"
    }

    private Map findStage(String stageName) {
        if (!ValidationUtils.isNonEmpty(stageName)) return null
        List stages = getStagesList()
        for (Object s : stages) {
            if (s instanceof Map && stageName.equals(((Map) s).get("name"))) {
                return (Map) s
            }
        }
        return null
    }

    private List getStagesList() {
        Object stagesObj = context.get("stages")
        if (stagesObj instanceof List) return (List) stagesObj
        List newStages = []
        context["stages"] = newStages
        return newStages
    }

    @NonCPS
    private long calculateStageDuration(Map stageEntry) {
        if (stageEntry == null) return 0
        Object enteredObj = stageEntry.get("enteredAt")
        Object exitedObj = stageEntry.get("exitedAt")
        if (enteredObj instanceof Number && exitedObj instanceof Number) {
            return ((Number) exitedObj).longValue() - ((Number) enteredObj).longValue()
        }
        return 0
    }

    @NonCPS
    private long calculateElapsed() {
        if (this.pipelineStartTime <= 0) return 0
        return System.currentTimeMillis() - this.pipelineStartTime
    }

    @NonCPS
    private String formatTimestamp() {
        return formatTimestamp(System.currentTimeMillis())
    }

    @NonCPS
    private String formatTimestamp(long epochMs) {
        try {
            return new Date(epochMs).format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", TimeZone.getTimeZone("UTC"))
        } catch (Exception e) {
            return epochMs.toString()
        }
    }
}
