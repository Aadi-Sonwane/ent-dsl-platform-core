package com.enterprise.platform.security

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ShellUtils
import com.enterprise.platform.utils.ValidationUtils

class SecretScanManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final List<Map<String, Object>> SECRET_PATTERNS = buildSecretPatterns()
    private static final int MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024
    private static final int ENTROPY_THRESHOLD = 4.2
    private static final int MAX_REPORTED_FINDINGS = 200

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final ShellUtils shellUtils
    private final String correlationId
    private final List<Map> findings = []

    SecretScanManager(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.shellUtils = new ShellUtils(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    SecretScanManager(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.shellUtils = new ShellUtils(steps)
        this.correlationId = correlationId
    }

    Map scanWorkspace(String workspacePath, Map options) {
        LoggingUtils.info("SecretScanManager",
            "Starting secret scan on workspace '${workspacePath}' [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(workspacePath)) {
            throw new IllegalArgumentException("Workspace path must not be null or empty")
        }
        if (options == null) options = [:]

        findings.clear()
        long startTime = System.currentTimeMillis()

        try {
            List<String> excludePaths = resolveExcludePaths(options)
            List<String> includePatterns = resolveIncludePatterns(options)
            Boolean enableEntropyDetection = options.entropyDetection instanceof Boolean ?
                (Boolean) options.entropyDetection : true
            Boolean enableExternalTool = options.externalTool instanceof Boolean ?
                (Boolean) options.externalTool : false

            if (enableExternalTool) {
                Map externalResults = runExternalTool(workspacePath, options)
                findings.addAll(externalResults.findings ?: [])
            }

            Map fileScanResults = scanFilesRecursive(workspacePath, excludePaths, includePatterns, enableEntropyDetection)
            findings.addAll(fileScanResults.findings ?: [])

            long duration = System.currentTimeMillis() - startTime

            int criticalCount = countBySeverity("CRITICAL")
            int highCount = countBySeverity("HIGH")
            int totalSecrets = findings.size()

            LoggingUtils.info("SecretScanManager",
                "Secret scan completed in ${duration}ms: ${totalSecrets} potential secrets found [correlationId=${correlationId}]")

            audit.emitAuditEvent("SECRET_SCAN_COMPLETED",
                "Secret scan completed: ${totalSecrets} findings (${criticalCount} critical, ${highCount} high)", correlationId)
            telemetry.emitEvent("security.secret", "scan_completed", [
                correlationId: correlationId,
                durationMs: duration,
                totalFindings: totalSecrets,
                criticalCount: criticalCount,
                highCount: highCount
            ])

            /*
             * Build safe report — never include actual secret values in logs or telemetry
             */
            List<Map> safeFindings = buildSafeFindings(findings)
            checkCriticalExposure(criticalCount, highCount)

            return [
                status: criticalCount > 0 ? "FAILED" : "COMPLETED",
                correlationId: correlationId,
                durationMs: duration,
                totalFindings: totalSecrets,
                criticalCount: criticalCount,
                highCount: highCount,
                mediumCount: countBySeverity("MEDIUM"),
                lowCount: countBySeverity("LOW"),
                findings: safeFindings
            ]

        } catch (RuntimeException e) {
            if (e.message.contains("CRITICAL")) throw e
            long duration = System.currentTimeMillis() - startTime
            String errMsg = "Secret scan failed: ${e.message}"
            LoggingUtils.error("SecretScanManager", errMsg, e)
            throw new RuntimeException(errMsg, e)

        } catch (Exception e) {
            String errMsg = "Unexpected secret scan error: ${e.message}"
            LoggingUtils.error("SecretScanManager", errMsg, e)
            throw new RuntimeException(errMsg, e)
        }
    }

    List<Map> getFindings() {
        return buildSafeFindings(findings)
    }

    String getCorrelationId() {
        return this.correlationId
    }

    /*
     * File-based scanning
     */

    private Map scanFilesRecursive(String basePath, List<String> excludePaths,
                                   List<String> includePatterns, boolean enableEntropy) {
        List<Map> fileFindings = []
        List<String> scannedFiles = []
        int totalFiles = 0

        try {
            String findCommand = buildFindCommand(basePath, excludePaths)
            Map execResult = shellUtils.execute(findCommand, [
                timeoutMs: 60000,
                captureOutput: true,
                validExitCodes: [0, 1]
            ])

            String output = execResult.stdout?.toString() ?: ""
            List<String> files = output.readLines().findAll { it.trim().length() > 0 }

            if (includePatterns != null && !includePatterns.isEmpty()) {
                files = files.findAll { f ->
                    for (String pattern : includePatterns) {
                        if (f.contains(pattern) || f.matches(pattern)) return true
                    }
                    return false
                }
            }

            totalFiles = files.size()
            if (totalFiles > 10000) {
                files = files.take(10000)
                LoggingUtils.warn("SecretScanManager",
                    "Too many files to scan (${totalFiles}), limiting to 10000 [correlationId=${correlationId}]")
            }

            for (String filePath : files) {
                if (!ValidationUtils.isNonEmpty(filePath)) continue
                if (isExcluded(filePath, excludePaths)) continue
                List<Map> fileHits = scanSingleFile(filePath, enableEntropy)
                if (!fileHits.isEmpty()) {
                    fileFindings.addAll(fileHits)
                    scannedFiles.add(filePath)
                }
            }

        } catch (Exception e) {
            LoggingUtils.warn("SecretScanManager",
                "Error during recursive file scan: ${e.message}")
        }

        return [
            findings: fileFindings,
            scannedFiles: scannedFiles,
            totalFiles: totalFiles
        ]
    }

    private List<Map> scanSingleFile(String filePath, boolean enableEntropy) {
        List<Map> fileFindings = []

        try {
            if (!steps.fileExists(filePath)) return fileFindings

            Object fileSizeObj = steps.sh(
                script: "stat --format=%s '${filePath}' 2>/dev/null || echo 0",
                returnStdout: true
            ).toString().trim()
            long fileSize = 0
            try {
                fileSize = Long.parseLong(fileSizeObj)
            } catch (Exception e) { }
            if (fileSize > MAX_FILE_SIZE_BYTES || fileSize <= 0) return fileFindings

            String content = steps.readFile(file: filePath, encoding: "UTF-8")
            if (!ValidationUtils.isNonEmpty(content)) return fileFindings

            boolean isBinary = content.contains("\u0000")
            if (isBinary) {
                String extension = filePath.contains(".") ?
                    filePath.substring(filePath.lastIndexOf(".")) : ""
                List<String> binaryExts = [".gpg", ".asc", ".jar", ".class", ".png", ".jpg", ".gif", ".ico",
                                           ".woff", ".ttf", ".eot", ".pdf", ".doc", ".xls"]
                if (!binaryExts.contains(extension)) {
                    fileFindings.add(buildSecretFinding(
                        "BINARY_FILE_WITH_EMBEDDED_SECRETS",
                        filePath, 0,
                        "Binary file with null bytes detected — may contain embedded secrets",
                        "MEDIUM"
                    ))
                }
                return fileFindings
            }

            String[] lines = content.split("\\r?\\n", -1)

            for (Map<String, Object> pattern : SECRET_PATTERNS) {
                String regex = (String) pattern.get("regex")
                String category = (String) pattern.get("category")
                String severity = (String) pattern.get("severity")
                try {
                    java.util.regex.Pattern compiled = java.util.regex.Pattern.compile(regex)
                    java.util.regex.Matcher matcher = compiled.matcher(content)
                    int matchCount = 0
                    while (matcher.find() && matchCount < 5) {
                        int lineNumber = findLineNumber(lines, matcher.start())
                        String matchContext = extractContext(content, matcher.start(), matcher.end())
                        if (!isLikelyFalsePositive(matchContext, filePath)) {
                            fileFindings.add(buildSecretFinding(
                                category, filePath, lineNumber,
                                "${category} pattern matched in file",
                                severity
                            ))
                        }
                        matchCount++
                    }
                } catch (java.util.regex.PatternSyntaxException e) {
                    LoggingUtils.warn("SecretScanManager",
                        "Invalid regex pattern for '${category}': ${e.message}")
                }
            }

            if (enableEntropy && fileFindings.isEmpty() && !isLowEntropyFile(filePath)) {
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i].trim()
                    if (line.length() < 20 || line.length() > 200) continue
                    if (line.matches(".*[A-Za-z0-9+/=]{20,}.*")) {
                        double entropy = calculateShannonEntropy(line)
                        if (entropy > ENTROPY_THRESHOLD) {
                            fileFindings.add(buildSecretFinding(
                                "HIGH_ENTROPY_STRING",
                                filePath, i + 1,
                                "High-entropy string detected (entropy ${String.format('%.2f', entropy)}) — possible credential or token",
                                "MEDIUM"
                            ))
                        }
                    }
                }
            }

        } catch (Exception e) {
            LoggingUtils.warn("SecretScanManager",
                "Error scanning file '${filePath}': ${e.message}")
        }

        return fileFindings
    }

    private int findLineNumber(String[] lines, int charOffset) {
        int accumulated = 0
        for (int i = 0; i < lines.length; i++) {
            accumulated += lines[i].length() + 1
            if (accumulated > charOffset) return i + 1
        }
        return 1
    }

    private String extractContext(String content, int start, int end) {
        int contextStart = Math.max(0, start - 40)
        int contextEnd = Math.min(content.length(), end + 40)
        String full = content.substring(contextStart, contextEnd)
        if (full.length() > 120) {
            full = full.substring(0, 117) + "..."
        }
        return full.replace("\n", "\\n").replace("\r", "\\r")
    }

    @NonCPS
    private boolean isLikelyFalsePositive(String context, String filePath) {
        String lowerContext = context.toLowerCase()
        if (lowerContext.contains("example") && lowerContext.contains("password")) return true
        if (lowerContext.contains("test") && lowerContext.contains("token")) return true
        if (lowerContext.contains("fake") || lowerContext.contains("placeholder")) return true
        if (lowerContext.contains("sample") && lowerContext.contains("key")) return true
        if (filePath.contains("test") && filePath.contains("resources")) return true
        if (filePath.contains("/test/") && filePath.endsWith(".yml")) return true
        return false
    }

    @NonCPS
    private boolean isLowEntropyFile(String filePath) {
        String lower = filePath.toLowerCase()
        if (lower.endsWith(".md") || lower.endsWith(".txt")) return true
        if (lower.endsWith(".xml") || lower.endsWith(".html") || lower.endsWith(".htm")) return true
        if (lower.endsWith(".json") && !lower.endsWith("credentials.json")) return true
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return true
        if (lower.endsWith(".properties") || lower.endsWith(".conf")) return true
        return false
    }

    @NonCPS
    private double calculateShannonEntropy(String input) {
        if (input == null || input.isEmpty()) return 0.0
        Map<Character, Integer> freq = [:]
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i)
            freq[c] = (freq[c] ?: 0) + 1
        }
        double entropy = 0.0
        int len = input.length()
        for (Map.Entry<Character, Integer> entry : freq.entrySet()) {
            double p = entry.value / (double) len
            if (p > 0.0) {
                entropy -= p * (Math.log(p) / Math.log(2))
            }
        }
        return entropy
    }

    @NonCPS
    private Map buildSecretFinding(String category, String filePath, int lineNumber,
                                   String description, String severity) {
        Map finding = [:]
        finding["category"] = category
        finding["file"] = filePath
        finding["line"] = lineNumber
        finding["severity"] = severity
        finding["description"] = description
        return finding
    }

    /*
     * External tool integration
     */

    private Map runExternalTool(String workspacePath, Map options) {
        List<Map> externalFindings = []
        String tool = options.externalToolName instanceof String ?
            options.externalToolName.toString() : "gitleaks"

        try {
            String outputFile = ".secretscan-ext-${correlationId}.json"
            String cmd
            switch (tool) {
                case "gitleaks":
                    cmd = "gitleaks detect --source='${workspacePath}' --report-format=json --report-path='${outputFile}' --no-git --verbose"
                    break
                case "trufflehog":
                    cmd = "trufflehog filesystem '${workspacePath}' --json > '${outputFile}' 2>/dev/null"
                    break
                default:
                    LoggingUtils.warn("SecretScanManager",
                        "Unknown external tool '${tool}', skipping [correlationId=${correlationId}]")
                    return [findings: []]
            }

            shellUtils.execute(cmd, [
                timeoutMs: 300000,
                captureOutput: true,
                validExitCodes: [0, 1]
            ])

            if (steps.fileExists(outputFile)) {
                String output = steps.readFile(file: outputFile, encoding: "UTF-8")
                /* Parse tool output safely — never log the actual secret values */
                if (ValidationUtils.isNonEmpty(output)) {
                    try {
                        if (tool == "gitleaks") {
                            def parsed = new groovy.json.JsonSlurper().parseText(output)
                            if (parsed instanceof List) {
                                for (Object item : (List) parsed) {
                                    if (item instanceof Map) {
                                        Map f = (Map) item
                                        externalFindings.add(buildSecretFinding(
                                            "EXTERNAL_${f.RuleID?.toString() ?: 'UNKNOWN'}",
                                            f.File?.toString() ?: "unknown",
                                            f.StartLine instanceof Number ? ((Number) f.StartLine).intValue() : 0,
                                            "External secret scanner (${tool}) finding",
                                            f.Severity?.toString() ?: "HIGH"
                                        ))
                                    }
                                }
                            }
                        } else if (tool == "trufflehog") {
                            String[] lines = output.split("\\r?\\n")
                            for (String line : lines) {
                                if (!ValidationUtils.isNonEmpty(line)) continue
                                try {
                                    def parsed = new groovy.json.JsonSlurper().parseText(line)
                                    if (parsed instanceof Map) {
                                        Map f = (Map) parsed
                                        externalFindings.add(buildSecretFinding(
                                            "EXTERNAL_TRUFFLEHOG",
                                            extractStringField(f, "SourceMetadata.Data.Git.file") ?: f.Raw?.toString()?.take(50) ?: "unknown",
                                            0,
                                            "External secret scanner (${tool}) finding",
                                            "HIGH"
                                        ))
                                    }
                                } catch (Exception e) { }
                            }
                        }
                    } catch (Exception e) {
                        LoggingUtils.warn("SecretScanManager",
                            "Failed to parse external scanner output: ${e.message}")
                    }
                }
            }

            steps.sh(script: "rm -f '${outputFile}'", returnStatus: true)

        } catch (Exception e) {
            LoggingUtils.warn("SecretScanManager",
                "External secret scanner failed: ${e.message}")
        }

        return [findings: externalFindings]
    }

    /*
     * Private helpers
     */

    private String buildFindCommand(String basePath, List<String> excludePaths) {
        StringBuilder cmd = new StringBuilder()
        cmd.append("find '").append(basePath).append("' -type f")
        cmd.append(" -not -path '*/node_modules/*'")
        cmd.append(" -not -path '*/.git/*'")
        cmd.append(" -not -path '*/venv/*'")
        cmd.append(" -not -path '*/__pycache__/*'")
        cmd.append(" -not -path '*/target/*'")
        cmd.append(" -not -path '*/build/*'")
        cmd.append(" -not -path '*/dist/*'")
        cmd.append(" -not -path '*.min.js'")
        cmd.append(" -not -path '*.map'")

        if (excludePaths != null) {
            for (String ex : excludePaths) {
                if (ValidationUtils.isNonEmpty(ex)) {
                    cmd.append(" -not -path '*/").append(ex).append("/*'")
                }
            }
        }

        cmd.append(" 2>/dev/null || true")
        return cmd.toString()
    }

    @NonCPS
    private boolean isExcluded(String filePath, List<String> excludePaths) {
        if (excludePaths == null) return false
        for (String ex : excludePaths) {
            if (ValidationUtils.isNonEmpty(ex) && filePath.contains(ex)) return true
        }
        return false
    }

    @NonCPS
    private List<String> resolveExcludePaths(Map options) {
        List<String> excludes = []
        if (options.excludePaths instanceof List) {
            for (Object ex : (List) options.excludePaths) {
                if (ex instanceof String) excludes.add((String) ex)
            }
        }
        return excludes
    }

    @NonCPS
    private List<String> resolveIncludePatterns(Map options) {
        List<String> includes = []
        if (options.includePatterns instanceof List) {
            for (Object inc : (List) options.includePatterns) {
                if (inc instanceof String) includes.add((String) inc)
            }
        }
        return includes
    }

    @NonCPS
    private int countBySeverity(String severity) {
        int count = 0
        for (Map f : findings) {
            if (severity.equals(f.severity)) count++
        }
        return count
    }

    @NonCPS
    private void checkCriticalExposure(int criticalCount, int highCount) {
        List<String> violations = []

        if (criticalCount > 0) {
            violations.add("${criticalCount} CRITICAL secret(s) detected — possible credential exposure")
        }
        if (highCount > 5) {
            violations.add("${highCount} HIGH secret(s) detected — exceeds threshold of 5")
        }

        if (!violations.isEmpty()) {
            String msg = violations.join("; ")
            LoggingUtils.error("SecretScanManager",
                "Secret scan threshold exceeded: ${msg}", null)
            audit.emitAuditEvent("SECRET_THRESHOLD_EXCEEDED",
                "Secret scan threshold exceeded: ${msg}", correlationId)
            telemetry.emitEvent("security.secret", "threshold_exceeded", [
                correlationId: correlationId,
                criticalCount: criticalCount,
                highCount: highCount,
                summary: msg
            ])
            throw new RuntimeException(
                "Secret scan FAILED: ${criticalCount} CRITICAL and ${highCount} HIGH secret(s) detected. " +
                "Potential credential exposure must be resolved before proceeding.")
        }
    }

    @NonCPS
    private List<Map> buildSafeFindings(List<Map> rawFindings) {
        List<Map> safe = []
        int limit = Math.min(rawFindings.size(), MAX_REPORTED_FINDINGS)
        for (int i = 0; i < limit; i++) {
            Map raw = rawFindings.get(i)
            Map safeEntry = [:]
            safeEntry["category"] = raw.category
            safeEntry["file"] = raw.file
            safeEntry["line"] = raw.line
            safeEntry["severity"] = raw.severity
            safeEntry["description"] = raw.description
            safe.add(safeEntry)
        }
        return safe
    }

    @NonCPS
    private String extractStringField(Map map, String dottedKey) {
        if (map == null) return ""
        String[] parts = dottedKey.split("\\.")
        Object current = map
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map) current).get(part)
            } else {
                return ""
            }
        }
        return current?.toString() ?: ""
    }

    @NonCPS
    private static List<Map<String, Object>> buildSecretPatterns() {
        return [
            [regex: "-----BEGIN\\s+RSA\\s+PRIVATE\\s+KEY-----", category: "RSA_PRIVATE_KEY", severity: "CRITICAL"],
            [regex: "-----BEGIN\\s+DSA\\s+PRIVATE\\s+KEY-----", category: "DSA_PRIVATE_KEY", severity: "CRITICAL"],
            [regex: "-----BEGIN\\s+EC\\s+PRIVATE\\s+KEY-----", category: "EC_PRIVATE_KEY", severity: "CRITICAL"],
            [regex: "-----BEGIN\\s+PGP\\s+PRIVATE\\s+KEY\\s+BLOCK-----", category: "PGP_PRIVATE_KEY", severity: "CRITICAL"],
            [regex: "-----BEGIN\\s+OPENSSH\\s+PRIVATE\\s+KEY-----", category: "OPENSSH_PRIVATE_KEY", severity: "CRITICAL"],
            [regex: "-----BEGIN\\s+CERTIFICATE-----", category: "CERTIFICATE", severity: "MEDIUM"],
            [regex: "(?i)AKIA[0-9A-Z]{16}", category: "AWS_ACCESS_KEY_ID", severity: "HIGH"],
            [regex: "(?i)-----BEGIN\\s+ANY\\s+PRIVATE\\s+KEY-----", category: "GENERIC_PRIVATE_KEY", severity: "CRITICAL"],
            [regex: "(?i)(github|gh)_(pat|saas|oauth|token|key|secret|personal)[=:\\\\s][A-Za-z0-9_-]{10,}", category: "GITHUB_TOKEN", severity: "HIGH"],
            [regex: "(?i)gitlab[_-]token[=:\\\\s][A-Za-z0-9_-]{10,}", category: "GITLAB_TOKEN", severity: "HIGH"],
            [regex: "(?i)(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9_]{36,}", category: "GITHUB_PAT", severity: "CRITICAL"],
            [regex: "(?i)xox[baprs]-[0-9A-Za-Z-]{10,}", category: "SLACK_TOKEN", severity: "HIGH"],
            [regex: "(?i)sk-[A-Za-z0-9]{20,}", category: "OPENAI_API_KEY", severity: "HIGH"],
            [regex: "(?i)pk\\.[A-Za-z0-9]{20,}", category: "STRIPE_PUBLISHABLE_KEY", severity: "MEDIUM"],
            [regex: "(?i)sk\\.[A-Za-z0-9]{20,}", category: "STRIPE_SECRET_KEY", severity: "CRITICAL"],
            [regex: "(?i)(password|passwd|pwd)[=:\\\\s][A-Za-z0-9!@#\$%^&*()_+\\\\-=\\[\\]{}|;:',.<>?]{8,}", category: "PASSWORD", severity: "HIGH"],
            [regex: "(?i)(api[_-]?key|api[_-]?secret|apikey)[=:\\\\s][A-Za-z0-9_\\\\-]{10,}", category: "API_KEY", severity: "HIGH"],
            [regex: "(?i)(secret|token|credential|auth)[=:\\\\s][A-Za-z0-9_\\\\-\\\\/\\\\+=]{10,}", category: "GENERIC_SECRET", severity: "HIGH"],
            [regex: "mongodb[+srv]?://[A-Za-z0-9_]+:[A-Za-z0-9_!@#\$%^&*()]+@", category: "MONGODB_CONNECTION_STRING", severity: "HIGH"],
            [regex: "postgresql://[A-Za-z0-9_]+:[A-Za-z0-9_!@#\$%^&*()]+@", category: "POSTGRES_CONNECTION_STRING", severity: "HIGH"],
            [regex: "jdbc:mysql://[A-Za-z0-9._-]+:[0-9]+/[A-Za-z0-9_]+\\?user=[A-Za-z0-9_]+&password=[A-Za-z0-9_!@#\$%^&*()]+", category: "JDBC_CONNECTION_STRING", severity: "HIGH"],
            [regex: "redis://:[A-Za-z0-9_!@#\$%^&*()]+@", category: "REDIS_CONNECTION_STRING", severity: "HIGH"],
            [regex: "(?i)-----BEGIN\\s+PGP\\s+(SIGNATURE|MESSAGE)-----", category: "PGP_SIGNED_DATA", severity: "LOW"],
            [regex: "(?i)(sf_|salesforce|sfdc)_(username|password|token|secret)[=:\\\\s][A-Za-z0-9_\\\\-]{10,}", category: "SALESFORCE_CREDENTIAL", severity: "HIGH"],
            [regex: "(?i)(jira|confluence)_(token|api_token|api_key)[=:\\\\s][A-Za-z0-9_\\\\-]{10,}", category: "ATLASSIAN_TOKEN", severity: "HIGH"],
            [regex: "(?i)(docker|dockerhub)_?(login|password|token|pat)[=:\\\\s][A-Za-z0-9_\\\\-]{10,}", category: "DOCKER_CREDENTIAL", severity: "HIGH"]
        ]
    }
}
