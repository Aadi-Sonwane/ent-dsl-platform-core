package com.enterprise.platform.utils

class ShellUtils implements Serializable {
    private static final long serialVersionUID = 1L

    private static final int DEFAULT_TIMEOUT_MS = 600000
    private static final List<Integer> DEFAULT_VALID_EXIT_CODES = [0]
    private static final int MAX_OUTPUT_CHARS = 51200

    private final Object steps

    ShellUtils(Object steps) {
        this.steps = steps
    }

    Map execute(String command, Map options) {
        if (command == null || command.trim().length() == 0) {
            throw new IllegalArgumentException("Shell command must not be null or empty")
        }
        if (options == null) options = [:]

        boolean captureOutput = options.captureOutput instanceof Boolean ?
            (Boolean) options.captureOutput : true
        int timeoutMs = options.timeoutMs instanceof Number ?
            ((Number) options.timeoutMs).intValue() : DEFAULT_TIMEOUT_MS
        List<Integer> validExitCodes = options.validExitCodes instanceof List ?
            (List<Integer>) options.validExitCodes : DEFAULT_VALID_EXIT_CODES
        String inputData = options.inputData instanceof String ?
            options.inputData.toString() : ""

        String sanitizedCommand = LoggingUtils.sanitizeForLog(command)
        LoggingUtils.info("ShellUtils",
            "Executing command (timeout=${timeoutMs}ms, capture=${captureOutput}): ${truncate(sanitizedCommand, 500)}")

        Map result = [:]
        long startTime = System.currentTimeMillis()

        try {
            if (captureOutput) {
                String stdout = steps.sh(
                    script: command,
                    returnStdout: true,
                    timeout: timeoutMs
                ).toString()
                long duration = System.currentTimeMillis() - startTime
                String sanitizedStdout = LoggingUtils.sanitizeForLog(stdout)

                result["stdout"] = stdout
                result["exitCode"] = 0
                result["durationMs"] = duration
                result["outputTruncated"] = stdout.length() > MAX_OUTPUT_CHARS

                if (stdout.length() > MAX_OUTPUT_CHARS) {
                    LoggingUtils.warn("ShellUtils",
                        "Command output truncated at ${MAX_OUTPUT_CHARS} characters (actual: ${stdout.length()})")
                }

                LoggingUtils.info("ShellUtils",
                    "Command completed in ${duration}ms: exitCode=0, output=${sanitizedStdout.length()} chars")
            } else {
                int exitCode = steps.sh(
                    script: command,
                    returnStatus: true,
                    timeout: timeoutMs
                ) as int
                long duration = System.currentTimeMillis() - startTime

                result["exitCode"] = exitCode
                result["stdout"] = ""
                result["durationMs"] = duration

                LoggingUtils.info("ShellUtils",
                    "Command completed in ${duration}ms: exitCode=${exitCode}")
            }

            if (!validExitCodes.contains(result["exitCode"])) {
                String errMsg = "Command exited with code ${result["exitCode"]}; expected one of ${validExitCodes}"
                LoggingUtils.error("ShellUtils", errMsg, null)
                result["error"] = errMsg
                throw new RuntimeException(errMsg)
            }

        } catch (RuntimeException e) {
            if (e.message != null && e.message.contains("exited with code")) {
                long duration = System.currentTimeMillis() - startTime
                result["durationMs"] = duration
                result["exception"] = e
                throw e
            }
            long duration = System.currentTimeMillis() - startTime
            result["durationMs"] = duration
            result["error"] = e.message
            result["exception"] = e
            throw new RuntimeException("Shell execution failed: ${e.message}", e)

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime
            result["durationMs"] = duration
            result["error"] = e.message
            result["exception"] = e
            throw new RuntimeException("Shell execution failed: ${e.message}", e)
        }

        return result
    }

    Map execute(String command) {
        return execute(command, [:])
    }

    int getExitCode(String command) {
        try {
            Map result = execute(command, [captureOutput: false, validExitCodes: [0, 1, 2, 126, 127]])
            return result.exitCode instanceof Number ? ((Number) result.exitCode).intValue() : -1
        } catch (Exception e) {
            return -1
        }
    }

    boolean commandExists(String command) {
        try {
            steps.sh(script: "command -v '${command}' >/dev/null 2>&1", returnStatus: true)
            return true
        } catch (Exception e) {
            return false
        }
    }

    @NonCPS
    private String truncate(String value, int maxLength) {
        if (value == null) return ""
        if (value.length() <= maxLength) return value
        return value.substring(0, maxLength - 3) + "..."
    }
}
